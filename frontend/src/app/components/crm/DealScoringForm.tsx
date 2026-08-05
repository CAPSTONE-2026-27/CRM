import { colors } from "../../tokens";
import { Field, AIInsightBox } from "./ui";
import {
  BUDGET_STATUS,
  BUYING_INTENT,
  COMPETITOR_MENTION,
  CUSTOMER_REQUIREMENTS,
  CUSTOMER_SENTIMENT,
  CUSTOMER_URGENCY,
  DECISION_MAKER_INVOLVEMENT,
  IMPLEMENTATION_READINESS,
  MEETING_OUTCOME,
  OBJECTION_TOKENS,
  PRODUCT_INTEREST_LEVEL,
  RISK_FACTORS,
  UPSELL_OPPORTUNITY,
  type DealScoringInput,
} from "../../lib/dealScoring";

/**
 * The 17 inputs the deal-scoring model was trained on.
 *
 * Every dropdown is populated from lib/dealScoring, which mirrors the trained
 * bundle. The service scores in strict mode, so a free-text field here would
 * mean a rejected score the moment someone typed a variant the model had never
 * seen — hence selects, not text inputs, for every categorical.
 */
export function DealScoringForm({
  value,
  onChange,
}: {
  value: DealScoringInput;
  onChange: (next: DealScoringInput) => void;
}) {
  const set = <K extends keyof DealScoringInput>(key: K) => (v: DealScoringInput[K]) =>
    onChange({ ...value, [key]: v });

  const toggleObjection = (token: string) => {
    const next = value.mainObjections.includes(token)
      ? value.mainObjections.filter((t) => t !== token)
      : [...value.mainObjections, token];
    onChange({ ...value, mainObjections: next });
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <AIInsightBox text="These signals feed the deal-scoring model. It weighs budget, decision-maker involvement, engagement and objections to predict how likely this deal is to close." />

      <Section title="Engagement">
        <Grid>
          <Field label="Total meetings" type="number" value={value.totalMeetings} onChange={set("totalMeetings")} />
          <Field label="Lead score (0-100)" type="number" value={value.leadScore} onChange={set("leadScore")} />
          <Field label="Engagement score (0-100)" type="number" value={value.engagementScore} onChange={set("engagementScore")} />
          <Field
            label="Relationship strength (0-10)"
            type="number"
            value={value.relationshipStrength}
            onChange={set("relationshipStrength")}
          />
        </Grid>
      </Section>

      <Section title="Customer signals">
        <Grid>
          <Field label="Customer sentiment" type="select" value={value.customerSentiment} onChange={set("customerSentiment")} options={[...CUSTOMER_SENTIMENT]} />
          <Field label="Buying intent" type="select" value={value.buyingIntent} onChange={set("buyingIntent")} options={[...BUYING_INTENT]} />
          <Field label="Customer urgency" type="select" value={value.customerUrgency} onChange={set("customerUrgency")} options={[...CUSTOMER_URGENCY]} />
          <Field label="Product interest level" type="select" value={value.productInterestLevel} onChange={set("productInterestLevel")} options={[...PRODUCT_INTEREST_LEVEL]} />
        </Grid>
      </Section>

      <Section title="Deal readiness">
        <Grid>
          <Field label="Budget status" type="select" value={value.budgetStatus} onChange={set("budgetStatus")} options={[...BUDGET_STATUS]} />
          <Field label="Decision maker involvement" type="select" value={value.decisionMakerInvolvement} onChange={set("decisionMakerInvolvement")} options={[...DECISION_MAKER_INVOLVEMENT]} />
          <Field label="Implementation readiness" type="select" value={value.implementationReadiness} onChange={set("implementationReadiness")} options={[...IMPLEMENTATION_READINESS]} />
          <Field label="Latest meeting outcome" type="select" value={value.meetingOutcome} onChange={set("meetingOutcome")} options={[...MEETING_OUTCOME]} />
        </Grid>
      </Section>

      <Section title="Requirements & risk">
        <Grid>
          <Field label="Customer requirements" type="select" value={value.customerRequirements} onChange={set("customerRequirements")} options={[...CUSTOMER_REQUIREMENTS]} />
          <Field label="Risk factors" type="select" value={value.riskFactors} onChange={set("riskFactors")} options={[...RISK_FACTORS]} />
          <Field label="Competitor mentioned" type="select" value={value.competitorMention} onChange={set("competitorMention")} options={[...COMPETITOR_MENTION]} />
          <Field label="Upsell opportunity" type="select" value={value.upsellOpportunity} onChange={set("upsellOpportunity")} options={[...UPSELL_OPPORTUNITY]} />
        </Grid>
      </Section>

      <Section title="Main objections">
        <div style={{ fontSize: 11, color: colors.textSecondary, marginBottom: 8 }}>
          Select any the customer has raised. Leave all unticked if there are none.
        </div>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
          {OBJECTION_TOKENS.map((token) => {
            const active = value.mainObjections.includes(token);
            return (
              <button
                key={token}
                type="button"
                onClick={() => toggleObjection(token)}
                aria-pressed={active}
                style={{
                  border: `1px solid ${active ? colors.primary : colors.border}`,
                  background: active ? colors.primaryLight : "#FFFFFF",
                  color: active ? colors.primary : colors.textPrimary,
                  borderRadius: 20,
                  padding: "5px 12px",
                  fontSize: 12,
                  cursor: "pointer",
                  fontFamily: "inherit",
                }}
              >
                {token}
              </button>
            );
          })}
        </div>
      </Section>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div
        style={{
          fontSize: 11,
          fontWeight: 600,
          color: colors.textSecondary,
          textTransform: "uppercase",
          letterSpacing: 0.4,
          marginBottom: 8,
        }}
      >
        {title}
      </div>
      {children}
    </div>
  );
}

function Grid({ children }: { children: React.ReactNode }) {
  return <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>{children}</div>;
}
