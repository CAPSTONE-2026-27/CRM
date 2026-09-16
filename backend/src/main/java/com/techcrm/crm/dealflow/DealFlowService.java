package com.techcrm.crm.dealflow;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.deal.DealRepository;
import com.techcrm.crm.deal.DealScoringClient;
import com.techcrm.crm.deal.DealStages;
import com.techcrm.crm.dealflow.DealAnalysisClient.AnalysisResult;
import com.techcrm.crm.dealflow.DealAnalysisClient.ExtractedValue;
import com.techcrm.crm.dealflow.DealFlowDtos.*;
import com.techcrm.crm.dealflow.FeatureEngineeringService.EngineeredFeatures;
import com.techcrm.crm.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the deal analysis pipeline: meeting output -> LLM extraction ->
 * feature engineering -> XGBoost prediction -> dashboard.
 *
 * Runs synchronously inside the submit request. The model call is the slow part
 * (single-digit seconds), and an executive who has just spent two minutes
 * writing up a meeting expects to see what it produced — pushing this onto a
 * queue would trade that for a spinner and a polling loop.
 *
 * Every stage is persisted before the next one runs, so a failure halfway
 * through leaves a partial chain that can be inspected rather than nothing at
 * all. Only the meeting output itself is non-negotiable: if the model, the
 * feature layer or the scorer fails, the write-up is still saved.
 */
@Service
public class DealFlowService {

    private static final Logger log = LoggerFactory.getLogger(DealFlowService.class);

    private final DealRepository dealRepository;
    private final MeetingOutputRepository meetingOutputRepository;
    private final DealAnalysisRepository analysisRepository;
    private final ExtractedParameterRepository parameterRepository;
    private final FeatureSetRepository featureSetRepository;
    private final DealPredictionRepository predictionRepository;
    private final ManagerReviewRepository reviewRepository;

    private final DealStateClient dealStateClient;
    private final DealAnalysisClient analysisClient;
    private final HeuristicMeetingAnalyzer heuristicAnalyzer;
    private final FeatureEngineeringService featureEngineering;
    private final DealPredictionService predictionService;
    private final DealScoringClient scoringClient;
    private final String llmModelVersion;

    public DealFlowService(DealRepository dealRepository,
                           MeetingOutputRepository meetingOutputRepository,
                           DealAnalysisRepository analysisRepository,
                           ExtractedParameterRepository parameterRepository,
                           FeatureSetRepository featureSetRepository,
                           DealPredictionRepository predictionRepository,
                           ManagerReviewRepository reviewRepository,
                           DealStateClient dealStateClient,
                           DealAnalysisClient analysisClient,
                           HeuristicMeetingAnalyzer heuristicAnalyzer,
                           FeatureEngineeringService featureEngineering,
                           DealPredictionService predictionService,
                           DealScoringClient scoringClient,
                           @Value("${ai.model-name:unknown}") String llmModelVersion) {
        this.dealRepository = dealRepository;
        this.meetingOutputRepository = meetingOutputRepository;
        this.analysisRepository = analysisRepository;
        this.parameterRepository = parameterRepository;
        this.featureSetRepository = featureSetRepository;
        this.predictionRepository = predictionRepository;
        this.reviewRepository = reviewRepository;
        this.dealStateClient = dealStateClient;
        this.analysisClient = analysisClient;
        this.heuristicAnalyzer = heuristicAnalyzer;
        this.featureEngineering = featureEngineering;
        this.predictionService = predictionService;
        this.scoringClient = scoringClient;
        this.llmModelVersion = llmModelVersion;
    }

    /* ------------------------------------------------------- submit (step 4-8) */

