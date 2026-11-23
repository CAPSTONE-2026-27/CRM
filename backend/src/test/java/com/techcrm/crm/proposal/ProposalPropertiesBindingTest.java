package com.techcrm.crm.proposal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the YAML in application.yml actually binds to {@link ProposalProperties}.
 *
 * Worth its own test because the failure mode is silent: a mistyped key binds to
 * nothing, the field keeps its default, and the only symptom is a proposal
 * generated with terms nobody configured. Uses ApplicationContextRunner rather
 * than @SpringBootTest so no database is needed.
 */
class ProposalPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ProposalProperties.class);

    @Test
    void bindsEveryKeyTheShippedConfigurationSets() {
        runner.withPropertyValues(
                        "proposal.eligible-stages=PROPOSAL",
                        "proposal.default-validity-days=30",
                        "proposal.default-payment-terms=50% on acceptance.",
                        "proposal.default-delivery-timeline=Within 30 days.",
                        "proposal.implementation-value-threshold=2500000",
                        "proposal.templates.location=classpath:proposal-templates/")
                .run(context -> {
                    ProposalProperties properties = context.getBean(ProposalProperties.class);
                    assertThat(properties.getEligibleStages()).containsExactly("PROPOSAL");
                    assertThat(properties.getDefaultValidityDays()).isEqualTo(30);
                    assertThat(properties.getDefaultPaymentTerms()).isEqualTo("50% on acceptance.");
                    assertThat(properties.getDefaultDeliveryTimeline()).isEqualTo("Within 30 days.");
                    assertThat(properties.getImplementationValueThreshold())
                            .isEqualByComparingTo(new BigDecimal("2500000"));
                    assertThat(properties.getTemplates().getLocation())
                            .isEqualTo("classpath:proposal-templates/");
                });
    }

    /** A comma-separated string is how the list is written in YAML, and it has
     *  to arrive as several entries rather than one long one. */
    @Test
    void aCommaSeparatedStageListBindsAsSeveralStages() {
        runner.withPropertyValues("proposal.eligible-stages=PROPOSAL,NEGOTIATION")
                .run(context -> assertThat(context.getBean(ProposalProperties.class).getEligibleStages())
                        .containsExactly("PROPOSAL", "NEGOTIATION"));
    }

    /** Nothing configured must still be a usable module, not an empty one — the
     *  defaults are the shipped behaviour. */
    @Test
    void theDefaultsAreUsableWithNoConfigurationAtAll() {
        runner.run(context -> {
            ProposalProperties properties = context.getBean(ProposalProperties.class);
            assertThat(properties.getEligibleStages()).containsExactly("PROPOSAL");
            assertThat(properties.getDefaultValidityDays()).isPositive();
            assertThat(properties.getDefaultPaymentTerms()).isNotBlank();
            assertThat(properties.getTemplates().getLocation()).startsWith("classpath:");
        });
    }

    /** Per-type template overrides are a map keyed by ProposalType name. */
    @Test
    void aPerTypeTemplateOverrideBinds() {
        runner.withPropertyValues(
                        "proposal.templates.overrides.STANDARD_SALES_PROPOSAL=file:/opt/quote.docx")
                .run(context -> assertThat(context.getBean(ProposalProperties.class)
                        .getTemplates().getOverrides())
                        .containsEntry("STANDARD_SALES_PROPOSAL", "file:/opt/quote.docx"));
    }
}
