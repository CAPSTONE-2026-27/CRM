package com.techcrm.crm.lead.score;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * What the fine-tuned model read out of one qualification meeting.
 *
 * Rows are append-only, one per meeting. The five signal columns are the engine's
 * entire input; everything else on this entity is narrative for the rep, or
 * provenance for whoever has to explain a score later.
 *
 * Stored rather than derived on demand because a score is only defensible if the
 * reading behind it survives. Re-running the model months later would give a
 * different answer — a newer adapter, a different sampling seed — and the
 * question "why was this lead scored 74 in March" would become unanswerable.
 */
@Entity
@Table(name = "lead_ai_analysis")
@Getter
@Setter
public class LeadAiAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    /** Null while this is a preview the rep has not saved yet. */
    @Column(name = "meeting_id")
    private Long meetingId;

    /* ---- The five signals the engine scores on. Vocabulary enforced by the
     *      lead_ai_analysis_vocabulary CHECK constraint in V18. ---- */

    @Column(name = "customer_sentiment", nullable = false, length = 20)
    private String customerSentiment;

    @Column(name = "buying_intent", nullable = false, length = 20)
    private String buyingIntent;

    @Column(name = "decision_maker_involvement", nullable = false, length = 20)
    private String decisionMakerInvolvement;

    @Column(name = "customer_urgency", nullable = false, length = 20)
    private String customerUrgency;

    @Column(name = "product_interest_level", nullable = false, length = 20)
    private String productInterestLevel;

    /* ---- Narrative. Read by humans, never by the engine. ---- */

    @Column(name = "meeting_summary", columnDefinition = "text")
    private String meetingSummary;

    @Column(columnDefinition = "text")
    private String objections;

    @Column(name = "key_points", columnDefinition = "text")
    private String keyPoints;

    @Column(name = "recommended_next_action", columnDefinition = "text")
    private String recommendedNextAction;

    /** The model's confidence in its own reading, 0-1. Distinct from
     *  qualificationProbability: one says "how sure am I of these five values",
     *  the other "how likely is this lead worth pursuing". */
    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "qualification_probability")
    private Double qualificationProbability;

    /** The reply verbatim. The only way to diagnose a bad reading after the
     *  fact — the parsed columns show what was understood, not what was said. */
    @Column(name = "raw_response", columnDefinition = "text")
    private String rawResponse;

    /** Which adapter produced this. Without it, comparing two months of history
     *  silently compares two different models. */
    @Column(name = "model_version", length = 100)
    private String modelVersion;

    /**
     * MODEL, REPAIRED or HEURISTIC.
     *
     * REPAIRED means at least one value had to be snapped onto the vocabulary,
     * HEURISTIC that the model was unreachable and the rule-based fallback ran.
     * A weak reading and a confident one produce equally confident-looking
     * scores, and this column is the only thing that tells them apart.
     */
    @Column(nullable = false, length = 20)
    private String source = "MODEL";

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** The five signals as the engine wants them. */
    @Transient
    public LeadSignals toSignals() {
        return new LeadSignals(customerSentiment, buyingIntent,
                decisionMakerInvolvement, customerUrgency, productInterestLevel);
    }

    public void applySignals(LeadSignals signals) {
        this.customerSentiment = signals.customerSentiment();
        this.buyingIntent = signals.buyingIntent();
        this.decisionMakerInvolvement = signals.decisionMakerInvolvement();
        this.customerUrgency = signals.customerUrgency();
        this.productInterestLevel = signals.productInterestLevel();
    }
}
