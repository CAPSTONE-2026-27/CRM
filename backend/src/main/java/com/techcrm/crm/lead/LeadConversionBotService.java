package com.techcrm.crm.lead;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.sap.SapProcessProperties;
import com.techcrm.crm.sap.SapProcessTriggerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

/**
 * Converting a lead by handing the job to an automation.
 *
 * The conversion itself is unchanged — the automation calls
 * {@code POST /api/leads/{id}/convert} like any other client. What this adds is
 * the decision about whether it may: a lead reaches an opportunity when the
 * scoring model says it is worth one, not merely because somebody dragged a
 * card.
 *
 * <b>The model's label decides, not its number.</b> HOT/WARM/COLD is the
 * verdict the model asserts; the score is a ranking aid that moves when the
 * model is retrained. A numeric threshold would also imply that 69 and 71 mean
 * different things, which nobody can defend.
 *
 * <b>An unscored lead is not refused.</b> A model that returned nothing, or
 * something this build does not recognise, has given no verdict — and a
 * scoring service being down must not make leads permanently unconvertible.
 * Those pass to the automation and a person decides, which is what happened
 * before any of this existed.
 */
@Service
public class LeadConversionBotService {

    private static final Logger log = LoggerFactory.getLogger(LeadConversionBotService.class);

    /** The label the model gives a lead worth converting. */
    private static final String READY = "HOT";

    /** Labels this build understands. Anything else is no verdict at all. */
    private static final Set<String> KNOWN_LABELS = Set.of("HOT", "WARM", "COLD");

    private final LeadRepository leadRepository;
    private final SapProcessTriggerClient sap;
    private final SapProcessProperties properties;

    public LeadConversionBotService(LeadRepository leadRepository,
                                    SapProcessTriggerClient sap,
                                    SapProcessProperties properties) {
        this.leadRepository = leadRepository;
        this.sap = sap;
        this.properties = properties;
    }

    /**
     * Checks a lead may be converted, then asks the automation to convert it.
     *
     * Returns as soon as the automation has been started, not when the
     * opportunity exists. The caller refreshes and sees the deal appear.
     *
     * @throws ResponseStatusException 409 when the lead is not ready
     */
    /** Whether an automation is wired up to do the converting. */
    public boolean isConfigured() {
        String definitionId = properties.getOpportunityDefinitionId();
        return sap.isConfigured() && definitionId != null && !definitionId.isBlank();
    }

    @Transactional(readOnly = true)
    public void requestConversion(AuthenticatedUser caller, Long leadId) {
        requireReadyToConvert(caller, leadId);
        startBot(caller.organizationId(), leadId);
    }

    /**
     * Refuses a lead that should not become an opportunity yet.
     *
     * Separate from starting the automation because the same judgement applies
     * whoever does the converting — an installation with no bot configured
     * still should not convert a COLD lead.
     *
     * @throws ResponseStatusException 409 when the lead is not ready
     */
    @Transactional(readOnly = true)
    public void requireReadyToConvert(AuthenticatedUser caller, Long leadId) {
        Lead lead = leadRepository.findByIdAndOrganizationId(leadId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));

        // The same two the conversion endpoint enforces, checked here so the
        // representative is told now rather than after an automation has run.
        if (lead.getConvertedDealId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This lead has already been converted to opportunity " + lead.getConvertedDealId());
        }
        if ("UNQUALIFIED".equals(lead.getQualificationStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only qualified leads can become opportunities.");
        }

        requireScoredReady(lead);
    }

    /**
     * Refuses a lead the model has judged and not thought ready.
     *
     * The message names the verdict and what to do about it, because "not
     * ready" on its own leaves a representative with a card that will not move
     * and no idea why.
     */
    private void requireScoredReady(Lead lead) {
        String label = lead.getAiScoreLabel() == null ? "" : lead.getAiScoreLabel().trim().toUpperCase();

        if (!KNOWN_LABELS.contains(label)) {
            // No verdict. Scoring may not have run, or may have returned
            // something this build does not know. Either way it is not grounds
            // for refusal.
            log.info("Lead {} has no recognised score label ({}); converting without one",
                    lead.getId(), lead.getAiScoreLabel());
            return;
        }

        if (!READY.equals(label)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    lead.getFullName() + " is " + label + ". Hold a meeting and submit the outcome — "
                            + "the lead is scored again from what was discussed, and a " + READY
                            + " lead converts automatically.");
        }
    }

    /**
     * Starts the automation.
     *
     * Asynchronous and after the read has finished: the representative's drag
     * must not wait on a cloud platform, and a platform that cannot be reached
     * is a logged warning rather than a failed conversion.
     */
    @Async
    public void startBot(Long organizationId, Long leadId) {
        String definitionId = properties.getOpportunityDefinitionId();
        if (definitionId == null || definitionId.isBlank()) {
            log.warn("No opportunity bot is configured; lead {} was not converted", leadId);
            return;
        }

        String instanceId = sap.startProcess(definitionId, Map.of("leadid", String.valueOf(leadId)));
        if (instanceId == null) {
            log.warn("Could not start the opportunity bot for lead {}", leadId);
            return;
        }
        log.info("Started the opportunity bot for lead {} as instance {}", leadId, instanceId);
    }
}