    /**
     * Records a meeting output and runs the full analysis chain over it.
     *
     * @return the meeting, its extracted parameters, engineered features and
     * prediction — everything the workspace needs to render the result without
     * a second round trip.
     */
    @Transactional
    public MeetingOutputDetail submitMeetingOutput(AuthenticatedUser caller, Long dealId, MeetingOutputRequest request) {
        Deal deal = requireDeal(caller, dealId);

        MeetingOutput meeting = persistMeetingOutput(caller, deal, request);
        DealAnalysis analysis = runAnalysis(caller, deal, meeting);
        List<ExtractedParameter> parameters = parameterRepository.findByAnalysisIdOrderByDisplayOrderAsc(analysis.getId());
        FeatureSet featureSet = featureSetRepository.findByAnalysisId(analysis.getId()).orElse(null);

        // Scoped to this submission's own feature set, not the deal's newest
        // prediction. When scoring is unavailable the deal rightly keeps its
        // previous score, but this meeting must not be reported as having
        // produced it — that would attribute a number to a meeting that never
        // generated one.
        DealPrediction prediction = featureSet == null ? null : predictionRepository
                .findByFeatureSetIdAndOrganizationId(featureSet.getId(), caller.organizationId())
                .orElse(null);

        return MeetingOutputDetail.of(meeting, analysis, parameters, featureSet, prediction);
    }

    private MeetingOutput persistMeetingOutput(AuthenticatedUser caller, Deal deal, MeetingOutputRequest r) {
        int nextVersion = (int) meetingOutputRepository
                .countByDealIdAndOrganizationId(deal.getId(), caller.organizationId()) + 1;

        MeetingOutput meeting = new MeetingOutput();
        meeting.setOrganizationId(caller.organizationId());
        meeting.setDealId(deal.getId());
        meeting.setOpportunityId(deal.getOpportunityId());
        meeting.setLeadId(deal.getLeadId());
        meeting.setVersion(nextVersion);

        meeting.setMeetingDate(r.meetingDate());
        meeting.setMeetingTime(r.meetingTime());
        meeting.setMeetingType(r.meetingType());
        meeting.setParticipants(r.participants());
        meeting.setMeetingSummary(r.meetingSummary());
        meeting.setCustomerRequirements(r.customerRequirements());
        meeting.setKeyDiscussionPoints(r.keyDiscussionPoints());
        meeting.setCustomerQuestions(r.customerQuestions());
        meeting.setCompetitorMentioned(r.competitorMentioned());
        meeting.setObjections(r.objections());
        meeting.setBudgetDiscussion(r.budgetDiscussion());
        meeting.setTimeline(r.timeline());
        meeting.setNextSteps(r.nextSteps());
        meeting.setExecutiveRemarks(r.executiveRemarks());
        meeting.setSubmittedById(caller.userId());

        return meetingOutputRepository.save(meeting);
    }

    /**
     * Steps 5-8, persisted stage by stage.
     *
     * Three readings are tried in order, each a weaker reading of the same
     * meeting. Only the first carries what earlier meetings established; the
     * other two see this write-up alone and default everything it doesn't
     * mention, which is the behaviour that predates the deal-state service.
     */
    private DealAnalysis runAnalysis(AuthenticatedUser caller, Deal deal, MeetingOutput meeting) {
        DealAnalysis analysis = new DealAnalysis();
        analysis.setOrganizationId(caller.organizationId());
        analysis.setDealId(deal.getId());
        analysis.setOpportunityId(deal.getOpportunityId());
        analysis.setLeadId(deal.getLeadId());
        analysis.setMeetingOutputId(meeting.getId());

        Map<String, ExtractedValue> extracted = null;

        /* ---- First choice: stateful extraction ---- */
        Map<String, Object> previousState = previousStateFor(caller, deal);
        DealStateClient.DealStateResult state = dealStateClient.update(previousState, meeting);
        if (state != null) {
            extracted = fromDealState(state, previousState, meeting.getVersion());
            analysis.setStatus(state.adapterLoaded() ? DealAnalysis.SUCCEEDED : DealAnalysis.DEGRADED);
            analysis.setModelVersion("deal-state/"
                    + (state.modelVersion() == null ? "unknown" : state.modelVersion()));
            analysis.setLatencyMs((int) Math.min(state.latencyMs(), Integer.MAX_VALUE));
            analysis.setRawResponse(state.rawResponse());

            if (!state.adapterLoaded()) {
                analysis.setErrorMessage("The deal-state service answered from base weights — no trained adapter "
                        + "was loaded, so these values are not production output.");
            } else if (!state.repairs().isEmpty()) {
                // Surfaced rather than only logged: a repaired one-hot value is
                // the failure the scorer accepts silently, so the record has to
                // say it happened or nothing downstream ever can.
                analysis.setErrorMessage(state.repairs().size()
                        + " value(s) were repaired to fit the scorer's vocabulary: "
                        + String.join("; ", state.repairs()));
            }
        }

        /* ---- Second: single-meeting extraction, the pre-deal-state path ---- */
        if (extracted == null) {
            Integer leadScore = deal.getLeadScore() == null ? null : (int) Math.round(deal.getLeadScore());
            AnalysisResult result = analysisClient.analyze(meeting, deal.getName(), leadScore);
            if (result != null) {
                extracted = result.parameters();
                analysis.setStatus(DealAnalysis.SUCCEEDED);
                analysis.setModelVersion(llmModelVersion);
                analysis.setLatencyMs((int) Math.min(result.latencyMs(), Integer.MAX_VALUE));
                analysis.setRawResponse(result.rawResponse());
            }
        }

        /* ---- Last resort: keyword match ---- */
        if (extracted == null) {
            extracted = heuristicAnalyzer.analyze(meeting);
            analysis.setStatus(DealAnalysis.DEGRADED);
            analysis.setModelVersion("heuristic-fallback");
            analysis.setErrorMessage("The analysis model was unavailable or returned an unusable reply; "
                    + "parameters were derived by keyword match.");
            log.warn("Deal analysis degraded to heuristics for deal {} meeting {}", deal.getId(), meeting.getId());
        }

        DealAnalysis saved = analysisRepository.save(analysis);

        persistParameters(caller, deal, saved, extracted);
        EngineeredFeatures engineered = engineerAndPersist(caller, deal, saved, extracted, meeting.getVersion());
        predictAndPersist(caller, deal, saved, engineered);

        return saved;
    }

