import { prisma } from "./prisma.js";

// Records each prompt/response exchanged with the LLM while scoring a lead —
// an audit trail of exactly what the model saw and said. Now stored in
// Postgres (previously a local SQLite file) so nothing is persisted locally.
export async function logScoringConversation(entry: {
  leadId: string;
  organizationId: string;
  model: string;
  systemPrompt: string;
  userPrompt: string;
  response: string;
  outcome: "scored" | "parse_failed" | "ai_error";
}): Promise<void> {
  await prisma.scoringConversation.create({ data: entry });
}

export async function getScoringConversations(leadId: string, organizationId: string) {
  return prisma.scoringConversation.findMany({
    where: { leadId, organizationId },
    orderBy: { id: "desc" },
  });
}
