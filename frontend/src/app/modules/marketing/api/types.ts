// Mirrors com.techcrm.crm.marketing.api.MarketingDtos. Keep the two in step.

export type CampaignStatus =
  | "DRAFT"
  | "AI_PROCESSING"
  | "PENDING_LEAD_APPROVAL"
  | "PENDING_CONTENT_APPROVAL"
  | "READY_TO_LAUNCH"
  | "SCHEDULED"
  | "ACTIVE"
  | "PAUSED"
  | "COMPLETED"
  | "CANCELLED";

export type PipelineStatus = "IDLE" | "RUNNING" | "COMPLETED" | "FAILED";
/** Marketing Automation is email-only. */
export type Channel = "EMAIL";
export type ContentChannel = Channel;
export type Provenance = "AI_GENERATED" | "HUMAN_EDITED" | "SYSTEM_GENERATED" | "HUMAN_AUTHORED";
export type LeadApproval = "PENDING" | "APPROVED" | "REJECTED" | "REMOVED";
export type Eligibility = "ELIGIBLE" | "NOT_ELIGIBLE";

export type Audience = {
  industries: string[];
  companySize: string | null;
  locations: string[];
  customerTypes: string[];
  leadSources: string[];
  productInterest: string | null;
  minDealValue: number | null;
  maxDealValue: number | null;
  purchaseTimeline: string | null;
  customerSegment: string | null;
  customerStatus: "ANY" | "EXISTING_CUSTOMERS" | "NEW_LEADS";
  otherConditions: string | null;
};

export type ContentConfig = {
  channels: Channel[];
  contentTypes: string[];
  tone: string | null;
  language: string | null;
  writingStyle: string | null;
  cta: string | null;
  additionalInstructions: string | null;
  senderName: string | null;
  senderEmail: string | null;
  replyToEmail: string | null;
  personalization: boolean | null;
};

export type Criterion = { field: string; operator: string; value: string };

export type CampaignRequest = {
  name: string;
  campaignType: string;
  objective: string | null;
  description: string | null;
  startDate: string | null;
  endDate: string | null;
  productName: string | null;
  productDescription: string | null;
  mainBenefits: string | null;
  offer: string | null;
  discount: string | null;
  pricingInfo: string | null;
  sellingPoints: string | null;
  callToAction: string | null;
  landingPageUrl: string | null;
  audience: Audience;
  contentConfig: ContentConfig;
  criteria: Criterion[];
  ruleMatch: "ALL" | "ANY";
};

export type StrategyRequest = { sendWindow: string | null; timezone: string | null; notes: string | null };

export type CampaignSummary = {
  id: string;
  name: string;
  campaignType: string;
  status: CampaignStatus;
  startDate: string | null;
  endDate: string | null;
  channels: Channel[];
  targetLeads: number;
  approvedLeads: number;
  sent: number;
  delivered: number;
  opened: number;
  clicked: number;
  converted: number;
  bounced: number;
  unsubscribed: number;
  leadPipelineStatus: PipelineStatus;
  contentPipelineStatus: PipelineStatus;
  createdAt: string;
  updatedAt: string;
};

export type PipelineState = {
  leadStatus: PipelineStatus;
  leadDone: number;
  leadTotal: number;
  leadError: string | null;
  contentStatus: PipelineStatus;
  contentError: string | null;
};

export type Readiness = {
  evaluatedLeads: number;
  eligibleLeads: number;
  approvedLeads: number;
  eligiblePending: number;
  requiredContent: string[];
  approvedContent: string[];
  leadsReady: boolean;
  contentReady: boolean;
  canLaunch: boolean;
  blockers: string[];
};

export type AbTest = {
  enabled: boolean;
  testElement: AbElement;
  testedChannel: Channel | null;
  splitAPct: number;
  testAudiencePct: number;
  durationHours: number;
  primaryMetric: AbMetric;
  decisionRule: "MANUAL" | "HIGHEST_PRIMARY_METRIC";
  minSamplePerVariant: number;
  status: "CONFIGURED" | "RUNNING" | "AWAITING_DECISION" | "DECIDED";
  startedAt: string | null;
  endsAt: string | null;
  winnerVariant: "A" | "B" | null;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
};

export type AbElement = "EMAIL_SUBJECT" | "PREVIEW_TEXT" | "EMAIL_BODY" | "CTA" | "EMAIL_STRUCTURE";
export type AbMetric = "OPEN_RATE" | "CLICK_RATE" | "CONVERSION_RATE" | "RESPONSE_RATE";
export type AbTestRequest = Omit<
  AbTest,
  "testedChannel" | "status" | "startedAt" | "endsAt" | "winnerVariant" | "decidedBy" | "decidedAt" | "decisionNote"
>;

export type ApprovalItem = {
  id: string;
  campaignId: string;
  campaignName: string;
  campaignType: string;
  kind: "LEADS" | "CONTENT" | "CAMPAIGN";
  status: "PENDING" | "APPROVED" | "REJECTED" | "WITHDRAWN";
  submittedBy: string | null;
  submittedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  note: string | null;
  pendingCount: number;
};

