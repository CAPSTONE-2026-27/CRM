package com.techcrm.crm.lead.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One attempt to validate a lead before it is created.
 *
 * The row exists because the answer takes seconds to arrive: the request that
 * starts a validation cannot also return its result, so the browser polls this
 * record instead. See V28 for why this is a table rather than a cache.
 */
@Entity
@Table(name = "lead_validation_runs")
@Getter
@Setter
public class LeadValidationRun {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "started_by")
    private Long startedBy;

    @Column(nullable = false, length = 20)
    private String state = ValidationState.PENDING.name();

    @Column(nullable = false, length = 40)
    private String step = ValidationStep.STARTING.name();

    /** The automation platform's own instance id, so a run here can be traced
     *  to a run in its monitoring view. Null until the process has started. */
    @Column(name = "instance_id", length = 100)
    private String instanceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submitted_json", columnDefinition = "jsonb")
    private Map<String, Object> submitted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verdict_json", columnDefinition = "jsonb")
    private Map<String, Object> verdict;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** True once the run has an answer of any kind — a verdict, a timeout or a
     *  failure. The browser stops polling on this. */
    public boolean isFinished() {
        return ValidationState.COMPLETED.name().equals(state)
                || ValidationState.TIMED_OUT.name().equals(state)
                || ValidationState.FAILED.name().equals(state);
    }
}
