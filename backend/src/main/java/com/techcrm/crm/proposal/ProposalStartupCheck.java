package com.techcrm.crm.proposal;

import com.techcrm.crm.deal.DealStages;
import com.techcrm.crm.proposal.template.ProposalTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports what the proposal module can and cannot do, once, at startup.
 *
 * Narrower than {@code ContractStartupCheck}, deliberately: LibreOffice is
 * reported there, and this module uses the same instance.
 * Saying it twice on every boot would train whoever reads the log to skim it.
 *
 * What is worth checking here is the two things this module owns — its templates
 * and its stage list. A misconfigured eligible-stages list is a typo, not a
 * deployment choice, and it would make every deal permanently ineligible with no
 * other symptom, so that one is an error rather than a warning.
 */
@Component
public class ProposalStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(ProposalStartupCheck.class);

    private final ProposalProperties properties;
    private final ProposalTemplateService templateService;

    public ProposalStartupCheck(ProposalProperties properties, ProposalTemplateService templateService) {
        this.properties = properties;
        this.templateService = templateService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        List<String> missingTemplates = new ArrayList<>();
        for (ProposalType type : ProposalType.values()) {
            if (!templateService.isAvailable(type)) {
                missingTemplates.add(type.name() + " (" + templateService.templateKey(type) + ")");
            }
        }
        if (missingTemplates.isEmpty()) {
            log.info("Proposal templates available: {}", ProposalType.values().length);
        } else {
            log.error("Proposal templates missing, generation will fail for these types: {}", missingTemplates);
        }

        List<String> unknownStages = properties.getEligibleStages().stream()
                .map(stage -> stage == null ? "" : stage.trim().toUpperCase().replace(' ', '_'))
                .filter(stage -> !DealStages.ALL.contains(stage))
                .toList();
        if (!unknownStages.isEmpty()) {
            log.error("proposal.eligible-stages names stages that do not exist: {}. Valid stages: {}",
                    unknownStages, DealStages.ORDERED);
        } else {
            log.info("Proposals can be generated from deal stages {}", properties.getEligibleStages());
        }
    }
}
