export type Role = "ADMIN" | "MANAGER" | "SALES_REP" | "SUPPORT_AGENT" | "MARKETING";

// Mirrors UserResponse from the API. Adding a field that the API does not
// return gives no type error but reads back as undefined at runtime.
export type UserRow = {
  id: string;
  fullName: string;
  email: string;
  username?: string | null;
  jobTitle?: string | null;
  department?: string | null;
  role: Role;
  status: "ACTIVE" | "INACTIVE";
  mustChangePassword: boolean;
  lastLoginAt?: string | null;
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

  // Deal-scoring model inputs, as assessed by the rep.
  totalMeetings?: number | null;
  leadScore?: number | null;
  customerSentiment?: string | null;
  buyingIntent?: string | null;
  relationshipStrength?: number | null;
  budgetStatus?: string | null;
  decisionMakerInvolvement?: string | null;
  customerUrgency?: string | null;
  mainObjections?: string | null;
  productInterestLevel?: string | null;
  meetingOutcome?: string | null;
  customerRequirements?: string | null;
  riskFactors?: string | null;
  competitorMention?: string | null;
  engagementScore?: number | null;
  implementationReadiness?: string | null;
  upsellOpportunity?: string | null;

  // Model output. Null when the deal has no scoring inputs yet, or the model
  // was unreachable when it was last saved.
  dealScore?: number | null;
  dealScoreBand?: "HIGH" | "MEDIUM" | "LOW" | "VERY LOW" | null;
  dealScoreAction?: string | null;
  dealScoreModelVersion?: string | null;
  dealScoredAt?: string | null;
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

// Mirrors AuditLogResponse from the API.
export type AuditLogEntry = {
  id: string;
  actorUserId?: string | null;
  action: string;
  entityType: string;
  entityId?: string | null;
  detail?: string | null;
  occurredAt: string;
};

// Lead Output module — one record per customer meeting, permanently linked to
// its lead. History is append-only; a new meeting never replaces an older one.
export type LeadMeeting = {
  id: string;
  leadId: string;
  meetingDate: string;
  meetingTime: string;
  meetingOutput: string;
  aiSummary: string;
  previousScore?: number | null;
  updatedScore?: number | null;
  scoreChangeReason?: string | null;
  aiModelVersion?: string | null;
  recordedBy?: { fullName: string } | null;
  createdAt: string;
};

// Result of the AI preview step — not persisted until the rep saves.
export type MeetingAnalysis = {
  leadId: string;
  leadName: string;
  meetingDate: string;
  meetingTime: string;
  meetingOutput: string;
  aiSummary: string;
  previousScore: number | null;
  updatedScore: number;
  scoreDifference: number;
  scoreLabel: "Hot" | "Warm" | "Cold";
  reasons: string[];
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
