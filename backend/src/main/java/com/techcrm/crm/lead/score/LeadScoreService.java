package com.techcrm.crm.lead.score;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.lead.score.LeadMeetingExtractionClient.ExtractionRequest;
import com.techcrm.crm.lead.score.LeadMeetingExtractionClient.ExtractionResult;
import com.techcrm.crm.lead.score.LeadScoreFluctuationEngine.MeetingScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Owns a lead's score for its whole life: the initial value, every movement a
 * qualification meeting causes, and the history that explains them.
 *
 * Separate from LeadMeetingService because the two answer different questions.
 * That service owns meetings — creating them, listing them, storing the rep's
 * write-up. This one owns the score, and it is the only place a score is
 * written. Keeping the writes in one class is what makes "the rep cannot change
 * the score" a structural property rather than a rule someone has to remember.
 *
 * The pipeline, in order:
 *
 *   meeting notes -> fine-tuned model -> five signals
 *                 -> LeadScoreFluctuationEngine -> meeting score, band, updated score
 *                 -> lead_ai_analysis + lead_score_history + the lead's live score
 *
 * The model contributes only the middle step. It never sees the current score
 * and never returns one.
 */
@Service
public class LeadScoreService {

    private static final Logger log = LoggerFactory.getLogger(LeadScoreService.class);

    private final LeadRepository leadRepository;
    private final LeadAiAnalysisRepository analysisRepository;
    private final LeadScoreHistoryRepository historyRepository;
    private final LeadMeetingExtractionClient extractionClient;
    private final LeadScoreFluctuationEngine engine;

    public LeadScoreService(LeadRepository leadRepository,
                            LeadAiAnalysisRepository analysisRepository,
                            LeadScoreHistoryRepository historyRepository,
                            LeadMeetingExtractionClient extractionClient,
                            LeadScoreFluctuationEngine engine) {
        this.leadRepository = leadRepository;
        this.analysisRepository = analysisRepository;
        this.historyRepository = historyRepository;
        this.extractionClient = extractionClient;
        this.engine = engine;
    }

    /** What a meeting did to a lead, before anything is persisted. */
    public record MeetingOutcome(
            LeadSignals signals,
            MeetingScore score,
            double qualificationProbability,
            String source,
            String rawResponse
    ) {
    }

    /**
     * Reads a meeting and computes its effect, without saving anything.
     *
     * Backs the preview step: the rep sees what the AI read and what it does to
     * the score before committing. They can edit their notes and re-run — but
     * not the score, which is why this returns the engine's number rather than
     * a suggestion.
     */
    public MeetingOutcome analyse(Lead lead, String meetingNotes, int meetingNumber,
                                  LocalDate meetingDate) {
        ExtractionRequest request = new ExtractionRequest(
                lead.getFullName(), lead.getCompany(), lead.getIndustry(),
                lead.getCompanyLocation(), lead.getEmployeeCount(), lead.getProductQuantity(),
                lead.getEstimatedDealValue(), lead.getPurchaseTimeline(), lead.getCustomerType(),
                lead.getSourceChannel(), lead.getNotes(), meetingNumber,
                meetingDate == null ? null : meetingDate.toString(), null, meetingNotes);

        ExtractionResult extraction = extractionClient.extract(request);

        LeadSignals signals;
        String source;
        String raw;
        if (extraction == null) {
            // Model unreachable or unparseable. The keyword fallback only moves
            // a signal on an explicit phrase, so an unreadable meeting records
            // "nothing learned" rather than manufacturing a movement.
            signals = extractionClient.heuristic(meetingNotes);
            source = "HEURISTIC";
            raw = null;
            log.warn("Lead {} meeting {}: extraction unavailable, using heuristic reading",
                    lead.getId(), meetingNumber);
        } else {
            signals = extraction.signals();
            source = extraction.repaired() ? "REPAIRED" : "MODEL";
            raw = extraction.raw();
        }

        MeetingScore score = engine.score(lead.getAiScore(), signals);
        double probability = engine.qualificationProbability(signals, score.meetingScore());
        return new MeetingOutcome(signals, score, probability, source, raw);
    }

