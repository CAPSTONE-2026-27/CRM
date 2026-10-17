package com.techcrm.crm.sap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the control room reports.
 *
 * The point of reading configuration rather than a table is that a bot shows
 * as active exactly when it can actually run — so these pin that a definition
 * id, and nothing else, is what decides.
 */
@DisplayName("the SAP automation registry")
class SapBotRegistryTest {

    private SapProcessProperties properties;
    private SapBotRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new SapProcessProperties();
        registry = new SapBotRegistry(properties);
    }

    @Test
    void listsEveryAutomationTheCrmCanStart() {
        assertThat(registry.all())
                .extracting(SapBotRegistry.BotSummary::name)
                .containsExactly(
                        "Lead validation bot",
                        "Lead welcome bot",
                        "Meeting scheduler bot",
                        "Deal meeting scheduler bot",
                        "Opportunity creation bot",
                        "Proposal generation bot",
                        "Contract generation bot");
    }

    /** Nothing is configured out of the box, and the page says so rather than
     *  implying the automations are running. */
    @Test
    void reportsNothingAsWiredUpUntilItIs() {
        assertThat(registry.all()).allMatch(bot -> !bot.configured());
    }

    @Test
    void reportsAnAutomationAsWiredUpOnceItHasADefinitionId() {
        properties.setContractDefinitionId("ap21.tenant.contractbot.contractBotProcess");

        var contract = registry.all().stream()
                .filter(bot -> bot.name().equals("Contract generation bot"))
                .findFirst().orElseThrow();

        assertThat(contract.configured()).isTrue();
        assertThat(contract.definitionId()).isEqualTo("ap21.tenant.contractbot.contractBotProcess");
    }

    /** Blank is how configuration says "not wired up", so it must not read as
     *  a definition id that happens to be empty. */
    @Test
    void treatsABlankDefinitionIdAsNotConfigured() {
        properties.setContractDefinitionId("   ");

        var contract = registry.all().stream()
                .filter(bot -> bot.name().equals("Contract generation bot"))
                .findFirst().orElseThrow();

        assertThat(contract.configured()).isFalse();
        assertThat(contract.definitionId()).isNull();
    }

    /** Each row says what a person would do to cause it, not what the code
     *  does when they have. */
    @Test
    void saysWhatMakesEachAutomationRun() {
        assertThat(registry.all())
                .allMatch(bot -> bot.trigger() != null && !bot.trigger().isBlank());
    }
}
