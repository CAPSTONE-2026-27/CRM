-- CreateTable
CREATE TABLE "LeadMeeting" (
    "id" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "leadId" TEXT NOT NULL,
    "recordedById" TEXT,
    "meetingDate" DATE NOT NULL,
    "meetingTime" TEXT NOT NULL,
    "meetingOutput" TEXT NOT NULL,
    "aiSummary" TEXT NOT NULL,
    "previousScore" INTEGER,
    "updatedScore" INTEGER,
    "scoreChangeReason" TEXT,
    "aiModelVersion" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "LeadMeeting_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "LeadMeeting_organizationId_idx" ON "LeadMeeting"("organizationId");

-- CreateIndex
CREATE INDEX "LeadMeeting_leadId_idx" ON "LeadMeeting"("leadId");

-- AddForeignKey
ALTER TABLE "LeadMeeting" ADD CONSTRAINT "LeadMeeting_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "Organization"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "LeadMeeting" ADD CONSTRAINT "LeadMeeting_leadId_fkey" FOREIGN KEY ("leadId") REFERENCES "Lead"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "LeadMeeting" ADD CONSTRAINT "LeadMeeting_recordedById_fkey" FOREIGN KEY ("recordedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
