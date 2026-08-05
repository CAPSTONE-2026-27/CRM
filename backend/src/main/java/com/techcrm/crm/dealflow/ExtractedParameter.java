package com.techcrm.crm.dealflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One business parameter the analysis model extracted, with how sure it was and
 * why.
 *
 * A row per parameter rather than a JSON blob on the analysis: these are
 * queried across deals ("every deal where budget_status was extracted below 0.5
 * confidence"), which is the entire reason for storing the confidence at all.
 */
@Entity
@Table(name = "deal_extracted_parameters")
@Getter
@Setter
public class ExtractedParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "analysis_id", nullable = false)
    private Long analysisId;

    @Column(name = "deal_id", nullable = false)
    private Long dealId;

    /** snake_case, matching the parameter names in {@link DealParameters}. */
    @Column(nullable = false, length = 60)
    private String name;

    @Column(columnDefinition = "text")
    private String value;

    /** 0-1. How confident the model was in this specific reading. */
    private Double confidence;

    @Column(columnDefinition = "text")
    private String explanation;

    /** Preserves the canonical parameter order for display, independent of
     *  whatever order the model happened to emit them in. */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;
}
