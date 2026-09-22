package com.techcrm.crm.contract;

import com.techcrm.crm.contract.document.DocxGenerationService;
import com.techcrm.crm.contract.email.MailjetProperties;
import com.techcrm.crm.contract.template.ContractTemplateService;
import com.techcrm.crm.deal.DealStages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports what the contract module can and cannot do, once, at startup.
 *
 * Deliberately logs rather than fails. A missing LibreOffice or an unset
 * Mailjet key is a legitimate state for a developer machine, and refusing to
 * boot the whole CRM over it would be wrong. What is not acceptable is finding
 * out at 5pm, from a sales executive, that contracts have been silently
 * DOCX-only for a week — so each gap is stated plainly here, with the setting
 * that closes it.
 *
 * A misconfigured eligible-stages list is different: it is a typo, not a
 * deployment choice, and it would make every deal permanently ineligible with no
 * other symptom. That one is called out as an error.
 */
@Component
public class ContractStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(ContractStartupCheck.class);

    private final ContractProperties properties;
    private final ContractTemplateService templateService;
    private final MailjetProperties mailjetProperties;
    private final DocxGenerationService docxGenerationService;

    public ContractStartupCheck(ContractProperties properties,
                                ContractTemplateService templateService,
                                MailjetProperties mailjetProperties,
                                DocxGenerationService docxGenerationService) {
        this.properties = properties;
        this.templateService = templateService;
        this.mailjetProperties = mailjetProperties;
        this.docxGenerationService = docxGenerationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        List<String> missingTemplates = new ArrayList<>();
        for (ContractType type : ContractType.values()) {
            if (!templateService.isAvailable(type)) {
                missingTemplates.add(type.name() + " (" + templateService.templateKey(type) + ")");
            }
        }
        if (missingTemplates.isEmpty()) {
            log.info("Contract templates available: {}", ContractType.values().length);
            warmUpDocx4j();
        } else {
            log.error("Contract templates missing, generation will fail for these types: {}", missingTemplates);
        }

        List<String> unknownStages = properties.getEligibleStages().stream()
                .map(stage -> stage == null ? "" : stage.trim().toUpperCase().replace(' ', '_'))
                .filter(stage -> !DealStages.ALL.contains(stage))
                .toList();
        if (!unknownStages.isEmpty()) {
            log.error("contract.eligible-stages names stages that do not exist: {}. Valid stages: {}",
                    unknownStages, DealStages.ORDERED);
        } else {
            log.info("Contracts can be generated from deal stages {}", properties.getEligibleStages());
        }

        if (!properties.getLibreoffice().isEnabled()) {
            log.warn("contract.libreoffice.enabled=false - contracts will be generated as DOCX only, "
                    + "and cannot be emailed to the customer");
        }

        if (!mailjetProperties.isConfigured()) {
            log.warn("Mailjet is not configured (mailjet.api-key / mailjet.secret-key / mailjet.from-email) - "
                    + "POST /api/contracts/{id}/send-email will return 503");
        }
    }

    /**
     * Absorbs docx4j's first-call cost so a real contract does not.
     *
     * On its own thread, because it takes around twenty seconds and nothing else
     * about the CRM should wait for the contract module to get comfortable. A
     * daemon thread so it can never hold a shutdown open.
     */
    private void warmUpDocx4j() {
        // A platform thread, not a virtual one: this is a long CPU-bound stretch
        // of JAXB reflection, which would pin a carrier thread for its duration.
        Thread.ofPlatform().daemon().name("contract-docx4j-warmup").start(() -> {
            try {
                docxGenerationService.warmUp(templateService.load(ContractType.values()[0]));
            } catch (RuntimeException e) {
                log.warn("docx4j warm-up could not run", e);
            }
        });
    }
}
