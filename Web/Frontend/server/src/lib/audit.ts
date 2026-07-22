import { prisma } from "./prisma.js";

export async function writeAuditLog(entry: {
  organizationId: string;
  actorType: "user" | "bot" | "system";
  actorUserId?: string;
  actorLabel?: string;
  event: string;
  detail?: string;
  severity: "ok" | "info" | "warning" | "alert";
  relatedEntityType?: string;
  relatedEntityId?: string;
  aiModelVersion?: string;
  aiConfidence?: number;
}) {
  return prisma.auditLog.create({ data: entry });
}
