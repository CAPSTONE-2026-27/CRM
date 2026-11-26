package com.techcrm.crm.marketing.content;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One piece of campaign content for one channel and one A/B variant. The
 * fields differ per channel (an email has a subject, an SMS does not), so they
 * are stored as a JSON object whose keys are fixed by {@link ContentChannel}.
 */
@Entity
@Table(name = "mkt_content_variants")
@Getter
@Setter
public class ContentVariant {

    public enum Status { DRAFT, PENDING_REVIEW, APPROVED, REJECTED }

    /** Where the current text came from. Shown on every piece of content. */
    public enum Provenance { AI_GENERATED, HUMAN_EDITED, SYSTEM_GENERATED, HUMAN_AUTHORED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ContentChannel channel;

    @Column(nullable = false, length = 1, updatable = false)
    private String variant = "A";

    @Column(name = "fields_json", nullable = false, columnDefinition = "text")
    private String fieldsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING_REVIEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provenance provenance;

    @Column(name = "model_name", length = 120)
    private String modelName;

    /** The user prompt of the model call this variant came from; null when no call was made. Retraining input. */
    @Column(name = "ai_input", columnDefinition = "text")
    private String aiInput;

    @Column(name = "generation_count", nullable = false)
    private Integer generationCount = 1;

    @Column(name = "validation_warnings", nullable = false, columnDefinition = "text")
    private String validationWarnings = "[]";

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
