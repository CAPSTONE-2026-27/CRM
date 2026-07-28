import { Queue } from "bullmq";
import { redisConnection } from "./connection.js";
import { prisma } from "../lib/prisma.js";

export const RPA_QUEUE_NAME = "rpa-bots";

export type BotJobName = "lead-enrichment" | "follow-up-sequencing" | "case-routing";

// The three bots this repo actually implements (built by the fine-tuning /
// RPA phases of the build). Other bots registered via the F01 wizard are
// registry entries only — no executable handler exists for them yet.
const BOT_NAME_TO_JOB: Record<string, BotJobName> = {
  "Lead enrichment bot": "lead-enrichment",
  "Follow-up sequencing bot": "follow-up-sequencing",
  "Case routing bot": "case-routing",
};

export const rpaQueue = new Queue(RPA_QUEUE_NAME, { connection: redisConnection });

export type BotJobData = {
  // Absent for the scheduled sweep, which runs across every org that has
  // the bot registered rather than a single tenant.
  organizationId?: string;
  botId?: string;
  triggeredBy: "event" | "schedule" | "manual";
  payload?: Record<string, unknown>;
};

export async function enqueueBotRun(botName: string, data: BotJobData) {
  const jobName = BOT_NAME_TO_JOB[botName];
  if (!jobName) {
    throw new Error(`No executable handler registered for bot "${botName}"`);
  }
  await rpaQueue.add(jobName, data, { removeOnComplete: 100, removeOnFail: 100 });
}

// Looks up a built-in bot by its registry name within an org and queues a
// run for it — used to wire record-creation events (new lead, new case) to
// their bot without the caller needing to know the bot's id.
export async function triggerBotByName(
  organizationId: string,
  botName: string,
  payload: Record<string, unknown>
) {
  const bot = await prisma.rpaBot.findFirst({ where: { organizationId, name: botName } });
  if (!bot) return; // bot not registered for this org — nothing to trigger
  await enqueueBotRun(botName, { organizationId, botId: bot.id, triggeredBy: "event", payload });
}

export async function scheduleRecurringJobs() {
  // Follow-up sequencing sweeps for stale deals/leads once an hour, across all orgs.
  await rpaQueue.upsertJobScheduler(
    "follow-up-sequencing-hourly",
    { pattern: "0 * * * *" },
    { name: "follow-up-sequencing", data: { triggeredBy: "schedule" } satisfies BotJobData }
  );
}
