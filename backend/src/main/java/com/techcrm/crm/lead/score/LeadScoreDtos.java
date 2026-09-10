package com.techcrm.crm.lead.score;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Wire shapes for the score timeline, the AI reading, and qualification. */
public final class LeadScoreDtos {

    private LeadScoreDtos() {
    }

    /**
     * One row of the manager's timeline.
     *
     * Carries the per-parameter breakdown as a typed list rather than the raw
     * JSON string stored in the column, so the UI never has to parse it and
     * cannot disagree with the backend about its shape.
     */
    public record ScoreTimelineEntry(
            Long id,
            Integer meetingNumber,
            LocalDate meetingDate,
            Integer meetingScore,
            String priority,
            Integer previousScore,
            Integer updatedScore,
            Integer scoreDifference,
            Double qualificationProbability,
            String changeReason,
            String source,
            List<ContributionView> contributions,
            OffsetDateTime createdAt
    ) {
    }

    /** One parameter's contribution: what it was read as, and what it earned. */
    public record ContributionView(String parameter, String value, Integer points, Integer maxPoints) {
    }

    /** The five signals behind the most recent movement, plus provenance. */
    public record AiInsightView(
            String customerSentiment,
            String buyingIntent,
            String decisionMakerInvolvement,
            String customerUrgency,
            String productInterestLevel,
            Double qualificationProbability,
            String source,
            String modelVersion,
            OffsetDateTime createdAt
    ) {
    }

    /**
     * The whole score story for one lead.
     *
     * One endpoint rather than three, because every consumer of any part of this
     * needs the rest: a timeline without the current score has no anchor, and a
     * score without the reading behind it cannot be questioned.
     */
    public record LeadScoreTimeline(
            String leadId,
            Integer currentScore,
            String currentPriority,
            Double qualificationProbability,
            String qualificationStatus,
            AiInsightView latestInsight,
            List<ScoreTimelineEntry> timeline
    ) {
    }

    /**
     * The sales executive's verdict.
     *
     * A boolean, not a score. The whole point of the redesign is that the score
     * is the engine's and the decision is the human's; letting this carry a
     * number would put them back in the same hands.
     */
    public record QualifyRequest(
            @NotNull Boolean qualified,
            @Size(max = 1000) String note
    ) {
    }
}
