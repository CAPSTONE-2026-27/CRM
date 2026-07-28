import { prisma } from "../lib/prisma.js";
import { getAiProvider } from "../services/aiProvider.js";
import { writeAuditLog } from "../lib/audit.js";
import type { BotJobData } from "../queue/queues.js";

export async function runCaseRouting(data: BotJobData) {
  const caseId = data.payload?.caseId as string | undefined;
  if (!data.organizationId || !caseId) return;

  const caseRow = await prisma.case.findUniqueOrThrow({ where: { id: caseId } });

  const ai = getAiProvider();
  const model = process.env.AI_MODEL_NAME ?? "local-model";
  const completion = await ai.chat([
    {
      role: "system",
      content:
        'Classify this support case. Respond with strict JSON: {"priority": "LOW"|"MEDIUM"|"HIGH"|"CRITICAL", "category": string}.',
    },
    { role: "user", content: `Case subject: ${caseRow.subject}. Source: ${caseRow.source ?? "unknown"}.` },
  ]);
  const parsed = JSON.parse(completion.choices[0]?.message?.content ?? "{}") as {
    priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
    category: string;
  };

  // Assign to the support agent with the fewest currently-open cases (simple load balancing).
  const agents = await prisma.user.findMany({
    where: { organizationId: data.organizationId, role: "SUPPORT_AGENT" },
    include: { _count: { select: { casesAssigned: { where: { status: { notIn: ["RESOLVED", "CLOSED"] } } } } } },
  });
  const assignee = agents.sort((a, b) => a._count.casesAssigned - b._count.casesAssigned)[0];

  await prisma.case.update({
    where: { id: caseRow.id },
    data: {
      priority: parsed.priority,
      assignedToId: assignee?.id,
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
        logs: `Classified "${caseRow.subject}" as ${parsed.priority}/${parsed.category}, assigned to ${assignee?.fullName ?? "unassigned queue"}`,
      },
    });
  }

  await writeAuditLog({
    organizationId: data.organizationId,
    actorType: "bot",
    actorLabel: "Case routing bot",
    event: "Case routed",
    detail: `#${caseRow.caseNumber} "${caseRow.subject}" → ${parsed.priority}, assigned to ${assignee?.fullName ?? "unassigned"}`,
    severity: "info",
    relatedEntityType: "Case",
    relatedEntityId: caseRow.id,
    aiModelVersion: model,
  });
}
