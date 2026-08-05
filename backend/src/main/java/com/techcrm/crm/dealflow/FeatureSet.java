package com.techcrm.crm.dealflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deal flow step 7 — the bridge between the language model and the numeric one.
 *
 * Two representations of the same reading are stored because they answer
 * different questions:
 *
 *   features     the engineered numeric vector ("Positive" -> 0.92). This is
 *                what a human reviews and what a future model could train on.
 *   modelInputs  the same information as the categorical labels the current
 *                XGBoost bundle expects. This is what was actually sent.
 *
 * Keeping only the numbers would make a disputed score impossible to reproduce;
 * keeping only the labels would throw away the engineering step entirely.
 */
@Entity
@Table(name = "deal_feature_sets")
@Getter
@Setter
public class FeatureSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "deal_id", nullable = false)
    private Long dealId;

    @Column(name = "analysis_id", nullable = false)
    private Long analysisId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Double> features = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_inputs", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> modelInputs = new LinkedHashMap<>();

    /**
     * Parameters the model could not read, where a default had to stand in.
     * A score built on five imputed fields deserves less trust than one built on
     * none, and this is what makes that visible.
     *
     * text[] rather than jsonb: mapping a List&lt;String&gt; as JSON changes the
     * recommended JDBC type for List&lt;String&gt; process-wide, which breaks
     * users.permissions — a genuine Postgres array — on schema validation.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "imputed_fields", nullable = false, columnDefinition = "text[]")
    private List<String> imputedFields = List.of();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
