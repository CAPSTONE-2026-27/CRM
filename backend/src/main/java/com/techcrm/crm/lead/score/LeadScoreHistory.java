package com.techcrm.crm.lead.score;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One movement in a lead's score: its initial value, then one row per
 * qualification meeting.
 *
 * Append-only, and written only by {@link LeadScoreFluctuationEngine}. No code
 * path takes a score from a request body — that is the whole point of the
 * redesign, and keeping the writes in one place is what enforces it rather than
 * merely documenting it.
 *
 * The lead's live score is still denormalised onto `leads` so existing list and
 * filter queries keep working. This table is the record of how it got there.
 */
@Entity
@Table(name = "lead_score_history")
@Getter
@Setter
public class LeadScoreHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    /** Null on the initial row, which precedes any meeting. */
    @Column(name = "meeting_id")
    private Long meetingId;

    @Column(name = "analysis_id")
    private Long analysisId;

    /**
     * Position in the sequence, so the timeline can label a row "Meeting 3"
     * without counting rows in the query — and without depending on created_at
     * ordering, which breaks when two meetings are logged on the same day.
     */
    @Column(name = "meeting_number")
    private Integer meetingNumber;

    /** The 0-100 score for this meeting alone, from the rule table. */
    @Column(name = "meeting_score")
    private Integer meetingScore;

    /** The band meetingScore falls into. */
    @Column(length = 20)
    private String priority;

    /** Null on the first row: there is no previous score to move from. */
    @Column(name = "previous_score")
    private Integer previousScore;

    @Column(name = "updated_score", nullable = false)
    private Integer updatedScore;

    /**
     * Stored rather than derived on read.
     *
     * It appears on every row of the timeline, and computing it would mean a
     * window function over a table that is only ever appended to. It also
     * records what actually happened after clamping, which a subtraction of the
     * two stored scores would reproduce but a recomputation from the rule table
     * would not.
     */
    @Column(name = "score_difference", nullable = false)
    private Integer scoreDifference;

    @Column(name = "qualification_probability")
    private Double qualificationProbability;

    /**
     * Per-parameter contributions as JSON:
     * [{"parameter":"buying_intent","value":"High","points":30,"maxPoints":30}, ...]
     *
     * This is what makes a movement explainable rather than merely visible. The
     * manager sees not just "-6" but which signal cost it, which is the half
     * they can act on.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason_json", columnDefinition = "jsonb")
    private List<LeadScoreFluctuationEngine.Contribution> contributions = List.of();

    @Column(name = "change_reason", columnDefinition = "text")
    private String changeReason;

    @Column(name = "meeting_summary", columnDefinition = "text")
    private String meetingSummary;

    /** INITIAL, ENGINE or MANUAL. No rep-facing path writes MANUAL; the value
     *  exists so a future correction feature stays distinguishable from engine
     *  output rather than indistinguishable from it. */
    @Column(nullable = false, length = 20)
    private String source = "ENGINE";

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "meeting_date")
    private LocalDate meetingDate;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
