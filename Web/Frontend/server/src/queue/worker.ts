import "dotenv/config";
import { Worker } from "bullmq";
import { redisConnection } from "./connection.js";
import { RPA_QUEUE_NAME, scheduleRecurringJobs, type BotJobData } from "./queues.js";
import { runLeadEnrichment } from "../bots/leadEnrichment.js";
import { runFollowUpSequencing } from "../bots/followUpSequencing.js";
import { runCaseRouting } from "../bots/caseRouting.js";
import { prisma } from "../lib/prisma.js";
import { writeAuditLog } from "../lib/audit.js";

const worker = new Worker<BotJobData>(
  RPA_QUEUE_NAME,
  async (job) => {
    switch (job.name) {
      case "lead-enrichment":
        return runLeadEnrichment(job.data);
      case "follow-up-sequencing":
        return runFollowUpSequencing(job.data);
      case "case-routing":
        return runCaseRouting(job.data);
      default:
        throw new Error(`Unknown job: ${job.name}`);
    }
  },
  {
    connection: redisConnection,
    concurrency: 3,
    // Every job calls the LLM once — cap throughput so a bulk CSV import
    // doesn't slam a rate-limited provider (e.g. Groq free tier) with 429s.
    limiter: { max: 20, duration: 60_000 },
  }
);

worker.on("failed", async (job, err) => {
  console.error(`RPA job ${job?.name} (${job?.id}) failed:`, err);
  if (job?.data.organizationId) {
    if (job.data.botId) {
      await prisma.rpaBotRun.create({
        data: {
          organizationId: job.data.organizationId,
          botId: job.data.botId,
          status: "ERROR",
          triggeredBy: job.data.triggeredBy,
          finishedAt: new Date(),
          errorMessage: err.message,
        },
      });
    }
    await writeAuditLog({
      organizationId: job.data.organizationId,
      actorType: "bot",
      actorLabel: job.name,
      event: "Bot run failed",
      detail: err.message,
      severity: "alert",
    });
  }
});

scheduleRecurringJobs().catch((err) => console.error("Failed to schedule recurring jobs:", err));

console.log("RPA worker listening for jobs on queue:", RPA_QUEUE_NAME);
