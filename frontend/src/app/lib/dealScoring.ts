/**
 * Input vocabulary for the XGBoost deal-scoring model.
 *
 * These lists mirror the trained bundle exactly. The service scores in strict
 * mode: a value it wasn't trained on is rejected with a 400 rather than being
 * quietly treated as the median, so anything added here that the model doesn't
 * know will fail at scoring time, not silently skew the result.
 *
 * The model also exposes GET /schema, which returns these same lists read from
 * the bundle — worth checking against after any retrain.
 */

export const CUSTOMER_SENTIMENT = ["Negative", "Neutral", "Positive"] as const;
export const BUYING_INTENT = ["Low", "Medium", "High"] as const;
export const BUDGET_STATUS = ["Not Allocated", "Under Review", "Partially Approved", "Fully Approved"] as const;
export const DECISION_MAKER_INVOLVEMENT = ["No", "Indirect", "Yes"] as const;
export const CUSTOMER_URGENCY = ["Low", "Medium", "High", "Critical"] as const;
export const PRODUCT_INTEREST_LEVEL = ["Low", "Medium", "High", "Very High"] as const;
export const MEETING_OUTCOME = [
  "No Show / Cancelled",
  "Rescheduled",
  "Discussed Requirements",
  "Proposal Sent",
  "Verbal Agreement",
] as const;
export const COMPETITOR_MENTION = ["No", "Yes"] as const;
export const IMPLEMENTATION_READINESS = ["Not Ready", "Partially Ready", "Ready", "Fully Ready"] as const;
export const UPSELL_OPPORTUNITY = ["No", "Yes"] as const;

export const CUSTOMER_REQUIREMENTS = [
  "API / Technical Integration",
  "Basic Feature Set",
  "Compliance-driven Requirements",
  "Customized Integration",
  "Enterprise-grade Security",
  "Multi-department Rollout",
  "Scalable Infrastructure",
  "Standard Package",
] as const;

export const RISK_FACTORS = [
  "No Risk Identified",
  "Budget Constraints",
  "Competitor Pressure",
  "Economic Uncertainty",
  "Internal Politics",
  "Stakeholder Turnover",
  "Technical Concerns",
  "Timeline Conflict",
] as const;

/** Multi-select. Sent as a semicolon-separated string; empty means no objections. */
export const OBJECTION_TOKENS = [
  "Budget Not Allocated",
  "Competitor Preference",
  "Lack of Internal Buy-in",
  "Long Implementation Time",
  "Missing Features",
  "No Urgent Business Need",
  "Poor Past Experience / Support",
  "Price Too High",
  "Security / Compliance Concerns",
  "Unfavorable Contract Terms",
] as const;

export const NO_OBJECTIONS = "No Objections";

export type DealScoringInput = {
  totalMeetings: string;
  leadScore: string;
  customerSentiment: string;
  buyingIntent: string;
  relationshipStrength: string;
  budgetStatus: string;
  decisionMakerInvolvement: string;
  customerUrgency: string;
  mainObjections: string[];
  productInterestLevel: string;
  meetingOutcome: string;
  customerRequirements: string;
  riskFactors: string;
  competitorMention: string;
  engagementScore: string;
  implementationReadiness: string;
  upsellOpportunity: string;
};

/** Sensible mid-range starting point, so a rep adjusts rather than fills 17 blanks. */
export const emptyDealScoringInput = (): DealScoringInput => ({
  totalMeetings: "1",
  leadScore: "50",
  customerSentiment: "Neutral",
  buyingIntent: "Medium",
  relationshipStrength: "5",
  budgetStatus: "Under Review",
  decisionMakerInvolvement: "Indirect",
  customerUrgency: "Medium",
  mainObjections: [],
  productInterestLevel: "Medium",
  meetingOutcome: "Discussed Requirements",
  customerRequirements: "Standard Package",
  riskFactors: "No Risk Identified",
  competitorMention: "No",
  engagementScore: "50",
  implementationReadiness: "Partially Ready",
  upsellOpportunity: "No",
});

const num = (value: string, fallback = 0): number => {
  const parsed = parseFloat(String(value).replace(/[^0-9.\-]/g, ""));
  return Number.isFinite(parsed) ? parsed : fallback;
};

/** Maps the form's shape onto the API payload. */
export function toDealScoringPayload(input: DealScoringInput) {
  return {
    totalMeetings: Math.round(num(input.totalMeetings)),
    leadScore: num(input.leadScore),
    customerSentiment: input.customerSentiment,
    buyingIntent: input.buyingIntent,
    relationshipStrength: num(input.relationshipStrength),
    budgetStatus: input.budgetStatus,
    decisionMakerInvolvement: input.decisionMakerInvolvement,
    customerUrgency: input.customerUrgency,
    // The model expects the literal "No Objections" for none, not an empty string.
    mainObjections: input.mainObjections.length ? input.mainObjections.join("; ") : NO_OBJECTIONS,
    productInterestLevel: input.productInterestLevel,
    meetingOutcome: input.meetingOutcome,
    customerRequirements: input.customerRequirements,
    riskFactors: input.riskFactors,
    competitorMention: input.competitorMention,
    engagementScore: num(input.engagementScore),
    implementationReadiness: input.implementationReadiness,
    upsellOpportunity: input.upsellOpportunity,
  };
}

export function scoreBandVariant(band: string | null | undefined): "green" | "blue" | "amber" | "red" {
  switch (band) {
    case "HIGH":
      return "green";
    case "MEDIUM":
      return "blue";
    case "LOW":
      return "amber";
    default:
      return "red";
  }
}