    /**
     * The model inputs from this deal's most recent analysed meeting — the state
     * the new one starts from. Null on the first meeting, which the deal-state
     * service reads as "no prior state" and answers from its own defaults.
     */
    private Map<String, Object> previousStateFor(AuthenticatedUser caller, Deal deal) {
        return featureSetRepository
                .findFirstByDealIdAndOrganizationIdOrderByIdDesc(deal.getId(), caller.organizationId())
                .map(FeatureSet::getModelInputs)
                .orElse(null);
    }

    /**
     * Maps the seventeen-field state onto the fourteen parameters the rest of
     * the chain reads.
     *
     * The other three — total_meetings, lead_score and engagement_score — are
     * deliberately dropped here. They are arithmetic rather than judgement, the
     * CRM computes them exactly, and the adapter's own provenance records 40%
     * field accuracy on lead_score. {@link FeatureEngineeringService} puts the
     * correct values back.
     */
    private Map<String, ExtractedValue> fromDealState(DealStateClient.DealStateResult result,
                                                      Map<String, Object> previousState,
                                                      int meetingVersion) {
        Map<String, Object> state = result.state();
        Set<String> repaired = repairedFields(result.repairs());
        boolean firstMeeting = previousState == null || previousState.isEmpty();

        Map<String, ExtractedValue> extracted = new LinkedHashMap<>();
        for (String parameter : DealParameters.ORDERED) {
            String key = DealParameters.modelKey(parameter);
            Object value = state.get(key);
            if (value == null) continue;

            // No confidence. This model reports what it changed, not how sure it
            // is — inventing a number would put a meter on screen that means
            // nothing, and the UI already hides the meter when it is absent.
            extracted.put(parameter, new ExtractedValue(
                    String.valueOf(value),
                    null,
                    explain(firstMeeting, result.changedFields().contains(key),
                            meetingVersion, repaired.contains(key))));
        }
        return extracted;
    }

    /** Where a value came from, in place of a confidence the model doesn't report. */
    private String explain(boolean firstMeeting, boolean changed, int meetingVersion, boolean repaired) {
        String provenance;
        if (firstMeeting) {
            provenance = "Read from this meeting.";
        } else if (changed) {
            provenance = "Updated by this meeting.";
        } else {
            provenance = "Carried forward from meeting " + (meetingVersion - 1) + ".";
        }
        return repaired
                ? provenance + " The value was repaired to fit the scorer's vocabulary."
                : provenance;
    }

    /** Repairs arrive as "field=old->new"; the field name is everything before
     *  the first '='. */
    private Set<String> repairedFields(List<String> repairs) {
        Set<String> fields = new LinkedHashSet<>();
        for (String repair : repairs) {
            int separator = repair.indexOf('=');
            fields.add(separator > 0 ? repair.substring(0, separator) : repair);
        }
        return fields;
    }

