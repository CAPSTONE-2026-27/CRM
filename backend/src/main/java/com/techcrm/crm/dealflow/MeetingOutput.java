package com.techcrm.crm.dealflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Deal flow step 4 — the structured record of a customer meeting.
 *
 * Append-only and versioned per deal. A second meeting never edits the first:
 * the whole value of this table is the progression it preserves, and a deal
 * whose score moved from 40 to 75 is only interesting if both meetings survive
 * to explain why.
 */
@Entity
@Table(name = "deal_meeting_outputs")
@Getter
@Setter
public class MeetingOutput {

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

    /** 1-based, per deal. */
    @Column(nullable = false)
    private Integer version;

    @Column(name = "meeting_date", nullable = false)
    private LocalDate meetingDate;

    /** "HH:mm" as the executive entered it — wall-clock, deliberately not an
     *  instant, since a meeting time has no useful timezone. */
    @Column(name = "meeting_time", nullable = false, length = 5)
    private String meetingTime;

    /** ONLINE | ONSITE | PHONE | OTHER */
    @Column(name = "meeting_type", length = 30)
    private String meetingType;

    @Column(columnDefinition = "text")
    private String participants;

    /* ---- Long-form capture. All text, no length caps: the analysis model
     * reasons from the detail, and a truncated write-up scores worse. ---- */

    @Column(name = "meeting_summary", columnDefinition = "text")
    private String meetingSummary;

    @Column(name = "customer_requirements", columnDefinition = "text")
    private String customerRequirements;

    @Column(name = "key_discussion_points", columnDefinition = "text")
    private String keyDiscussionPoints;

    @Column(name = "customer_questions", columnDefinition = "text")
    private String customerQuestions;

    @Column(name = "competitor_mentioned", columnDefinition = "text")
    private String competitorMentioned;

    @Column(columnDefinition = "text")
    private String objections;

    @Column(name = "budget_discussion", columnDefinition = "text")
    private String budgetDiscussion;

    @Column(columnDefinition = "text")
    private String timeline;

    @Column(name = "next_steps", columnDefinition = "text")
    private String nextSteps;

    @Column(name = "executive_remarks", columnDefinition = "text")
    private String executiveRemarks;

    @Column(name = "submitted_by_id")
    private Long submittedById;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