    /**
     * Persists a meeting's effect: the reading, the movement, and the lead's new
     * live score.
     *
     * Recomputed here rather than trusting whatever the preview returned. The
     * preview is a read-only HTTP round trip the client could have altered, and
     * the notes may have been edited between preview and save — so the reading
     * that gets stored is always the reading of the text that was actually
     * saved.
     */
    @Transactional
    public MeetingOutcome recordMeeting(AuthenticatedUser caller, Lead lead, Long meetingId,
                                        String meetingNotes, LocalDate meetingDate) {
        int meetingNumber = (int) historyRepository
                .countByLeadIdAndOrganizationIdAndMeetingIdIsNotNull(
                        lead.getId(), caller.organizationId()) + 1;

        // The lead's score before any meeting existed. Recorded once, lazily, so
        // a timeline starts from where the lead actually began rather than
        // appearing to spring into existence at its first meeting.
        if (!historyRepository.existsByLeadIdAndOrganizationId(lead.getId(), caller.organizationId())) {
            recordInitial(caller, lead);
        }

        MeetingOutcome outcome = analyse(lead, meetingNotes, meetingNumber, meetingDate);

        LeadAiAnalysis analysis = new LeadAiAnalysis();
        analysis.setOrganizationId(caller.organizationId());
        analysis.setLeadId(lead.getId());
        analysis.setMeetingId(meetingId);
        analysis.applySignals(outcome.signals());
        analysis.setQualificationProbability(outcome.qualificationProbability());
        analysis.setRawResponse(outcome.rawResponse());
        analysis.setSource(outcome.source());
        analysis.setModelVersion(extractionModelVersion());
        analysis.setCreatedBy(caller.userId());
        analysis = analysisRepository.save(analysis);

        MeetingScore score = outcome.score();
        LeadScoreHistory history = new LeadScoreHistory();
        history.setOrganizationId(caller.organizationId());
        history.setLeadId(lead.getId());
        history.setMeetingId(meetingId);
        history.setAnalysisId(analysis.getId());
        history.setMeetingNumber(meetingNumber);
        history.setMeetingScore(score.meetingScore());
        history.setPriority(score.priority());
        history.setPreviousScore(score.previousScore());
        history.setUpdatedScore(score.updatedScore());
        history.setScoreDifference(score.scoreDifference());
        history.setQualificationProbability(outcome.qualificationProbability());
        history.setContributions(score.contributions());
        history.setChangeReason(score.changeReason());
        history.setSource("ENGINE");
        history.setModelVersion(extractionModelVersion());
        history.setMeetingDate(meetingDate);
        history.setCreatedBy(caller.userId());
        historyRepository.save(history);

        // The live score on `leads` stays denormalised so existing list and
        // filter queries keep working untouched. The history is the record of
        // how it got here; this is the current value.
        lead.setAiScore(score.updatedScore());
        lead.setLeadPriority(score.priority());
        lead.setQualificationProbability(outcome.qualificationProbability());
        lead.setAiScoreReason(score.changeReason());
        leadRepository.save(lead);

        log.info("Lead {} meeting {}: {} -> {} ({}{}) {} [{}]",
                lead.getId(), meetingNumber, score.previousScore(), score.updatedScore(),
                score.scoreDifference() >= 0 ? "+" : "", score.scoreDifference(),
                score.priority(), outcome.source());

        return outcome;
    }

    /** The lead's capture-time score, as the first row of its timeline. */
    @Transactional
    public void recordInitial(AuthenticatedUser caller, Lead lead) {
        if (historyRepository.existsByLeadIdAndOrganizationId(lead.getId(), caller.organizationId())) {
            return;
        }
        int captureScore = lead.getAiScore() == null ? 50 : lead.getAiScore();
        MeetingScore initial = engine.initial(captureScore);

        LeadScoreHistory history = new LeadScoreHistory();
        history.setOrganizationId(caller.organizationId());
        history.setLeadId(lead.getId());
        history.setMeetingNumber(0);
        history.setPreviousScore(null);
        history.setUpdatedScore(initial.updatedScore());
        history.setScoreDifference(0);
        history.setQualificationProbability(lead.getQualificationProbability());
        history.setChangeReason(initial.changeReason());
        history.setSource("INITIAL");
        history.setCreatedBy(caller.userId());
        historyRepository.save(history);
    }

    @Transactional(readOnly = true)
    public List<LeadScoreHistory> timeline(AuthenticatedUser caller, Long leadId) {
        return historyRepository.findByLeadIdAndOrganizationIdOrderByCreatedAtAsc(
                leadId, caller.organizationId());
    }

    @Transactional(readOnly = true)
    public LeadAiAnalysis latestAnalysis(AuthenticatedUser caller, Long leadId) {
        return analysisRepository
                .findFirstByLeadIdAndOrganizationIdOrderByCreatedAtDesc(leadId, caller.organizationId())
                .orElse(null);
    }

    private String extractionModelVersion() {
        return "lead-meeting-extraction-v1";
    }

}
