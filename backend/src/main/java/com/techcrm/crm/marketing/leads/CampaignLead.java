package com.techcrm.crm.marketing.leads;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One lead's evaluation for one campaign. The ai* fields are exactly what the
 * evaluator produced and are never edited by a person; the Marketing Head's
 * decision lives in approvalStatus / overrideEligibility / reviewNote, so the
 * two can always be compared.
 */
@Entity
@Table(name = "mkt_campaign_leads")
@Getter
@Setter
public class CampaignLead {

    public enum Eligibility { ELIGIBLE, NOT_ELIGIBLE }

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED, REMOVED }

    /** AI_MODEL: the fine-tuned LLM. RULES_ENGINE: the deterministic fallback
     *  used when the model is unavailable or its output failed validation. */
    /** EMAIL_CHECK: excluded before the model because the lead cannot be emailed. */
    public enum Source { AI_MODEL, RULES_ENGINE, EMAIL_CHECK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "lead_id", nullable = false, updatable = false)
    private Long leadId;

    @Column(name = "ai_score")
    private Integer aiScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_eligibility", length = 20)
    private Eligibility aiEligibility;

    @Column(name = "ai_confidence")
    private Double aiConfidence;

    @Column(name = "positive_factors", nullable = false, columnDefinition = "text")
    private String positiveFactors = "[]";

    @Column(name = "negative_factors", nullable = false, columnDefinition = "text")
    private String negativeFactors = "[]";

    @Column(name = "ai_reason", columnDefinition = "text")
    private String aiReason;

    @Column(name = "rule_results", nullable = false, columnDefinition = "text")
    private String ruleResults = "[]";

    @Column(name = "rule_guard_applied", nullable = false)
    private Boolean ruleGuardApplied = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_source", length = 20)
    private Source evaluationSource;

    @Column(name = "model_name", length = 120)
    private String modelName;

    /** The user prompt the model was sent; null unless evaluationSource is AI_MODEL. Retraining input. */
    @Column(name = "ai_input", columnDefinition = "text")
    private String aiInput;

    @Column(name = "evaluated_at")
    private OffsetDateTime evaluatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "override_eligibility", length = 20)
    private Eligibility overrideEligibility;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    /** The eligibility that counts: a human override wins over the AI. */
    public Eligibility effectiveEligibility() {
        return overrideEligibility != null ? overrideEligibility : aiEligibility;
    }
}
