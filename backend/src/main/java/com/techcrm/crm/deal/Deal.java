package com.techcrm.crm.deal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "deals")
@Getter
@Setter
public class Deal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private BigDecimal value;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "expected_close_date")
    private OffsetDateTime expectedCloseDate;

    /** OPPORTUNITY_CREATED | MEETING_SCHEDULED | PROSPECTING | QUALIFICATION |
     *  PROPOSAL | NEGOTIATION | CLOSED_WON | CLOSED_LOST */
    @Column(nullable = false, length = 30)
    private String stage = "PROSPECTING";

    /** Human-facing reference ("OPP-000042"), derived from {@link #id}. */
    @Column(name = "opportunity_id", length = 30)
    private String opportunityId;

    /** The lead this opportunity was converted from, when it came from one. */
    @Column(name = "lead_id")
    private Long leadId;

    /* ---- Meeting scheduling (deal flow step 2) ---- */

    @Column(name = "meeting_scheduled_at")
    private OffsetDateTime meetingScheduledAt;

    /** ONLINE | ONSITE | PHONE */
    @Column(name = "meeting_mode", length = 20)
    private String meetingMode;

    @Column(name = "meeting_participants", columnDefinition = "text")
    private String meetingParticipants;

    /* ---- Newest prediction, denormalised from deal_predictions so the
     * pipeline board can sort and colour cards without a subquery per row. ---- */

    @Column(name = "win_probability")
    private Double winProbability;

    @Column(name = "risk_level", length = 20)
    private String riskLevel;

    /* ---- Final decision (deal flow step 13) ---- */

    @Column(name = "closing_reason", columnDefinition = "text")
    private String closingReason;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /** 0-100 */
    private Integer probability;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "forecast_category")
    private String forecastCategory;

    @Column(name = "weighted_forecast_value")
    private BigDecimal weightedForecastValue;

    @Column(name = "best_case_value")
    private BigDecimal bestCaseValue;

    @Column(name = "auto_generate_proposal", nullable = false)
    private boolean autoGenerateProposal = false;

    @Column(name = "push_to_erp_on_close", nullable = false)
    private boolean pushToErpOnClose = false;

    /* ---- Deal-scoring model inputs (see V14) ----------------------------
     * The rep's assessment of this deal. Values are stored as the labels the
     * model was trained on; encoding is the model pipeline's job, not ours. */

    @Column(name = "total_meetings")
    private Integer totalMeetings;

    @Column(name = "lead_score")
    private Double leadScore;

    @Column(name = "customer_sentiment", length = 40)
    private String customerSentiment;

    @Column(name = "buying_intent", length = 40)
    private String buyingIntent;

    /** Numeric 0-10, not a category — the model treats it as continuous. */
    @Column(name = "relationship_strength")
    private Double relationshipStrength;

    @Column(name = "budget_status", length = 40)
    private String budgetStatus;

    @Column(name = "decision_maker_involvement", length = 40)
    private String decisionMakerInvolvement;

    @Column(name = "customer_urgency", length = 40)
    private String customerUrgency;

    /** Semicolon-separated labels, or "No Objections". */
    @Column(name = "main_objections", columnDefinition = "text")
    private String mainObjections;

    @Column(name = "product_interest_level", length = 40)
    private String productInterestLevel;

    @Column(name = "meeting_outcome", length = 60)
    private String meetingOutcome;

    @Column(name = "customer_requirements", length = 80)
    private String customerRequirements;

    @Column(name = "risk_factors", length = 80)
    private String riskFactors;

    @Column(name = "competitor_mention", length = 20)
    private String competitorMention;

    @Column(name = "engagement_score")
    private Double engagementScore;

    @Column(name = "implementation_readiness", length = 40)
    private String implementationReadiness;

    @Column(name = "upsell_opportunity", length = 20)
    private String upsellOpportunity;

    /* ---- Model output ---- */

    @Column(name = "deal_score")
    private Double dealScore;

    @Column(name = "deal_score_band", length = 20)
    private String dealScoreBand;

    @Column(name = "deal_score_action", length = 200)
    private String dealScoreAction;

    /** Which model produced the score, so it stays explainable after retraining. */
    @Column(name = "deal_score_model_version", length = 40)
    private String dealScoreModelVersion;

    @Column(name = "deal_scored_at")
    private OffsetDateTime dealScoredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
