export type Role = "ADMIN" | "MANAGER" | "SALES_REP" | "SUPPORT_AGENT" | "MARKETING";

// Mirrors the `publicUser` select in server/src/routes/users.ts. Fields not in
// that select (and not columns on User) must not be added here — they read back
// as undefined at runtime with no type error to warn you.
export type UserRow = {
  id: string;
  fullName: string;
  email: string;
  jobTitle?: string | null;
  department?: string | null;
  role: Role;
  status: "ACTIVE" | "INACTIVE";
  permissions: string[];
};

export type Lead = {
  id: string;
  fullName: string;
  company: string;
  industry?: string | null;
  employeeCount?: string | null;
  email?: string | null;
  phone?: string | null;
  product?: string | null;
  estimatedDealValue?: string | number | null;
  sourceChannel?: string | null;
  captureMethod?: "WEB_FORM" | "EMAIL_PARSING" | "RPA_BOT_IMPORT" | "CSV_IMPORT";
  notes?: string | null;
  aiScore?: number | null;
  aiScoreLabel?: string | null;
  aiScoreReason?: string | null;
  status: "NEW" | "WARM" | "HOT" | "COLD";
  assignedToId?: string | null;
  createdAt: string;
};

export type Account = {
  id: string;
  name: string;
  industry?: string | null;
  employeeCount?: string | null;
  relationshipValue?: string | number | null;
  aiSentimentScore?: number | null;
  createdAt: string;
};

export type Contact = {
  id: string;
  accountId: string;
  fullName: string;
  jobTitle?: string | null;
  email?: string | null;
  role?: string | null;
  isPrimary: boolean;
};

export type DealStage = "PROSPECTING" | "QUALIFICATION" | "PROPOSAL" | "NEGOTIATION" | "CLOSED_WON" | "CLOSED_LOST";

export type Deal = {
  id: string;
  name: string;
  accountId: string;
  account?: { name: string };
  value: string | number;
  currency: string;
  stage: DealStage;
  probability?: number | null;
  createdAt: string;
};

export type CaseRow = {
  id: string;
  caseNumber: number;
  subject: string;
  source?: string | null;
  priority: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  status: "OPEN" | "IN_PROGRESS" | "ESCALATED" | "RESOLVED" | "CLOSED";
  slaDeadline?: string | null;
  createdAt: string;
};

export type Campaign = {
  id: string;
  name: string;
  channel: "EMAIL" | "SMS_EMAIL" | "MULTI_CHANNEL";
  status: "DRAFT" | "SCHEDULED" | "ACTIVE" | "COMPLETED";
  sentCount: number;
  openRatePct?: number | null;
  segment?: string | null;
  region?: string | null;
  createdAt: string;
};

export type RpaBot = {
  id: string;
  name: string;
  platform: "UIPATH" | "AUTOMATION_ANYWHERE" | "BLUE_PRISM";
  status: "REGISTERED" | "SCHEDULED" | "RUNNING" | "ERROR" | "DEPLOYED";
  createdAt: string;
};

export type RpaBotRun = {
  id: string;
  botId: string;
  bot?: { name: string; platform: string };
  status: "RUNNING" | "SCHEDULED" | "SUCCESS" | "ERROR";
  tasksCompleted: number;
  startedAt: string;
  finishedAt?: string | null;
  logs?: string | null;
};

export type WorkflowNode = { type: "TRIGGER" | "CONDITION" | "AI" | "RPA" | "ACTION" | "LOG"; title: string; label: string; operation?: string; order: number };

export type WorkflowDefinition = {
  id: string;
  name: string;
  triggerEvent?: string | null;
  isActive: boolean;
  nodes: WorkflowNode[];
  createdAt: string;
};

// Mirrors the AuditLog model as returned by GET /audit-log (which also
// includes the actor's name). Field names are the server's — an earlier
// version of this type invented `action`/`entityType`/`entityId`, which are
// not columns and read back as undefined.
export type AuditLogEntry = {
  id: string;
  occurredAt: string;
  actorType: "user" | "bot" | "system";
  actorUserId?: string | null;
  actorLabel?: string | null;
  actorUser?: { fullName: string } | null;
  event: string;
  detail?: string | null;
  severity: "ok" | "info" | "warning" | "alert";
  relatedEntityType?: string | null;
  relatedEntityId?: string | null;
  aiModelVersion?: string | null;
};

export type PagedResponse<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type LeadStats = {
  totalLeads: number;
  aiScored: number;
  csvImported: number;
  botImported: number;
};

export type DashboardSummary = {
  metrics: { totalLeads: number; pipelineValue: string | number; openCases: number; rpaBotsActive: number; rpaBotsTotal: number };
  recentLeads: Lead[];
  pipelineByStage: { stage: DealStage; _sum: { value: string | number | null }; _count: { _all: number } }[];
  recentBotRuns: RpaBotRun[];
};

export type ReportingSummary = {
  revenueMtd: string | number;
  winRatePct: number;
  avgDealSize: string | number | null;
  churnRiskCount: number;
  revenueTrend: { month: string; revenue: string | number }[];
};

export type SecuritySummary = {
  auditEvents24h: number;
  failedLogins24h: number;
  mfaCoveragePct: number;
};
