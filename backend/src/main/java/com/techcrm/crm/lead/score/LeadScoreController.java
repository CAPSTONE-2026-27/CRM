package com.techcrm.crm.lead.score;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.lead.LeadService;
import com.techcrm.crm.lead.score.LeadScoreDtos.*;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * The score timeline, the AI reading behind it, and the qualification decision.
 *
 * Mounted under the existing /api/leads/{id} tree rather than a new root, so it
 * reads as part of a lead rather than a parallel system. Nothing here writes a
 * score: the timeline is read-only and qualification records a human verdict,
 * which are the only two operations the redesign leaves outside the engine.
 */
@RestController
@RequestMapping("/api/leads/{leadId}")
public class LeadScoreController {

    private static final Logger log = LoggerFactory.getLogger(LeadScoreController.class);

    private final LeadScoreService scoreService;
    private final LeadRepository leadRepository;
    private final LeadService leadService;

    public LeadScoreController(LeadScoreService scoreService, LeadRepository leadRepository, LeadService leadService) {
        this.scoreService = scoreService;
        this.leadRepository = leadRepository;
        this.leadService = leadService;
    }

    /**
     * The lead's score history: how it started, and what each meeting did to it.
     *
     * Returns an empty timeline rather than 404 for a lead that has had no
     * meetings — "no movements yet" is a normal state, and making the UI
     * distinguish it from "lead does not exist" would push that logic into
     * every caller.
     */
    @GetMapping("/score-timeline")
    @Transactional(readOnly = true)
    public LeadScoreTimeline timeline(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @PathVariable Long leadId) {
        Lead lead = requireLead(caller, leadId);

        List<ScoreTimelineEntry> entries = scoreService.timeline(caller, leadId).stream()
                .map(this::toEntry)
                .toList();

        LeadAiAnalysis latest = scoreService.latestAnalysis(caller, leadId);

        return new LeadScoreTimeline(
                String.valueOf(lead.getId()),
                lead.getAiScore(),
                lead.getLeadPriority(),
                lead.getQualificationProbability(),
                lead.getQualificationStatus(),
                latest == null ? null : toInsight(latest),
                entries);
    }

    /** The five signals behind the most recent meeting, on their own. */
    @GetMapping("/ai-insight")
    @Transactional(readOnly = true)
    public AiInsightView insight(@AuthenticationPrincipal AuthenticatedUser caller,
                                 @PathVariable Long leadId) {
        requireLead(caller, leadId);
        LeadAiAnalysis latest = scoreService.latestAnalysis(caller, leadId);
        if (latest == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No AI analysis yet — log a qualification meeting first");
        }
        return toInsight(latest);
    }

    /**
     * The sales executive's decision: qualified, or not.
     *
     * The only thing a human sets here. The score, the priority band and the
     * probability all come from the engine and are not accepted from the
     * request — this endpoint deliberately has no field for them.
     *
     * Does not create the deal. Conversion stays the separate, existing
     * POST /api/leads/{id}/convert step so that qualifying and converting can
     * fail independently: a lead marked qualified whose deal creation failed is
     * recoverable, whereas one operation doing both leaves an ambiguous state.
     */
    @PostMapping("/qualify")
    @Transactional
    public LeadScoreTimeline qualify(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @PathVariable Long leadId,
                                     @Valid @RequestBody QualifyRequest request) {
        Lead lead = requireLead(caller, leadId);

        boolean qualified = Boolean.TRUE.equals(request.qualified());
        String status = qualified ? "QUALIFIED" : "UNQUALIFIED";
        lead.setQualificationStatus(status);
        if (request.note() != null && !request.note().isBlank()) {
            lead.setQualificationReasoning(request.note());
        }
        if (qualified) {
            // Qualifying is what makes a lead assignable, so it is also the
            // moment the least-busy rep picks it up. Disqualifying does not
            // unassign: taking a lead out of someone's queue behind their back
            // loses the work already logged against it.
            leadService.autoAssignIfQualified(lead, caller.organizationId());
        }
        leadRepository.save(lead);

        log.info("Lead {} marked {} by user {} (score {}, {})",
                leadId, status, caller.userId(), lead.getAiScore(), lead.getLeadPriority());

        return timeline(caller, leadId);
    }

    private Lead requireLead(AuthenticatedUser caller, Long leadId) {
        return leadRepository.findByIdAndOrganizationId(leadId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));
    }

    private ScoreTimelineEntry toEntry(LeadScoreHistory history) {
        return new ScoreTimelineEntry(
                history.getId(),
                history.getMeetingNumber(),
                history.getMeetingDate(),
                history.getMeetingScore(),
                history.getPriority(),
                history.getPreviousScore(),
                history.getUpdatedScore(),
                history.getScoreDifference(),
                history.getQualificationProbability(),
                history.getChangeReason(),
                history.getSource(),
                toContributionViews(history.getContributions()),
                history.getCreatedAt());
    }

    /**
     * The breakdown is an explanation, not the score, so a row missing one
     * still belongs on the timeline — dropping the entry would hide a real
     * score movement. Rows written before the breakdown existed have none.
     */
    private List<ContributionView> toContributionViews(
            List<LeadScoreFluctuationEngine.Contribution> contributions) {
        if (contributions == null) return List.of();
        return contributions.stream()
                .map(c -> new ContributionView(c.parameter(), c.value(), c.points(), c.maxPoints()))
                .toList();
    }


    private AiInsightView toInsight(LeadAiAnalysis analysis) {
        return new AiInsightView(
                analysis.getCustomerSentiment(),
                analysis.getBuyingIntent(),
                analysis.getDecisionMakerInvolvement(),
                analysis.getCustomerUrgency(),
                analysis.getProductInterestLevel(),
                analysis.getQualificationProbability(),
                analysis.getSource(),
                analysis.getModelVersion(),
                analysis.getCreatedAt());
    }
}
