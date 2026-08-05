package com.techcrm.crm.dealflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/** Deal flow step 8 — what the XGBoost model made of one feature set. */
@Entity
@Table(name = "deal_predictions")
@Getter
@Setter
public class DealPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "deal_id", nullable = false)
    private Long dealId;

    @Column(name = "opportunity_id", length = 30)
    private String opportunityId;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "feature_set_id")
    private Long featureSetId;

    /** 0-100, straight from the regressor. */
    @Column(name = "deal_score", nullable = false)
    private Double dealScore;

    /** 0-100. A calibration of the score, not an independently trained
     *  classifier — see DealPredictionService for what that means. */
    @Column(name = "win_probability")
    private Double winProbability;

    /** HIGH | MEDIUM | LOW | VERY LOW */
    @Column(length = 20)
    private String band;

    /** LOW | MEDIUM | HIGH — inverse of the score, plus explicit risk signals. */
    @Column(name = "risk_level", length = 20)
    private String riskLevel;

    /** 0-1 mean extraction confidence. How much evidence the score rests on,
     *  which is a different question from how high the score is. */
    private Double confidence;

    @Column(name = "recommended_action", length = 200)
    private String recommendedAction;

    /* text[] rather than jsonb — see FeatureSet.imputedFields for why mapping a
     * List<String> as JSON is not a local decision. */

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "positive_factors", nullable = false, columnDefinition = "text[]")
    private List<String> positiveFactors = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "negative_factors", nullable = false, columnDefinition = "text[]")
    private List<String> negativeFactors = List.of();

    @Column(name = "model_version", length = 40)
    private String modelVersion;

    @CreationTimestamp
    @Column(name = "predicted_at", nullable = false, updatable = false)
    private OffsetDateTime predictedAt;
}
