package com.techcrm.crm.meeting;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One customer meeting logged against a lead. Rows are append-only: a later
 *  meeting never overwrites an earlier one, so the lead keeps a full history
 *  of how and why its score moved. */
@Entity
@Table(name = "lead_meetings")
@Getter
@Setter
public class LeadMeeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "recorded_by_id")
    private Long recordedById;

    @Column(name = "meeting_date", nullable = false)
    private LocalDate meetingDate;

    /** "HH:mm" as the rep entered it — local wall-clock time, deliberately not
     *  converted to an instant, since a meeting time has no useful timezone. */
    @Column(name = "meeting_time", nullable = false, length = 5)
    private String meetingTime;

    @Column(name = "meeting_output", nullable = false, columnDefinition = "text")
    private String meetingOutput;

    /** AI-generated, reviewed and optionally edited by the rep before saving. */
    @Column(name = "ai_summary", nullable = false, columnDefinition = "text")
    private String aiSummary;

    @Column(name = "previous_score")
    private Integer previousScore;

    @Column(name = "updated_score")
    private Integer updatedScore;

    @Column(name = "score_change_reason", columnDefinition = "text")
    private String scoreChangeReason;

    @Column(name = "ai_model_version")
    private String aiModelVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
