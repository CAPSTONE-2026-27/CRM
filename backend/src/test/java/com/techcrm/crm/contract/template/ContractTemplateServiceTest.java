package com.techcrm.crm.contract.template;

import com.techcrm.crm.contract.ContractAssembly;
import com.techcrm.crm.contract.ContractFixtures;
import com.techcrm.crm.contract.ContractLineItem;
import com.techcrm.crm.contract.ContractProperties;
import com.techcrm.crm.contract.ContractType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Template selection, and that all three bundled templates are really there. */
class ContractTemplateServiceTest {

    ContractProperties properties;
    ContractTemplateService service;

    @BeforeEach
    void setUp() {
        properties = new ContractProperties();
        service = new ContractTemplateService(properties, new DefaultResourceLoader());
    }

    private static ContractAssembly withProduct(String amount) {
        return ContractFixtures.assembly(List.of(new ContractAssembly.ResolvedLineItem(
                1, "Fleet Telematics Unit", new BigDecimal("30.00"),
                new BigDecimal(amount), new BigDecimal(amount),
                ContractLineItem.Source.LEAD_PRODUCT)));
    }

    private static ContractAssembly withoutProduct(String amount) {
        return ContractFixtures.assembly(List.of(new ContractAssembly.ResolvedLineItem(
                1, "Northwind ERP rollout", BigDecimal.ONE,
                new BigDecimal(amount), new BigDecimal(amount),
                ContractLineItem.Source.DEAL_VALUE)));
    }

    @Test
    void anExplicitTypeWinsOverTheHeuristic() {
        assertThat(service.resolve(withProduct("100.00"), "PROFESSIONAL_SERVICES_AGREEMENT"))
                .isEqualTo(ContractType.PROFESSIONAL_SERVICES_AGREEMENT);
    }

    @Test
    void anExplicitTypeIsCaseAndSeparatorInsensitive() {
        assertThat(service.resolve(withProduct("100.00"), "enterprise subscription agreement"))
                .isEqualTo(ContractType.ENTERPRISE_SUBSCRIPTION_AGREEMENT);
        assertThat(service.resolve(withProduct("100.00"), "enterprise-subscription-agreement"))
                .isEqualTo(ContractType.ENTERPRISE_SUBSCRIPTION_AGREEMENT);
    }

    @Test
    void anUnknownTypeIsRejectedWithTheListOfKnownOnes() {
        assertThatThrownBy(() -> service.resolve(withProduct("100.00"), "MSA"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown contract type")
                .hasMessageContaining("STANDARD_SALES_AGREEMENT");
    }

    /** No product means nothing discrete is being shipped, so the schedule's
     *  quantity column would be meaningless on a sales template. */
    @Test
    void aDealWithNoProductBecomesAServicesAgreementHoweverLarge() {
        assertThat(service.resolve(withoutProduct("50000000.00"), null))
                .isEqualTo(ContractType.PROFESSIONAL_SERVICES_AGREEMENT);
    }

    @Test
    void aProductSaleBelowTheThresholdIsAStandardSale() {
        assertThat(service.resolve(withProduct("450000.00"), null))
                .isEqualTo(ContractType.STANDARD_SALES_AGREEMENT);
    }

    @Test
    void aProductSaleAtOrAboveTheThresholdIsAnEnterpriseSubscription() {
        assertThat(service.resolve(withProduct("2500000.00"), null))
                .isEqualTo(ContractType.ENTERPRISE_SUBSCRIPTION_AGREEMENT);
        assertThat(service.resolve(withProduct("2500001.00"), null))
                .isEqualTo(ContractType.ENTERPRISE_SUBSCRIPTION_AGREEMENT);
    }

    @Test
    void theThresholdIsConfigurable() {
        properties.setEnterpriseValueThreshold(new BigDecimal("100000"));

        assertThat(service.resolve(withProduct("450000.00"), null))
                .isEqualTo(ContractType.ENTERPRISE_SUBSCRIPTION_AGREEMENT);
    }

    @ParameterizedTest
    @EnumSource(ContractType.class)
    void everyTypeHasABundledTemplateThatLoads(ContractType type) {
        assertThat(service.isAvailable(type)).as("template for %s", type).isTrue();

        byte[] bytes = service.load(type);
        // A .docx is a zip; "PK" is the local file header signature. Cheap proof
        // that what shipped is a real Word document and not a stub.
        assertThat(bytes).hasSizeGreaterThan(1000);
        assertThat(new String(bytes, 0, 2)).isEqualTo("PK");
    }

    @Test
    void theTemplateKeyRecordsWhereTheDocumentCameFrom() {
        assertThat(service.templateKey(ContractType.STANDARD_SALES_AGREEMENT))
                .isEqualTo("classpath:contract-templates/standard-sales-agreement.docx");
    }

    @Test
    void aPerTypeOverrideReplacesTheBundledTemplate() {
        properties.getTemplates().setOverrides(
                Map.of("STANDARD_SALES_AGREEMENT", "classpath:contract-templates/professional-services-agreement.docx"));

        assertThat(service.templateKey(ContractType.STANDARD_SALES_AGREEMENT))
                .endsWith("professional-services-agreement.docx");
    }

    @Test
    void aMissingTemplateIsAServerFaultNotACallerFault() {
        properties.getTemplates().setLocation("classpath:no-such-directory/");

        assertThat(service.isAvailable(ContractType.STANDARD_SALES_AGREEMENT)).isFalse();
        assertThatThrownBy(() -> service.load(ContractType.STANDARD_SALES_AGREEMENT))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(500);
    }
}