export type CampaignDetail = {
  id: string;
  name: string;
  campaignType: string;
  objective: string | null;
  description: string | null;
  startDate: string | null;
  endDate: string | null;
  status: CampaignStatus;
  productName: string | null;
  productDescription: string | null;
  mainBenefits: string | null;
  offer: string | null;
  discount: string | null;
  pricingInfo: string | null;
  sellingPoints: string | null;
  callToAction: string | null;
  landingPageUrl: string | null;
  audience: Audience;
  contentConfig: ContentConfig;
  criteria: Criterion[];
  ruleMatch: "ALL" | "ANY";
  strategy: StrategyRequest;
  scheduledAt: string | null;
  pipeline: PipelineState;
  readiness: Readiness;
  abTest: AbTest | null;
  approvals: ApprovalItem[];
  createdBy: string | null;
  launchedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
  viewerIsApprover: boolean;
};

export type RuleResult = {
  field: string;
  operator: string;
  expected: string;
  actual: string | null;
  outcome: "PASS" | "FAIL" | "UNKNOWN";
  label: string;
};

export type CampaignLead = {
  id: string;
  leadId: string;
  leadName: string;
  company: string | null;
  industry: string | null;
  companySize: string | null;
  location: string | null;
  customerType: string | null;
  leadSource: string | null;
  purchaseTimeline: string | null;
  estimatedDealValue: number | null;
  productInterest: string | null;
  interestLevel: string | null;
  aiScore: number | null;
  aiEligibility: Eligibility | null;
  aiConfidence: number | null;
  positiveFactors: string[];
  negativeFactors: string[];
  aiReason: string | null;
  ruleResults: RuleResult[];
  ruleGuardApplied: boolean;
  /** EMAIL_CHECK: excluded before the AI because the lead can't be emailed (no/invalid address, unsubscribed, bounced). */
  evaluationSource: "AI_MODEL" | "RULES_ENGINE" | "EMAIL_CHECK" | null;
  modelName: string | null;
  evaluatedAt: string | null;
  approvalStatus: LeadApproval;
  overrideEligibility: Eligibility | null;
  effectiveEligibility: Eligibility | null;
  reviewNote: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
};

export type LeadAction = "APPROVE" | "REJECT" | "REMOVE" | "RESTORE";

export type ContentItem = {
  id: string;
  channel: ContentChannel;
  variant: "A" | "B";
  fields: Record<string, string>;
  status: "DRAFT" | "PENDING_REVIEW" | "APPROVED" | "REJECTED";
  provenance: Provenance;
  modelName: string | null;
  generationCount: number;
  validationWarnings: string[];
  reviewNote: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  updatedAt: string;
};

export type ActivityItem = {
  id: string;
  campaignId: string;
  campaignName: string | null;
  actorType: "AI" | "HUMAN" | "SYSTEM";
  actorName: string | null;
  action: string;
  summary: string;
  detail: Record<string, unknown> | null;
  occurredAt: string;
};

/**
 * sent = accepted by the email provider; delivered = confirmed by a provider event (null rate = not reported);
 * opened is an estimate; engagement rates divide by sent minus bounced.
 */
export type Funnel = {
  sent: number;
  delivered: number;
  bounced: number;
  opened: number;
  clicked: number;
  responded: number;
  converted: number;
  unsubscribed: number;
  failed: number;
  suppressed: number;
  deliveryRate: number | null;
  bounceRate: number | null;
  openRate: number | null;
  clickRate: number | null;
  responseRate: number | null;
  conversionRate: number | null;
  unsubscribeRate: number | null;
};

export type SeriesPoint = {
  date: string;
  sent: number;
  delivered: number;
  opened: number;
  clicked: number;
  converted: number;
  bounced: number;
  unsubscribed: number;
};

export type AnalyticsResponse = {
  from: string;
  to: string;
  funnel: Funnel;
  series: SeriesPoint[];
  variants: { variant: "A" | "B"; funnel: Funnel }[];
  queued: number;
  held: number;
  sending: number;
};

export type DashboardResponse = {
  totalCampaigns: number;
  campaignsCreatedLast30d: number;
  activeCampaigns: number;
  scheduledCampaigns: number;
  leadsReached: number;
  leadsReachedLast30d: number;
  leadsReachedPrev30d: number;
  approvalsWaiting: number;
  leadDecisionsWaiting: number;
  funnel: Funnel;
  recentActivity: ActivityItem[];
};

export type FieldMeta = {
  key: string;
  label: string;
  kind: "NUMBER" | "TEXT" | "LEVEL";
  operators: string[];
  options: string[];
  hint: string | null;
};

export type MetaResponse = {
  campaignTypes: string[];
  channels: Channel[];
  ruleFields: FieldMeta[];
  purchaseTimelines: string[];
  customerTypes: string[];
  ai: {
    model: string;
    selfHosted: boolean;
    externalProviderAllowed: boolean;
    fallbackEnabled: boolean;
    lastCallSucceeded: boolean | null;
    lastCallAt: string | null;
  };
  email: EmailDeliveryStatus;
  viewerIsApprover: boolean;
};

/** configured=false is the "configuration required" state: campaigns can't launch. */
export type EmailDeliveryStatus = {
  configured: boolean;
  provider: string;
  missing: string[];
  trackingEnabled: boolean;
  webhookEnabled: boolean;
  verifiedSenders: string[];
};

export type EmailPreview = {
  from: string;
  replyTo: string | null;
  to: string;
  subject: string | null;
  preheader: string | null;
  html: string;
  text: string;
  sampleLead: boolean;
  notes: string[];
};

export type RangeKey = "7d" | "30d" | "90d" | "custom";
export type Range = { key: RangeKey; from?: string; to?: string };
