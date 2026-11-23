package com.techcrm.crm.sap;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The automations this CRM knows how to start, and whether each is wired up.
 *
 * Read from configuration rather than from a table, because configuration is
 * what actually decides: a bot runs when its definition id is set and does
 * nothing when it is blank, whatever a registry row might claim. A page that
 * read a table could show a bot as registered while the backend had no way to
 * reach it.
 *
 * Deliberately not editable. These are deployed in SAP Build Process
 * Automation and pointed at from configuration — adding one here would create
 * a row that starts nothing.
 */
@Component
public class SapBotRegistry {

    private final SapProcessProperties properties;

    public SapBotRegistry(SapProcessProperties properties) {
        this.properties = properties;
    }

    /**
     * @param trigger what makes it run, in the words of the person who would
     *        cause it — not the name of a method
     * @param configured whether a definition id is set for it
     */
    public record BotSummary(
            String name,
            String trigger,
            String platform,
            boolean configured,
            String definitionId
    ) {
    }

    public List<BotSummary> all() {
        return List.of(
                bot("Lead validation bot",
                        "A representative saves a new lead",
                        properties.getLeadValidationDefinitionId()),
                bot("Lead welcome bot",
                        "A lead is created",
                        properties.getLeadWelcomeDefinitionId()),
                bot("Meeting scheduler bot",
                        "A meeting is booked with a lead",
                        properties.getMeetingSchedulerDefinitionId()),
                bot("Deal meeting scheduler bot",
                        "A meeting is booked about a deal",
                        properties.getDealMeetingSchedulerDefinitionId()),
                bot("Opportunity creation bot",
                        "A qualified lead reaches Opportunity",
                        properties.getOpportunityDefinitionId()),
                bot("Proposal generation bot",
                        "A deal reaches Proposal",
                        properties.getProposalDefinitionId()),
                bot("Contract generation bot",
                        "A deal reaches Contract",
                        properties.getContractDefinitionId()));
    }

    private BotSummary bot(String name, String trigger, String definitionId) {
        boolean configured = definitionId != null && !definitionId.isBlank();
        return new BotSummary(name, trigger, "SAP BPA", configured,
                configured ? definitionId : null);
    }
}
