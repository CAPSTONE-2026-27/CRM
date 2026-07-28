import { prisma } from "../lib/prisma.js";
import { getAiProvider } from "../services/aiProvider.js";
import { writeAuditLog } from "../lib/audit.js";
import { logScoringConversation } from "../lib/scoringLog.js";
import { runScoreTriggeredWorkflows } from "../services/workflowEngine.js";
import type { BotJobData } from "../queue/queues.js";

const SYSTEM_PROMPT =
  "You are a CRM lead-scoring assistant. Score the lead from 0-100 based on fit and intent signals " +
  "(deal value, industry, company size, source channel, and buying signals in the notes). " +
  'Respond with ONLY strict JSON, no prose, no markdown fences: {"score": <0-100>, "label": "Hot"|"Warm"|"Cold", "reason": "<one sentence>"}';

// Builds the scoring prompt from whatever form parameters the sales exec
// actually filled in — missing fields are omitted rather than faked.
function buildLeadDescription(lead: {
  fullName: string;
  company: string;
  industry: string | null;
  employeeCount: string | null;
  product: string | null;
  estimatedDealValue: unknown;
  sourceChannel: string | null;
  notes: string | null;
}): string {
  const lines = [`Contact: ${lead.fullName}`, `Company: ${lead.company}`];
  if (lead.industry) lines.push(`Industry: ${lead.industry}`);
  if (lead.employeeCount) lines.push(`Company size: ${lead.employeeCount} employees`);
  if (lead.product) lines.push(`Product interest: ${lead.product}`);
  if (lead.estimatedDealValue != null) lines.push(`Estimated deal value: ${lead.estimatedDealValue}`);
  if (lead.sourceChannel) lines.push(`Source channel: ${lead.sourceChannel}`);
  if (lead.notes) lines.push(`Notes from sales executive: ${lead.notes}`);
  return lines.join("\n");
}

// Small local models often wrap JSON in ```fences``` or add prose around it
// despite instructions — extract and validate rather than trusting JSON.parse.
function parseScoreResponse(raw: string): { score: number; label: "Hot" | "Warm" | "Cold"; reason: string } | null {
  const match = raw.match(/\{[\s\S]*\}/);
  if (!match) return null;
  try {
    const parsed = JSON.parse(match[0]);
    const score = Math.round(Number(parsed.score));
    if (!Number.isFinite(score)) return null;
    const label = String(parsed.label ?? "").toLowerCase();
    const normalized = label === "hot" ? "Hot" : label === "cold" ? "Cold" : "Warm";
    return {
      score: Math.min(100, Math.max(0, score)),
      label: normalized,
      reason: String(parsed.reason ?? "").slice(0, 500),
    };
  } catch {
    return null;
  }
}

export async function runLeadEnrichment(data: BotJobData) {
  const leadId = data.payload?.leadId as string | undefined;
  if (!data.organizationId || !leadId) return;

  const lead = await prisma.lead.findUniqueOrThrow({ where: { id: leadId } });
  const ai = getAiProvider();
  const model = process.env.AI_MODEL_NAME ?? "local-model";
  const userPrompt = buildLeadDescription(lead);

  let raw = "";
  try {
    const completion = await ai.chat([
      { role: "system", content: SYSTEM_PROMPT },
      { role: "user", content: userPrompt },
    ]);
    raw = completion.choices[0]?.message?.content ?? "";
  } catch (err) {
    await logScoringConversation({
      leadId: lead.id,
      organizationId: data.organizationId,
      model,
      systemPrompt: SYSTEM_PROMPT,
      userPrompt,
      response: err instanceof Error ? err.message : String(err),
      outcome: "ai_error",
    });
    throw err; // let the worker record the failed run
  }

  const parsed = parseScoreResponse(raw);

  await logScoringConversation({
    leadId: lead.id,
    organizationId: data.organizationId,
    model,
    systemPrompt: SYSTEM_PROMPT,
    userPrompt,
    response: raw,
    outcome: parsed ? "scored" : "parse_failed",
  });

  if (!parsed) {
    throw new Error(`Model response was not parseable as a score: ${raw.slice(0, 200)}`);
  }

  await prisma.lead.update({
    where: { id: lead.id },
    data: {
      aiScore: parsed.score,
      aiScoreLabel: `${parsed.label} lead`,
      aiScoreReason: parsed.reason,
      status: parsed.label.toUpperCase() as "HOT" | "WARM" | "COLD",
    },
  });

  if (data.botId) {
    await prisma.rpaBotRun.create({
      data: {
        organizationId: data.organizationId,
        botId: data.botId,
        status: "SUCCESS",
        tasksCompleted: 1,
        finishedAt: new Date(),
        triggeredBy: data.triggeredBy,
        aiModelVersion: model,
        logs: `Scored ${lead.fullName} (${lead.company}): ${parsed.score} (${parsed.label}) — ${parsed.reason}`,
      },
    });
  }

  await writeAuditLog({
    organizationId: data.organizationId,
    actorType: "bot",
    actorLabel: "Lead enrichment bot",
    event: "Lead scored",
    detail: `${lead.fullName} scored ${parsed.score} (${parsed.label})`,
    severity: "info",
    relatedEntityType: "Lead",
    relatedEntityId: lead.id,
    aiModelVersion: model,
  });

  await runScoreTriggeredWorkflows(data.organizationId, { id: lead.id, fullName: lead.fullName, aiScore: parsed.score });
}
