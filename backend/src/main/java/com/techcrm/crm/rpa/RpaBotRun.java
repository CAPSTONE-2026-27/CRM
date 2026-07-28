package com.techcrm.crm.rpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "rpa_bot_runs")
@Getter
@Setter
public class RpaBotRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "bot_id", nullable = false)
    private Long botId;

    @Column(name = "workflow_definition_id")
    private Long workflowDefinitionId;

    /** RUNNING | SCHEDULED | SUCCESS | ERROR */
    @Column(nullable = false, length = 20)
    private String status = "SCHEDULED";

    @Column(name = "tasks_completed", nullable = false)
    private Integer tasksCompleted = 0;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(columnDefinition = "text")
    private String logs;

    /** "event" | "schedule" | "manual" */
    @Column(name = "triggered_by", length = 20)
    private String triggeredBy;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "ai_model_version")
    private String aiModelVersion;

    @Column(name = "ai_confidence")
    private Double aiConfidence;
}
