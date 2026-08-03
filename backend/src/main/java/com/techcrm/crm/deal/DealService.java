package com.techcrm.crm.deal;

import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.deal.DealDtos.DealRequest;
import com.techcrm.crm.deal.DealDtos.DealResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
public class DealService {

    private static final Set<String> STAGES = Set.of(
            "PROSPECTING", "QUALIFICATION", "PROPOSAL", "NEGOTIATION", "CLOSED_WON", "CLOSED_LOST");

    private final DealRepository dealRepository;
    private final AccountRepository accountRepository;
    private final DealScoringClient dealScoringClient;

    public DealService(DealRepository dealRepository,
                       AccountRepository accountRepository,
                       DealScoringClient dealScoringClient) {
        this.dealRepository = dealRepository;
        this.accountRepository = accountRepository;
        this.dealScoringClient = dealScoringClient;
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
        return DealResponse.from(dealRepository.save(deal));
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
    }

    /** Stage-only update, so dragging a card on the pipeline board doesn't
     *  require the client to round-trip the whole deal. */
    @Transactional
    public DealResponse updateStage(AuthenticatedUser caller, Long id, String stage) {
        Deal deal = require(caller, id);
        deal.setStage(normaliseStage(stage));
        return DealResponse.from(dealRepository.save(deal));
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
        String value = stage == null ? "" : stage.trim().toUpperCase();
        if (!STAGES.contains(value)) {
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
