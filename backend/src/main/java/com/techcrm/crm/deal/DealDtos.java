package com.techcrm.crm.deal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public final class DealDtos {

    private DealDtos() {
    }

    public record DealRequest(
            @NotBlank String name,
            @NotNull Long accountId,
            @NotNull BigDecimal value,
            String currency,
            OffsetDateTime expectedCloseDate,
            String stage,
            @Min(0) @Max(100) Integer probability,
            Long ownerId,
            String forecastCategory,
            BigDecimal weightedForecastValue,
            BigDecimal bestCaseValue,
            Boolean autoGenerateProposal,
            Boolean pushToErpOnClose,

            // Deal-scoring model inputs. Optional: a deal can be created before
            // the rep has assessed it, and scored on a later edit.
            Integer totalMeetings,
            @Min(0) @Max(100) Double leadScore,
            String customerSentiment,
            String buyingIntent,
            @Min(0) @Max(10) Double relationshipStrength,
            String budgetStatus,
            String decisionMakerInvolvement,
            String customerUrgency,
            String mainObjections,
            String productInterestLevel,
            String meetingOutcome,
            String customerRequirements,
            String riskFactors,
            String competitorMention,
            @Min(0) @Max(100) Double engagementScore,
            String implementationReadiness,
            String upsellOpportunity
    ) {
    }

    /** Partial update used by the pipeline board, which drags a card between
     *  columns and only ever changes the stage. A closing reason is accepted
     *  alongside it because the two are set together when a deal closes. */
    public record DealStageRequest(@NotBlank String stage, String closingReason) {
    }

    /** Deal flow step 2 — books the customer meeting. */
    public record ScheduleMeetingRequest(
            @NotNull OffsetDateTime meetingScheduledAt,
            String meetingMode,
            String meetingParticipants
    ) {
    }

    /** Deal flow step 13 — the customer's final decision. */
    public record CloseDealRequest(
            @NotBlank String stage,
            @NotBlank String closingReason
    ) {
    }

    public record DealResponse(
            String id,
            String name,
            String accountId,
            BigDecimal value,
            String currency,
            OffsetDateTime expectedCloseDate,
            String stage,
            Integer probability,
            String ownerId,
            String forecastCategory,
            BigDecimal weightedForecastValue,
            BigDecimal bestCaseValue,
            boolean autoGenerateProposal,
            boolean pushToErpOnClose,

            Integer totalMeetings,
            Double leadScore,
            String customerSentiment,
            String buyingIntent,
            Double relationshipStrength,
            String budgetStatus,
            String decisionMakerInvolvement,
            String customerUrgency,
            String mainObjections,
            String productInterestLevel,
            String meetingOutcome,
            String customerRequirements,
            String riskFactors,
            String competitorMention,
            Double engagementScore,
            String implementationReadiness,
            String upsellOpportunity,

            Double dealScore,
            String dealScoreBand,
            String dealScoreAction,
            String dealScoreModelVersion,
            OffsetDateTime dealScoredAt,

            String opportunityId,
            String leadId,
            OffsetDateTime meetingScheduledAt,
            String meetingMode,
            String meetingParticipants,
            Double winProbability,
            String riskLevel,
            String closingReason,
            OffsetDateTime closedAt,

            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static DealResponse from(Deal d) {
            return new DealResponse(
                    String.valueOf(d.getId()),
                    d.getName(),
                    String.valueOf(d.getAccountId()),
                    d.getValue(),
                    d.getCurrency(),
                    d.getExpectedCloseDate(),
                    d.getStage(),
                    d.getProbability(),
                    d.getOwnerId() == null ? null : String.valueOf(d.getOwnerId()),
                    d.getForecastCategory(),
                    d.getWeightedForecastValue(),
                    d.getBestCaseValue(),
                    d.isAutoGenerateProposal(),
                    d.isPushToErpOnClose(),

                    d.getTotalMeetings(),
                    d.getLeadScore(),
                    d.getCustomerSentiment(),
                    d.getBuyingIntent(),
                    d.getRelationshipStrength(),
                    d.getBudgetStatus(),
                    d.getDecisionMakerInvolvement(),
                    d.getCustomerUrgency(),
                    d.getMainObjections(),
                    d.getProductInterestLevel(),
                    d.getMeetingOutcome(),
                    d.getCustomerRequirements(),
                    d.getRiskFactors(),
                    d.getCompetitorMention(),
                    d.getEngagementScore(),
                    d.getImplementationReadiness(),
                    d.getUpsellOpportunity(),

                    d.getDealScore(),
                    d.getDealScoreBand(),
                    d.getDealScoreAction(),
                    d.getDealScoreModelVersion(),
                    d.getDealScoredAt(),

                    d.getOpportunityId(),
                    d.getLeadId() == null ? null : String.valueOf(d.getLeadId()),
                    d.getMeetingScheduledAt(),
                    d.getMeetingMode(),
                    d.getMeetingParticipants(),
                    d.getWinProbability(),
                    d.getRiskLevel(),
                    d.getClosingReason(),
                    d.getClosedAt(),

                    d.getCreatedAt(),
                    d.getUpdatedAt()
            );
        }
    }
}