    private void persistParameters(AuthenticatedUser caller, Deal deal, DealAnalysis analysis,
                                   Map<String, ExtractedValue> extracted) {
        List<ExtractedParameter> rows = new ArrayList<>();
        int order = 0;
        for (String name : DealParameters.ORDERED) {
            ExtractedValue value = extracted.get(name);
            if (value == null) continue;

            ExtractedParameter row = new ExtractedParameter();
            row.setOrganizationId(caller.organizationId());
            row.setAnalysisId(analysis.getId());
            row.setDealId(deal.getId());
            row.setName(name);
            row.setValue(value.value());
            row.setConfidence(value.confidence());
            row.setExplanation(value.explanation());
            row.setDisplayOrder(order++);
            rows.add(row);
        }
        parameterRepository.saveAll(rows);
    }

    private EngineeredFeatures engineerAndPersist(AuthenticatedUser caller, Deal deal, DealAnalysis analysis,
                                                  Map<String, ExtractedValue> extracted, int meetingVersion) {
        // The meeting's own version is the running meeting count for this deal,
        // which is exactly what the model's total_meetings input means.
        EngineeredFeatures engineered = featureEngineering.engineer(extracted, meetingVersion, deal.getLeadScore());

        FeatureSet featureSet = new FeatureSet();
        featureSet.setOrganizationId(caller.organizationId());
        featureSet.setDealId(deal.getId());
        featureSet.setAnalysisId(analysis.getId());
        featureSet.setFeatures(engineered.features());
        featureSet.setModelInputs(engineered.modelInputs());
        featureSet.setImputedFields(engineered.imputedFields());
        featureSetRepository.save(featureSet);

        return engineered;
    }

    private void predictAndPersist(AuthenticatedUser caller, Deal deal, DealAnalysis analysis,
                                   EngineeredFeatures engineered) {

        DealScoringClient.DealScoreResult score = scoringClient.scoreFeatures(engineered.modelInputs());
        if (score == null) {
            // The scorer is down. The meeting, parameters and features are all
            // saved; the deal simply keeps its previous score rather than being
            // wiped, since a stale number beats no number while the service is out.
            log.warn("Deal scoring unavailable for deal {} — analysis {} stored without a prediction",
                    deal.getId(), analysis.getId());
            return;
        }

        Long featureSetId = featureSetRepository.findByAnalysisId(analysis.getId())
                .map(FeatureSet::getId).orElse(null);

        String riskLevel = predictionService.riskLevel(
                score.dealScore(), engineered.features(), engineered.imputedFields());
        DealPredictionService.Factors factors = predictionService.factors(
                engineered.features(), predictionService.labelsFrom(engineered), engineered.imputedFields());
        String action = predictionService.recommendedAction(score.dealScore());

        DealPrediction prediction = new DealPrediction();
        prediction.setOrganizationId(caller.organizationId());
        prediction.setDealId(deal.getId());
        prediction.setOpportunityId(deal.getOpportunityId());
        prediction.setLeadId(deal.getLeadId());
        prediction.setFeatureSetId(featureSetId);
        prediction.setDealScore(score.dealScore());
        prediction.setWinProbability(score.winProbability());
        prediction.setBand(score.band());
        prediction.setRiskLevel(riskLevel);
        prediction.setConfidence(engineered.meanConfidence());
        prediction.setRecommendedAction(action);
        prediction.setPositiveFactors(factors.positive());
        prediction.setNegativeFactors(factors.negative());
        prediction.setModelVersion(score.modelVersion());
        predictionRepository.save(prediction);

        // Denormalised onto the deal so the pipeline board can colour cards
        // without joining the prediction history for every row.
        deal.setDealScore(score.dealScore());
        deal.setDealScoreBand(score.band());
        deal.setDealScoreAction(action);
        deal.setDealScoreModelVersion(score.modelVersion());
        deal.setDealScoredAt(OffsetDateTime.now());
        deal.setWinProbability(score.winProbability());
        deal.setRiskLevel(riskLevel);
        dealRepository.save(deal);
    }

    /* --------------------------------------------------------------- reads */

