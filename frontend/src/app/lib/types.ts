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

  // Flow step 3 — the model's verdict on whether this lead is worth working.
  // Distinct from `status`, which is the Hot/Warm/Cold temperature.
  qualificationStatus: QualificationStatus;
  qualificationProbability?: number | null;
  qualificationReasoning?: string | null;

  // Flow step 4
  assignedAt?: string | null;
  assignmentStatus: "UNASSIGNED" | "ASSIGNED";

  // Flow step 5
  contactStatus: ContactStatus;
  contactStatusUpdatedAt?: string | null;
  contactNotes?: string | null;

  // Flow step 6 — set once the lead becomes an opportunity, never cleared.
  convertedDealId?: string | null;
  convertedAt?: string | null;

  createdAt: string;
  updatedAt: string;
};

export type QualificationStatus = "PENDING" | "QUALIFIED" | "UNQUALIFIED";

export type ContactStatus =
  | "NOT_CONTACTED"
  | "MEETING_SCHEDULED"
  | "NO_RESPONSE"
  | "INTERESTED"
  | "NOT_INTERESTED";

export const CONTACT_STATUS_LABELS: Record<ContactStatus, string> = {
  NOT_CONTACTED: "Not contacted",
  MEETING_SCHEDULED: "Meeting scheduled",
  NO_RESPONSE: "No response",
  INTERESTED: "Interested",
  NOT_INTERESTED: "Not interested",
};

/** Only these two mean the customer is willing to meet, so only these convert.
 *  Mirrors CONVERTIBLE_CONTACT_STATUSES in LeadConversionService. */
export const CONVERTIBLE_CONTACT_STATUSES: ContactStatus[] = ["MEETING_SCHEDULED", "INTERESTED"];

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

// OPPORTUNITY_CREATED and MEETING_SCHEDULED were added ahead of the original
// stages rather than replacing them — existing deals still sit on PROSPECTING
// and QUALIFICATION.
export type DealStage =
  | "OPPORTUNITY_CREATED"
  | "MEETING_SCHEDULED"
  | "PROSPECTING"
  | "QUALIFICATION"
  | "PROPOSAL"
  | "NEGOTIATION"
  | "CLOSED_WON"
  | "CLOSED_LOST";

export const DEAL_STAGE_LABELS: Record<DealStage, string> = {
  OPPORTUNITY_CREATED: "Opportunity created",
  MEETING_SCHEDULED: "Meeting scheduled",
  PROSPECTING: "Prospecting",
  QUALIFICATION: "Qualification",
  PROPOSAL: "Proposal",
  NEGOTIATION: "Negotiation",
  CLOSED_WON: "Closed won",
  CLOSED_LOST: "Closed lost",
};

/** The workflow order the progress tracker walks. Closed lost is deliberately
 *  absent — it is an exit, not a step forward. */
export const DEAL_FLOW_STEPS: DealStage[] = [
  "OPPORTUNITY_CREATED",
  "MEETING_SCHEDULED",
  "PROPOSAL",
  "NEGOTIATION",
  "CLOSED_WON",
];

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
  dealScoreBand?: ScoreBand | null;
  dealScoreAction?: string | null;
  dealScoreModelVersion?: string | null;
  dealScoredAt?: string | null;

  // Opportunity identity and the deal flow's own fields.
  opportunityId?: string | null;
  leadId?: string | null;
  meetingScheduledAt?: string | null;
  meetingMode?: string | null;
  meetingParticipants?: string | null;
  winProbability?: number | null;
  riskLevel?: RiskLevel | null;
  closingReason?: string | null;
  closedAt?: string | null;
};

export type ScoreBand = "HIGH" | "MEDIUM" | "LOW" | "VERY LOW";
export type RiskLevel = "LOW" | "MEDIUM" | "HIGH";

/* ---- Deal analysis pipeline (deal flow steps 4-10) ---- */

/** One business parameter the analysis model read out of a meeting write-up. */
export type ExtractedParameter = {
  name: string;
  displayName: string;
  value: string | null;
  confidence?: number | null;
  explanation?: string | null;
};

export type FeatureSet = {
  id: string;
  /** Engineered 0-1 vector, oriented so higher is always better for the deal. */
  features: Record<string, number>;
  /** The categorical labels actually sent to the XGBoost model. */
  modelInputs: Record<string, string | number>;
  /** Parameters the model could not read, where a default stood in. */
  imputedFields: string[];
};

export type DealPrediction = {
  id: string;
  dealScore: number;
  winProbability?: number | null;
  band?: ScoreBand | null;
  riskLevel?: RiskLevel | null;
  /** 0-1 mean extraction confidence — how much evidence the score rests on. */
  confidence?: number | null;
  recommendedAction?: string | null;
  positiveFactors: string[];
  negativeFactors: string[];
  modelVersion?: string | null;
  predictedAt: string;
};

export type DealAnalysisStatus = "SUCCEEDED" | "DEGRADED";

export type DealAnalysis = {
  id: string;
  status: DealAnalysisStatus;
  modelVersion?: string | null;
  latencyMs?: number | null;
  errorMessage?: string | null;
  createdAt: string;
};

/** One full turn of the pipeline: the write-up and everything derived from it.
 *  Append-only and versioned per deal — a new meeting never replaces an older. */
export type MeetingOutputDetail = {
  id: string;
  dealId: string;
  opportunityId?: string | null;
  leadId?: string | null;
  version: number;

  meetingDate: string;
  meetingTime: string;
  meetingType?: string | null;
  participants?: string | null;
  meetingSummary?: string | null;
  customerRequirements?: string | null;
  keyDiscussionPoints?: string | null;
  customerQuestions?: string | null;
  competitorMentioned?: string | null;
  objections?: string | null;
  budgetDiscussion?: string | null;
  timeline?: string | null;
  nextSteps?: string | null;
  executiveRemarks?: string | null;

  submittedById?: string | null;
  createdAt: string;

  analysis?: DealAnalysis | null;
  parameters: ExtractedParameter[];
  featureSet?: FeatureSet | null;
  prediction?: DealPrediction | null;
};

export type ManagerDecision = "APPROVED" | "REJECTED" | "OVERRIDDEN";

export type ManagerReview = {
  id: string;
  dealId: string;
  decision: ManagerDecision;
  recommendedAction?: string | null;
  overriddenAction?: string | null;
  comments?: string | null;
  reviewedById?: string | null;
  createdAt: string;
};

export type DealWorkspace = {
  dealId: string;
  opportunityId?: string | null;
  leadId?: string | null;
  name: string;
  stage: DealStage;
  value: string | number;
  currency: string;
  meetingScheduledAt?: string | null;
  meetingMode?: string | null;
  meetingParticipants?: string | null;
  closingReason?: string | null;
  closedAt?: string | null;

  latestPrediction?: DealPrediction | null;
  meetings: MeetingOutputDetail[];
  reviews: ManagerReview[];
};

export type CustomerOnboarding = {
  id: string;
  dealId: string;
  opportunityId?: string | null;
  status: "INITIATED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
  notes?: string | null;
  initiatedAt: string;
  completedAt?: string | null;
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
