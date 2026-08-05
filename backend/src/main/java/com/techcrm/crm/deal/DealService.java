package com.techcrm.crm.deal;

import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.deal.DealDtos.DealRequest;
import com.techcrm.crm.deal.DealDtos.DealResponse;
import com.techcrm.crm.onboarding.CustomerOnboardingService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class DealService {

    private final DealRepository dealRepository;
    private final AccountRepository accountRepository;
    private final DealScoringClient dealScoringClient;
    private final CustomerOnboardingService onboardingService;

    public DealService(DealRepository dealRepository,
                       AccountRepository accountRepository,
                       DealScoringClient dealScoringClient,
                       CustomerOnboardingService onboardingService) {
        this.dealRepository = dealRepository;
        this.accountRepository = accountRepository;
        this.dealScoringClient = dealScoringClient;
        this.onboardingService = onboardingService;
    }

    @Transactional(readOnly = true)
    public List<DealResponse> list(AuthenticatedUser caller) {
        return dealRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())
                .stream().map(DealResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public DealResponse get(AuthenticatedUser caller, Long id) {
        return DealResponse.from(require(caller, id));
    }

    @Transactional
    public DealResponse create(AuthenticatedUser caller, DealRequest request) {
        Deal deal = new Deal();
        deal.setOrganizationId(caller.organizationId());
        apply(caller, deal, request);
        applyDealScore(deal);

        Deal saved = dealRepository.save(deal);
        // The opportunity reference is derived from the id, so it can only be
        // assigned once the insert has allocated one.
        saved.setOpportunityId(DealStages.opportunityReference(saved.getId()));
        return DealResponse.from(dealRepository.save(saved));
    }

    @Transactional
    public DealResponse update(AuthenticatedUser caller, Long id, DealRequest request) {
        Deal deal = require(caller, id);
        apply(caller, deal, request);
        applyDealScore(deal);
        return DealResponse.from(dealRepository.save(deal));
    }

    /** Re-scores the deal from its current inputs. A null result means the model
     *  had nothing to work with or was unreachable — the previous score is then
     *  left in place rather than being wiped, since a stale score is more useful
     *  than none while the service is down. */
    private void applyDealScore(Deal deal) {
        DealScoringClient.DealScoreResult result = dealScoringClient.score(deal);
        if (result == null) {
            return;
        }
        deal.setDealScore(result.dealScore());
        deal.setDealScoreBand(result.band());
        deal.setDealScoreAction(result.action());
        deal.setDealScoreModelVersion(result.modelVersion());
        deal.setDealScoredAt(OffsetDateTime.now());
        deal.setWinProbability(result.winProbability());
    }

    /** Stage-only update, so dragging a card on the pipeline board doesn't
     *  require the client to round-trip the whole deal. */
    @Transactional
    public DealResponse updateStage(AuthenticatedUser caller, Long id, String stage, String closingReason) {
        Deal deal = require(caller, id);
        applyStageTransition(caller, deal, stage, closingReason);
        return DealResponse.from(dealRepository.save(deal));
    }

    /** Deal flow step 2 — books the customer meeting and advances the stage. */
    @Transactional
    public DealResponse scheduleMeeting(AuthenticatedUser caller, Long id,
                                        OffsetDateTime scheduledAt, String mode, String participants) {
        Deal deal = require(caller, id);
        deal.setMeetingScheduledAt(scheduledAt);
        deal.setMeetingMode(mode == null ? null : mode.trim().toUpperCase());
        deal.setMeetingParticipants(participants);

        // Only advances a deal that hasn't moved past this point — re-booking a
        // meeting on a deal already in negotiation must not drag it backwards.
        if (DealStages.OPPORTUNITY_CREATED.equals(deal.getStage())) {
            deal.setStage(DealStages.MEETING_SCHEDULED);
        }
        return DealResponse.from(dealRepository.save(deal));
    }

    /**
     * Applies a stage change, including the side effects of closing.
     *
     * Closed Won initiating onboarding lives here rather than in the caller so
     * every path that closes a deal — the pipeline board, the close dialog, a
     * future workflow rule — gets the handover for free.
     */
    private void applyStageTransition(AuthenticatedUser caller, Deal deal, String rawStage, String closingReason) {
        String stage = normaliseStage(rawStage);
        deal.setStage(stage);

        if (!DealStages.isClosed(stage)) {
            // Reopening a deal clears the closure: leaving a stale "lost to
            // competitor" on an active deal would poison every report using it.
            deal.setClosedAt(null);
            deal.setClosingReason(null);
            return;
        }

        deal.setClosedAt(OffsetDateTime.now());
        if (closingReason != null && !closingReason.isBlank()) {
            deal.setClosingReason(closingReason.trim());
        }

        if (DealStages.CLOSED_WON.equals(stage)) {
            onboardingService.initiate(caller.organizationId(), deal.getId(), deal.getOpportunityId(),
                    deal.getAccountId(), deal.getOwnerId());
        }
    }

    @Transactional
    public void delete(AuthenticatedUser caller, Long id) {
        dealRepository.delete(require(caller, id));
    }

    private Deal require(AuthenticatedUser caller, Long id) {
        return dealRepository.findByIdAndOrganizationId(id, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deal not found"));
    }

    private String normaliseStage(String stage) {
        String value = stage == null ? "" : stage.trim().toUpperCase().replace(' ', '_');
        if (!DealStages.ALL.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown deal stage: " + stage);
        }
        return value;
    }

    private void apply(AuthenticatedUser caller, Deal deal, DealRequest request) {
        accountRepository.findByIdAndOrganizationId(request.accountId(), caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account not found"));

        deal.setName(request.name());
        deal.setAccountId(request.accountId());
        deal.setValue(request.value());
        if (request.currency() != null) deal.setCurrency(request.currency());
        deal.setExpectedCloseDate(request.expectedCloseDate());
        if (request.stage() != null) deal.setStage(normaliseStage(request.stage()));
        deal.setProbability(request.probability());
        deal.setOwnerId(request.ownerId());
        deal.setForecastCategory(request.forecastCategory());
        deal.setWeightedForecastValue(request.weightedForecastValue());
        deal.setBestCaseValue(request.bestCaseValue());
        if (request.autoGenerateProposal() != null) deal.setAutoGenerateProposal(request.autoGenerateProposal());
        if (request.pushToErpOnClose() != null) deal.setPushToErpOnClose(request.pushToErpOnClose());

        deal.setTotalMeetings(request.totalMeetings());
        deal.setLeadScore(request.leadScore());
        deal.setCustomerSentiment(request.customerSentiment());
        deal.setBuyingIntent(request.buyingIntent());
        deal.setRelationshipStrength(request.relationshipStrength());
        deal.setBudgetStatus(request.budgetStatus());
        deal.setDecisionMakerInvolvement(request.decisionMakerInvolvement());
        deal.setCustomerUrgency(request.customerUrgency());
        deal.setMainObjections(request.mainObjections());
        deal.setProductInterestLevel(request.productInterestLevel());
        deal.setMeetingOutcome(request.meetingOutcome());
        deal.setCustomerRequirements(request.customerRequirements());
        deal.setRiskFactors(request.riskFactors());
        deal.setCompetitorMention(request.competitorMention());
        deal.setEngagementScore(request.engagementScore());
        deal.setImplementationReadiness(request.implementationReadiness());
        deal.setUpsellOpportunity(request.upsellOpportunity());
    }
}
