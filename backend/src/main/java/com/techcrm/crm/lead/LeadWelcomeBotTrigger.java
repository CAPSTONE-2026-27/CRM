package com.techcrm.crm.lead;

import com.techcrm.crm.sap.SapProcessProperties;
import com.techcrm.crm.sap.SapProcessTriggerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Welcomes a new lead by email.
 *
 * <b>After the commit, never during it.</b> A welcome email is sent to a real
 * person and cannot be recalled. Starting the automation inside the
 * transaction that created the lead would risk thanking someone for an enquiry
 * that then rolled back.
 *
 * <b>Off the representative's thread.</b> Saving a lead must not wait on a
 * cloud platform in another region, and an automation that cannot be started
 * is a logged warning rather than a failed save.
 *
 * <b>Only once.</b> Unlike the document bots, this is not idempotent — there
 * is no "already welcomed" for an email provider to return. It is published
 * from lead creation alone, which happens once per lead, and nothing else
 * republishes the event.
 */
@Component
public class LeadWelcomeBotTrigger {

    private static final Logger log = LoggerFactory.getLogger(LeadWelcomeBotTrigger.class);

    private final SapProcessTriggerClient sap;
    private final SapProcessProperties properties;

    public LeadWelcomeBotTrigger(SapProcessTriggerClient sap, SapProcessProperties properties) {
        this.sap = sap;
        this.properties = properties;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLeadCreated(LeadCreated event) {
        String definitionId = properties.getLeadWelcomeDefinitionId();
        if (definitionId == null || definitionId.isBlank()) {
            return;
        }

        if (event.email() == null || event.email().isBlank()) {
            // A pasted email always carries an address, but a lead typed in by
            // hand or imported from a spreadsheet need not. There is nobody to
            // welcome, and that is not a fault worth an error.
            log.info("Lead {} has no email address; no welcome was sent", event.leadId());
            return;
        }

        // Lower case keys, because the platform stores process input names that
        // way and matches them exactly — a camel-cased key is reported as a
        // missing property rather than being quietly normalised.
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("leadname", nullToEmpty(event.fullName()));
        context.put("leademail", event.email().trim());
        context.put("company", nullToEmpty(event.company()));
        context.put("source", nullToEmpty(event.sourceChannel()));

        String instanceId = sap.startProcess(definitionId, context);
        if (instanceId == null) {
            log.warn("Could not start the welcome bot for lead {}", event.leadId());
            return;
        }
        log.info("Started the welcome bot for lead {} as instance {}", event.leadId(), instanceId);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
