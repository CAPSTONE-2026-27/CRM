import { prisma } from "../lib/prisma.js";
import { getAiProvider } from "../services/aiProvider.js";
import { writeAuditLog } from "../lib/audit.js";
import type { BotJobData } from "../queue/queues.js";

const STALE_DEAL_DAYS = 7;
const STALE_LEAD_DAYS = 3;

async function draftFollowUp(subject: string): Promise<string> {
  const ai = getAiProvider();
  const completion = await ai.chat([
    { role: "system", content: "Draft a short, friendly CRM follow-up email (3-5 sentences). Return only the email body." },
    { role: "user", content: subject },
  ]);
  return completion.choices[0]?.message?.content ?? "";
}

async function runForOrg(organizationId: string, botId: string | undefined, triggeredBy: BotJobData["triggeredBy"]) {
  const now = Date.now();
  const staleDeals = await prisma.deal.findMany({
    where: {
      organizationId,
      stage: { notIn: ["CLOSED_WON", "CLOSED_LOST"] },
      updatedAt: { lt: new Date(now - STALE_DEAL_DAYS * 86_400_000) },
    },
    include: { account: { select: { name: true } } },
    take: 20,
  });
  const staleLeads = await prisma.lead.findMany({
    where: {
      organizationId,
      status: { in: ["NEW", "WARM"] },
      updatedAt: { lt: new Date(now - STALE_LEAD_DAYS * 86_400_000) },
    },
    take: 20,
  });

  const model = process.env.AI_MODEL_NAME ?? "local-model";
  let drafted = 0;

  for (const deal of staleDeals) {
    const body = await draftFollowUp(`Deal "${deal.name}" with ${deal.account.name} has been stale in ${deal.stage} for over ${STALE_DEAL_DAYS} days.`);
    await writeAuditLog({
      organizationId,
      actorType: "bot",
      actorLabel: "Follow-up sequencing bot",
      event: "Follow-up email drafted",
      detail: `Deal "${deal.name}" (${deal.account.name}): ${body}`,
      severity: "info",
      relatedEntityType: "Deal",
      relatedEntityId: deal.id,
      aiModelVersion: model,
    });
    drafted++;
  }

  for (const lead of staleLeads) {
    const body = await draftFollowUp(`Lead ${lead.fullName} at ${lead.company} has not been followed up on in over ${STALE_LEAD_DAYS} days.`);
    await writeAuditLog({
      organizationId,
      actorType: "bot",
      actorLabel: "Follow-up sequencing bot",
      event: "Follow-up email drafted",
      detail: `Lead ${lead.fullName} (${lead.company}): ${body}`,
      severity: "info",
      relatedEntityType: "Lead",
      relatedEntityId: lead.id,
      aiModelVersion: model,
    });
    drafted++;
  }

  if (botId) {
    await prisma.rpaBotRun.create({
      data: {
        organizationId,
        botId,
        status: "SUCCESS",
        tasksCompleted: drafted,
        finishedAt: new Date(),
        triggeredBy,
        aiModelVersion: model,
        logs: `Drafted ${drafted} follow-up email(s) across ${staleDeals.length} stale deal(s) and ${staleLeads.length} stale lead(s)`,
      },
    });
  }
}

export async function runFollowUpSequencing(data: BotJobData) {
  if (data.organizationId) {
    await runForOrg(data.organizationId, data.botId, data.triggeredBy);
    return;
  }

  // Scheduled sweep: run for every org that has this bot registered.
  const bots = await prisma.rpaBot.findMany({ where: { name: "Follow-up sequencing bot" } });
  for (const bot of bots) {
    await runForOrg(bot.organizationId, bot.id, "schedule");
  }
}
