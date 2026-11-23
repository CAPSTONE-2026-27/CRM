package com.techcrm.crm.proposal.template;

import com.techcrm.crm.proposal.ProposalAssembly;
import com.techcrm.crm.proposal.ProposalFixtures;
import com.techcrm.crm.proposal.ProposalLineItem;
import com.techcrm.crm.proposal.ProposalProperties;
import com.techcrm.crm.proposal.ProposalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Template selection, and that the bundled templates are actually there. */
class ProposalTemplateServiceTest {

    ProposalProperties properties;
    ProposalTemplateService service;

    @BeforeEach
    void setUp() {
        properties = new ProposalProperties();
        service = new ProposalTemplateService(properties, new DefaultResourceLoader());
    }

    private static ProposalAssembly withProduct(String amount) {
        return ProposalFixtures.assembly(List.of(new ProposalAssembly.ResolvedLineItem(
                1, "Fleet Telematics Unit", new BigDecimal("1.00"),
                new BigDecimal(amount), new BigDecimal(amount),
                ProposalLineItem.Source.LEAD_PRODUCT)));
    }

    private static ProposalAssembly withoutProduct(String amount) {
        return ProposalFixtures.assembly(List.of(new ProposalAssembly.ResolvedLineItem(
                1, "Northwind ERP rollout", new BigDecimal("1.00"),
                new BigDecimal(amount), new BigDecimal(amount),
                ProposalLineItem.Source.DEAL_VALUE)));
    }

    @Test
    void aModestProductSaleIsAStandardQuotation() {
        assertThat(service.resolve(withProduct("450000.00"), null))
                .isEqualTo(ProposalType.STANDARD_SALES_PROPOSAL);
    }

    @Test
    void aLargeProductSaleBecomesAnImplementationProposal() {
        assertThat(service.resolve(withProduct("5000000.00"), null))
                .isEqualTo(ProposalType.SOFTWARE_IMPLEMENTATION_PROPOSAL);
    }

    @Test
    void theValueThresholdIsInclusiveAndConfigurable() {
        properties.setImplementationValueThreshold(new BigDecimal("450000.00"));

        assertThat(service.resolve(withProduct("450000.00"), null))
                .isEqualTo(ProposalType.SOFTWARE_IMPLEMENTATION_PROPOSAL);
    }

    /**
     * The product test comes before the value test on purpose: a large services
     * engagement is still a services engagement, and printing a unit-price
     * schedule for it would leave the quantity column meaningless.
     */
    @Test
    void aLargeServicesEngagementIsStillAServicesProposal() {
        assertThat(service.resolve(withoutProduct("9000000.00"), null))
                .isEqualTo(ProposalType.PROFESSIONAL_SERVICES_PROPOSAL);
    }

    @Test
    void anExplicitTypeOverridesTheResolver() {
        assertThat(service.resolve(withProduct("450000.00"), "PROFESSIONAL_SERVICES_PROPOSAL"))
                .isEqualTo(ProposalType.PROFESSIONAL_SERVICES_PROPOSAL);
    }

    /** Callers send these in whatever shape their platform produces, so spacing
     *  and hyphens are normalised rather than rejected. */
    @Test
    void anExplicitTypeIsNormalisedBeforeMatching() {
        assertThat(service.resolve(withProduct("1.00"), "standard sales proposal"))
                .isEqualTo(ProposalType.STANDARD_SALES_PROPOSAL);
        assertThat(service.resolve(withProduct("1.00"), "  Standard-Sales-Proposal  "))
                .isEqualTo(ProposalType.STANDARD_SALES_PROPOSAL);
    }

    /** A caller's typo is a 400 that names the valid options, not a 500. */
    @Test
    void anUnknownTypeIsRejectedWithTheListOfKnownOnes() {
        assertThatThrownBy(() -> service.resolve(withProduct("1.00"), "MASTER_SERVICES_AGREEMENT"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("STANDARD_SALES_PROPOSAL");
    }

    @Test
    void theTemplateKeyIsTheLocationTheDocumentWasRenderedFrom() {
        assertThat(service.templateKey(ProposalType.STANDARD_SALES_PROPOSAL))
                .isEqualTo("classpath:proposal-templates/standard-sales-proposal.docx");
    }

    @Test
    void aTrailingSlashInTheLocationIsNotDoubled() {
        properties.getTemplates().setLocation("classpath:proposal-templates");

        assertThat(service.templateKey(ProposalType.STANDARD_SALES_PROPOSAL))
                .isEqualTo("classpath:proposal-templates/standard-sales-proposal.docx");
    }

    @Test
    void aPerTypeOverrideReplacesTheDefaultFile() {
        properties.getTemplates().setOverrides(
                Map.of("STANDARD_SALES_PROPOSAL", "file:/opt/crm/custom-quote.docx"));

        assertThat(service.templateKey(ProposalType.STANDARD_SALES_PROPOSAL))
                .isEqualTo("file:/opt/crm/custom-quote.docx");
    }

    /** The check the startup report runs, so a missing template is found on boot
     *  rather than by the first sales executive to click Generate. */
    @Test
    void everyBundledTemplateIsPresentAndReadable() {
        for (ProposalType type : ProposalType.values()) {
            assertThat(service.isAvailable(type))
                    .as("%s template is missing from the jar", type)
                    .isTrue();
            assertThat(service.load(type))
                    .as("%s template is empty", type)
                    .isNotEmpty();
        }
    }

    /** A missing template is a server misconfiguration, not a bad request. */
    @Test
    void aMissingTemplateIsA500NotA400() {
        properties.getTemplates().setLocation("classpath:no-such-directory/");

        assertThatThrownBy(() -> service.load(ProposalType.STANDARD_SALES_PROPOSAL))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("500");
    }

    /** Templates are read once and kept, keyed by resolved location — so
     *  repointing a type at a different file misses the cache rather than
     *  serving the old wording. */
    @Test
    void templatesAreCachedByLocationNotByType() {
        byte[] first = service.load(ProposalType.STANDARD_SALES_PROPOSAL);
        byte[] second = service.load(ProposalType.STANDARD_SALES_PROPOSAL);

        assertThat(second).isSameAs(first);
    }
}