    @Transactional(readOnly = true)
    public DealWorkspace workspace(AuthenticatedUser caller, Long dealId) {
        Deal deal = requireDeal(caller, dealId);

        List<MeetingOutput> meetings = meetingOutputRepository
                .findByDealIdAndOrganizationIdOrderByVersionDesc(dealId, caller.organizationId());

        List<MeetingOutputDetail> history = new ArrayList<>();
        for (MeetingOutput meeting : meetings) {
            DealAnalysis analysis = analysisRepository
                    .findByMeetingOutputIdAndOrganizationId(meeting.getId(), caller.organizationId())
                    .orElse(null);

            List<ExtractedParameter> parameters = analysis == null
                    ? List.of()
                    : parameterRepository.findByAnalysisIdOrderByDisplayOrderAsc(analysis.getId());
            FeatureSet featureSet = analysis == null
                    ? null
                    : featureSetRepository.findByAnalysisId(analysis.getId()).orElse(null);
            DealPrediction prediction = featureSet == null ? null : predictionRepository
                    .findByFeatureSetIdAndOrganizationId(featureSet.getId(), caller.organizationId())
                    .orElse(null);

            history.add(MeetingOutputDetail.of(meeting, analysis, parameters, featureSet, prediction));
        }

        DealPrediction latest = predictionRepository
                .findFirstByDealIdAndOrganizationIdOrderByPredictedAtDesc(dealId, caller.organizationId())
                .orElse(null);

        List<ManagerReview> reviews = reviewRepository
                .findByDealIdAndOrganizationIdOrderByCreatedAtDesc(dealId, caller.organizationId());

        return DealWorkspace.of(deal, history, latest, reviews);
    }

    /* --------------------------------------------------- manager review (10) */

    @Transactional
    public ManagerReviewResponse review(AuthenticatedUser caller, Long dealId, ManagerReviewRequest request) {
        if (caller.role() != Role.ADMIN && caller.role() != Role.MANAGER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an administrator or sales manager can review a deal recommendation");
        }

        Deal deal = requireDeal(caller, dealId);
        String decision = normaliseDecision(request.decision());

        DealPrediction prediction = predictionRepository
                .findFirstByDealIdAndOrganizationIdOrderByPredictedAtDesc(dealId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "This deal has no prediction to review — submit a meeting output first."));

        if (ManagerReview.OVERRIDDEN.equals(decision)
                && (request.overriddenAction() == null || request.overriddenAction().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An overriding decision needs the action to take instead.");
        }

        ManagerReview review = new ManagerReview();
        review.setOrganizationId(caller.organizationId());
        review.setDealId(deal.getId());
        review.setOpportunityId(deal.getOpportunityId());
        review.setPredictionId(prediction.getId());
        review.setDecision(decision);
        // Frozen from the prediction rather than taken from the request, so the
        // record shows what the model actually said, not what a client claimed.
        review.setRecommendedAction(prediction.getRecommendedAction());
        review.setOverriddenAction(request.overriddenAction());
        review.setComments(request.comments());
        review.setReviewedById(caller.userId());

        ManagerReview saved = reviewRepository.save(review);

        // An approved or overridden recommendation becomes the deal's active
        // next action; a rejection leaves the model's standing so the
        // disagreement stays visible rather than being erased.
        if (ManagerReview.OVERRIDDEN.equals(decision)) {
            deal.setDealScoreAction(request.overriddenAction());
            dealRepository.save(deal);
        }

        return ManagerReviewResponse.from(saved);
    }

    private String normaliseDecision(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        if (!List.of(ManagerReview.APPROVED, ManagerReview.REJECTED, ManagerReview.OVERRIDDEN).contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Decision must be APPROVED, REJECTED or OVERRIDDEN");
        }
        return value;
    }

    private Deal requireDeal(AuthenticatedUser caller, Long dealId) {
        Deal deal = dealRepository.findByIdAndOrganizationId(dealId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deal not found"));

        // Older deals predate the opportunity reference; fill it in on first
        // touch rather than leaving a workspace with a blank identifier.
        if (deal.getOpportunityId() == null) {
            deal.setOpportunityId(DealStages.opportunityReference(deal.getId()));
            deal = dealRepository.save(deal);
        }
        return deal;
    }
}
