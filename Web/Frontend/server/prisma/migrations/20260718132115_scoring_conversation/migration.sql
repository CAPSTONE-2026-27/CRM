-- Move the LLM scoring-conversation log from a local SQLite file into Postgres
CREATE TABLE "ScoringConversation" (
    "id" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "leadId" TEXT NOT NULL,
    "model" TEXT NOT NULL,
    "systemPrompt" TEXT NOT NULL,
    "userPrompt" TEXT NOT NULL,
    "response" TEXT NOT NULL,
    "outcome" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ScoringConversation_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "ScoringConversation_organizationId_idx" ON "ScoringConversation"("organizationId");
CREATE INDEX "ScoringConversation_leadId_idx" ON "ScoringConversation"("leadId");
