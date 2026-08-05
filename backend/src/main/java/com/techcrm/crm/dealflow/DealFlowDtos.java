package com.techcrm.crm.dealflow;

import com.techcrm.crm.deal.Deal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Wire shapes for the deal analysis pipeline. */
public final class DealFlowDtos {

    private DealFlowDtos() {
    }

    /* ----------------------------------------------------------- requests */

    /**
     * Deal flow step 4. Only the date, time and a summary are required — an
     * executive should not be blocked from recording a meeting because the
     * customer never mentioned a competitor.
     */
    public record MeetingOutputRequest(
            @NotNull LocalDate meetingDate,
            @NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$", message = "Meeting time must be HH:mm") String meetingTime,
            @Size(max = 30) String meetingType,
            String participants,

            @NotBlank(message = "A meeting summary is required") String meetingSummary,
            String customerRequirements,
            String keyDiscussionPoints,
            String customerQuestions,
            String competitorMentioned,
            String objections,
            String budgetDiscussion,
            String timeline,
            String nextSteps,
            String executiveRemarks
    ) {
    }

    public record ManagerReviewRequest(
            @NotBlank String decision,
            @Size(max = 200) String overriddenAction,
            String comments
    ) {
    }

    /* ---------------------------------------------------------- responses */

    public record ExtractedParameterResponse(
            String name,
            String displayName,
            String value,
            Double confidence,
            String explanation
    ) {
        static ExtractedParameterResponse from(ExtractedParameter p) {
            return new ExtractedParameterResponse(
                    p.getName(),
                    DealParameters.DISPLAY_NAMES.getOrDefault(p.getName(), p.getName()),
                    p.getValue(),
                    p.getConfidence(),
                    p.getExplanation());
        }
    }

    public record FeatureSetResponse(
            String id,
            Map<String, Double> features,
            Map<String, Object> modelInputs,
            List<String> imputedFields
    ) {
        static FeatureSetResponse from(FeatureSet f) {
            return f == null ? null
                    : new FeatureSetResponse(String.valueOf(f.getId()), f.getFeatures(), f.getModelInputs(),
                    f.getImputedFields());
        }
    }

    public record PredictionResponse(
            String id,
            Double dealScore,
            Double winProbability,
            String band,
            String riskLevel,
            Double confidence,
            String recommendedAction,
            List<String> positiveFactors,
            List<String> negativeFactors,
            String modelVersion,
            OffsetDateTime predictedAt
    ) {
        static PredictionResponse from(DealPrediction p) {
            return p == null ? null
                    : new PredictionResponse(
                    String.valueOf(p.getId()), p.getDealScore(), p.getWinProbability(), p.getBand(),
                    p.getRiskLevel(), p.getConfidence(), p.getRecommendedAction(),
                    p.getPositiveFactors(), p.getNegativeFactors(), p.getModelVersion(), p.getPredictedAt());
        }
    }

    public record AnalysisResponse(
            String id,
            String status,
            String modelVersion,
            Integer latencyMs,
            String errorMessage,
            OffsetDateTime createdAt
    ) {
        static AnalysisResponse from(DealAnalysis a) {
            return a == null ? null
                    : new AnalysisResponse(String.valueOf(a.getId()), a.getStatus(), a.getModelVersion(),
                    a.getLatencyMs(), a.getErrorMessage(), a.getCreatedAt());
        }
    }

    /** One full turn of the pipeline: the write-up and everything derived from it. */
    public record MeetingOutputDetail(
            String id,
            String dealId,
            String opportunityId,
            String leadId,
            Integer version,

            LocalDate meetingDate,
            String meetingTime,
            String meetingType,
            String participants,
            String meetingSummary,
            String customerRequirements,
            String keyDiscussionPoints,
            String customerQuestions,
            String competitorMentioned,
            String objections,
            String budgetDiscussion,
            String timeline,
            String nextSteps,
            String executiveRemarks,

            String submittedById,
            OffsetDateTime createdAt,

            AnalysisResponse analysis,
            List<ExtractedParameterResponse> parameters,
            FeatureSetResponse featureSet,
            PredictionResponse prediction
    ) {
        public static MeetingOutputDetail of(MeetingOutput m, DealAnalysis analysis,
                                             List<ExtractedParameter> parameters, FeatureSet featureSet,
                                             DealPrediction prediction) {
            return new MeetingOutputDetail(
                    String.valueOf(m.getId()),
                    String.valueOf(m.getDealId()),
                    m.getOpportunityId(),
                    m.getLeadId() == null ? null : String.valueOf(m.getLeadId()),
                    m.getVersion(),

                    m.getMeetingDate(), m.getMeetingTime(), m.getMeetingType(), m.getParticipants(),
                    m.getMeetingSummary(), m.getCustomerRequirements(), m.getKeyDiscussionPoints(),
                    m.getCustomerQuestions(), m.getCompetitorMentioned(), m.getObjections(),
                    m.getBudgetDiscussion(), m.getTimeline(), m.getNextSteps(), m.getExecutiveRemarks(),

                    m.getSubmittedById() == null ? null : String.valueOf(m.getSubmittedById()),
                    m.getCreatedAt(),

                    AnalysisResponse.from(analysis),
                    parameters.stream().map(ExtractedParameterResponse::from).toList(),
                    FeatureSetResponse.from(featureSet),
                    PredictionResponse.from(prediction));
        }
    }

    public record ManagerReviewResponse(
            String id,
            String dealId,
            String decision,
            String recommendedAction,
            String overriddenAction,
            String comments,
            String reviewedById,
            OffsetDateTime createdAt
    ) {
        public static ManagerReviewResponse from(ManagerReview r) {
            return new ManagerReviewResponse(
                    String.valueOf(r.getId()), String.valueOf(r.getDealId()), r.getDecision(),
                    r.getRecommendedAction(), r.getOverriddenAction(), r.getComments(),
                    r.getReviewedById() == null ? null : String.valueOf(r.getReviewedById()),
                    r.getCreatedAt());
        }
    }

    /** Everything the deal workspace renders, in one response. */
    public record DealWorkspace(
            String dealId,
            String opportunityId,
            String leadId,
            String name,
            String stage,
            BigDecimal value,
            String currency,
            OffsetDateTime meetingScheduledAt,
            String meetingMode,
            String meetingParticipants,
            String closingReason,
            OffsetDateTime closedAt,

            PredictionResponse latestPrediction,
            List<MeetingOutputDetail> meetings,
            List<ManagerReviewResponse> reviews
    ) {
        public static DealWorkspace of(Deal deal, List<MeetingOutputDetail> meetings,
                                       DealPrediction latest, List<ManagerReview> reviews) {
            return new DealWorkspace(
                    String.valueOf(deal.getId()),
                    deal.getOpportunityId(),
                    deal.getLeadId() == null ? null : String.valueOf(deal.getLeadId()),
                    deal.getName(),
                    deal.getStage(),
                    deal.getValue(),
                    deal.getCurrency(),
                    deal.getMeetingScheduledAt(),
                    deal.getMeetingMode(),
                    deal.getMeetingParticipants(),
                    deal.getClosingReason(),
                    deal.getClosedAt(),

                    PredictionResponse.from(latest),
                    meetings,
                    reviews.stream().map(ManagerReviewResponse::from).toList());
        }
    }
}
