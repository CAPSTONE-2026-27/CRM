import { getAiProvider } from "./aiProvider.js";

// Produces the post-meeting summary and a re-scored lead in a single model
// call — the score has to be justified by the same meeting notes the summary
// is drawn from, so splitting them risks the two disagreeing.
const SYSTEM_PROMPT =
  "You are a CRM assistant supporting a sales representative after a customer meeting. " +
  "You will be given a lead's profile, its previous AI score, and the rep's raw meeting notes. " +
  "Do two things: (1) write a concise 2-4 sentence summary of what happened in the meeting, " +
  "(2) re-score the lead from 0-100 based on the buying signals in those notes, taking the " +
  "previous score as the starting point and moving it up or down only as far as the notes justify. " +
  "Weigh budget confirmation, decision-maker involvement, timeline, explicit next steps, and " +
  "competitor pressure. Base everything strictly on the notes provided — never invent details. " +
  'Respond with ONLY strict JSON, no prose, no markdown fences: ' +
  '{"summary": "<2-4 sentences>", "score": <0-100>, "label": "Hot"|"Warm"|"Cold", "reasons": ["<short phrase>", "..."]}. ' +
  "Give 2-5 reasons, each a short phrase explaining the score movement.";

export type MeetingAnalysis = {
  summary: string;
  score: number;
  label: "Hot" | "Warm" | "Cold";
  reasons: string[];
  model: string;
};

export type MeetingAnalysisInput = {
  lead: {
    fullName: string;
    company: string;
    industry: string | null;
    product: string | null;
    estimatedDealValue: unknown;
    notes: string | null;
    aiScore: number | null;
  };
  meetingDate: string;
  meetingTime: string;
  meetingOutput: string;
};

function buildPrompt({ lead, meetingDate, meetingTime, meetingOutput }: MeetingAnalysisInput): string {
  const lines = [`Contact: ${lead.fullName}`, `Company: ${lead.company}`];
  if (lead.industry) lines.push(`Industry: ${lead.industry}`);
  if (lead.product) lines.push(`Product interest: ${lead.product}`);
  if (lead.estimatedDealValue != null) lines.push(`Estimated deal value: ${lead.estimatedDealValue}`);
  if (lead.notes) lines.push(`Existing notes: ${lead.notes}`);
  lines.push(`Previous AI score: ${lead.aiScore ?? "not scored yet"}`);
  lines.push("", `Meeting held on ${meetingDate} at ${meetingTime}.`, "Rep's meeting notes:", meetingOutput);
  return lines.join("\n");
}

// Same defensive parsing as the lead-scoring bot: small models often wrap JSON
// in fences or surround it with prose despite being told not to.
function parseAnalysis(raw: string): Omit<MeetingAnalysis, "model"> | null {
  const match = raw.match(/\{[\s\S]*\}/);
  if (!match) return null;
  try {
    const parsed = JSON.parse(match[0]);
    const score = Math.round(Number(parsed.score));
    const summary = String(parsed.summary ?? "").trim();
    if (!Number.isFinite(score) || !summary) return null;

    const label = String(parsed.label ?? "").toLowerCase();
    const reasons = Array.isArray(parsed.reasons)
      ? parsed.reasons.map((r: unknown) => String(r).trim()).filter(Boolean).slice(0, 5)
      : [];

    return {
      summary: summary.slice(0, 2000),
      score: Math.min(100, Math.max(0, score)),
      label: label === "hot" ? "Hot" : label === "cold" ? "Cold" : "Warm",
      reasons,
    };
  } catch {
    return null;
  }
}

export async function analyzeMeeting(input: MeetingAnalysisInput): Promise<MeetingAnalysis> {
  const ai = getAiProvider();
  const model = process.env.AI_MODEL_NAME ?? "local-model";

  const completion = await ai.chat([
    { role: "system", content: SYSTEM_PROMPT },
    { role: "user", content: buildPrompt(input) },
  ]);

  const raw = completion.choices[0]?.message?.content ?? "";
  const parsed = parseAnalysis(raw);
  if (!parsed) {
    throw new Error(`Model response was not parseable as a meeting analysis: ${raw.slice(0, 200)}`);
  }
  return { ...parsed, model };
}
