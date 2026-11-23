// Mirrors com.techcrm.crm.analytics.AnalyticsDtos. Money arrives as a JSON
// number (BigDecimal); rates are 0–100 percentages or null when there was
// nothing to divide by.

export type Section = "OVERVIEW" | "LEADS" | "DEALS" | "TEAM" | "AI" | "SUPPORT" | "CAMPAIGNS";

export type Window = {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  granularity: "day" | "week" | "month";
  zone: string;
};

export type KpiFormat = "count" | "currency" | "percent" | "score" | "hours";

export type Kpi = {
  key: string;
  label: string;
  format: KpiFormat;
  value: number | null;
  previous: number | null;
  hint: string | null;
};

export type CountRow = { key: string; count: number };
export type ValueRow = { key: string; count: number; value: number };

export type AnalyticsFilters = {
  from: string;
  to: string;
  ownerId?: string;
  source?: string;
  stage?: string;
};

export type MetaResponse = {
  sections: Section[];
  scopedToSelf: boolean;
  owners: { value: string; label: string }[];
  sources: string[];
  stages: string[];
  zone: string;
  currency: string;
};

export type OverviewResponse = { window: Window; scopedToSelf: boolean; kpis: Kpi[] };

export type LeadsResponse = {
  window: Window;
  total: number;
  averageScore: number | null;
  funnel: { key: string; label: string; count: number }[];
  byStatus: CountRow[];
  byQualification: CountRow[];
  scoreTiers: CountRow[];
  bySource: { source: string; leads: number; qualified: number; converted: number; conversionRate: number | null }[];
  trend: { bucket: string; created: number; qualified: number; converted: number }[];
};

export type AtRiskDeal = {
  id: string;
  opportunityId: string | null;
  name: string;
  accountName: string | null;
  ownerName: string | null;
  stage: string;
  value: number;
  currency: string;
  dealScore: number | null;
  winProbability: number | null;
  riskLevel: string | null;
  reason: string;
  expectedCloseDate: string | null;
};

export type DealsResponse = {
  window: Window;
  byStage: ValueRow[];
  wonCount: number;
  wonValue: number;
  lostCount: number;
  lostValue: number;
  winRate: number | null;
  avgWonDealSize: number | null;
  wonLostTrend: { bucket: string; won: number; lost: number; wonValue: number }[];
  scoreDistribution: CountRow[];
  winProbabilityDistribution: CountRow[];
  openByRisk: CountRow[];
  scoreTrend: { bucket: string; predictions: number; avgDealScore: number | null; avgWinProbability: number | null }[];
  atRisk: AtRiskDeal[];
};

export type RepRow = {
  userId: string;
  name: string;
  role: string;
  leadsAssigned: number;
  leadsConverted: number;
  conversionRate: number | null;
  dealsWon: number;
  dealsLost: number;
  revenueWon: number;
  winRate: number | null;
  meetings: number;
  openPipeline: number;
};

export type TeamResponse = { window: Window; reps: RepRow[] };

export type AiResponse = {
  window: Window;
  leadMeetings: number;
  dealMeetings: number;
  meetingsTrend: { bucket: string; leadMeetings: number; dealMeetings: number }[];
  leadsCreated: number;
  leadsAiScored: number;
  leadAnalysesBySource: CountRow[];
  dealAnalysesByStatus: CountRow[];
  dealPredictions: number;
  avgDealAnalysisLatencyMs: number | null;
  aiUsageTrend: { bucket: string; leadAnalyses: number; dealAnalyses: number; dealPredictions: number }[];
  reviewDecisions: CountRow[];
  reviewsByRisk: { riskLevel: string; approved: number; rejected: number; overridden: number }[];
  overrideRate: number | null;
};

export type CampaignsResponse = {
  window: Window;
  totalCampaigns: number;
  byStatus: CountRow[];
  funnel: {
    sent: number;
    delivered: number;
    opened: number;
    clicked: number;
    responded: number;
    converted: number;
    failed: number;
    openRate: number | null;
    clickRate: number | null;
    conversionRate: number | null;
  };
  trend: { bucket: string; sent: number; opened: number; clicked: number; converted: number }[];
  campaigns: {
    id: string;
    name: string;
    type: string;
    status: string;
    sent: number;
    delivered: number;
    opened: number;
    clicked: number;
    converted: number;
    openRate: number | null;
    clickRate: number | null;
    conversionRate: number | null;
  }[];
};
