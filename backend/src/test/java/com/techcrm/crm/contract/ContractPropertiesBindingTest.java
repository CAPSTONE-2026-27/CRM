package com.techcrm.crm.contract;

import com.techcrm.crm.contract.email.MailjetProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the YAML in application.yml actually reaches these objects.
 *
 * Worth its own test because the failure mode is silent: a setting that does not
 * bind leaves the field on its Java default, and the only symptom is behaviour
 * nobody asked for — {@code eligible-stages} binding to nothing would make every
 * deal ineligible, with a 409 as the only clue.
 *
 * An {@link ApplicationContextRunner} rather than {@code @SpringBootTest}: this
 * needs no database, and the full context needs one.
 */
class ContractPropertiesBindingTest {

    /** Registers the same binding machinery Boot applies to these two beans in
     *  production, and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ContractProperties.class, MailjetProperties.class})
    static class BindingOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingOnly.class);

    @Test
    void bindsTheDefaultsShippedInApplicationYml() {
        runner.withPropertyValues(
                        "contract.eligible-stages=NEGOTIATION,CLOSED_WON",
                        "contract.default-term-months=12",
                        "contract.enterprise-value-threshold=2500000",
                        "contract.templates.location=classpath:contract-templates/",
                        "contract.storage.root=./var/contracts",
                        "contract.libreoffice.enabled=true",
                        "contract.libreoffice.path=soffice",
                        "contract.libreoffice.timeout-seconds=120")
                .run(context -> {
                    ContractProperties properties = context.getBean(ContractProperties.class);

                    // The comma-separated form is the one application.yml uses;
                    // if relaxed binding ever stopped splitting it, every deal
                    // would silently become ineligible.
                    assertThat(properties.getEligibleStages())
                            .containsExactly("NEGOTIATION", "CLOSED_WON");
                    assertThat(properties.getDefaultTermMonths()).isEqualTo(12);
                    assertThat(properties.getEnterpriseValueThreshold())
                            .isEqualByComparingTo(new BigDecimal("2500000"));
                    assertThat(properties.getTemplates().getLocation())
                            .isEqualTo("classpath:contract-templates/");
                    assertThat(properties.getStorage().getRoot()).isEqualTo("./var/contracts");
                    assertThat(properties.getLibreoffice().isEnabled()).isTrue();
                    assertThat(properties.getLibreoffice().getPath()).isEqualTo("soffice");
                    assertThat(properties.getLibreoffice().getTimeoutSeconds()).isEqualTo(120);
                });
    }

    /** The nested `libreoffice` group is the one relaxed binding is least
     *  obvious about, so it is pinned with an override too. */
    @Test
    void bindsTheNestedLibreOfficeGroupFromAnOverride() {
        runner.withPropertyValues(
                        "contract.libreoffice.enabled=false",
                        "contract.libreoffice.path=C:/Program Files/LibreOffice/program/soffice.exe",
                        "contract.libreoffice.timeout-seconds=45")
                .run(context -> {
                    var libreOffice = context.getBean(ContractProperties.class).getLibreoffice();

                    assertThat(libreOffice.isEnabled()).isFalse();
                    assertThat(libreOffice.getPath()).endsWith("soffice.exe");
                    assertThat(libreOffice.getTimeoutSeconds()).isEqualTo(45);
                });
    }

    @Test
    void bindsPerTypeTemplateOverrides() {
        runner.withPropertyValues(
                        "contract.templates.overrides.STANDARD_SALES_AGREEMENT=file:/opt/crm/sales.docx")
                .run(context -> assertThat(context.getBean(ContractProperties.class)
                        .getTemplates().getOverrides())
                        .containsEntry("STANDARD_SALES_AGREEMENT", "file:/opt/crm/sales.docx"));
    }

    /** Unconfigured is the default state and must be a clean "off": send-email
     *  answers 503 rather than calling Mailjet with empty credentials. */
    @Test
    void mailjetIsOffUntilBothKeysAndASenderAreSet() {
        runner.run(context ->
                assertThat(context.getBean(MailjetProperties.class).isConfigured()).isFalse());

        runner.withPropertyValues("mailjet.api-key=public", "mailjet.secret-key=secret")
                .run(context ->
                        assertThat(context.getBean(MailjetProperties.class).isConfigured()).isFalse());

        runner.withPropertyValues(
                        "mailjet.api-key=public",
                        "mailjet.secret-key=secret",
                        "mailjet.from-email=sales@techcrm.example")
                .run(context ->
                        assertThat(context.getBean(MailjetProperties.class).isConfigured()).isTrue());
    }

    /** The template location is what makes the email editable without a rebuild. */
    @Test
    void bindsTheTemplateLocation() {
        runner.withPropertyValues("mailjet.templates.location=file:/opt/crm/email-templates/")
                .run(context -> assertThat(context.getBean(MailjetProperties.class)
                        .getTemplates().getLocation())
                        .isEqualTo("file:/opt/crm/email-templates/"));
    }

    /** Nothing committed may carry a real credential; both default to empty so
     *  an unconfigured deployment fails loudly rather than half-working. */
    @Test
    void shipsNoCredentials() {
        runner.run(context -> {
            MailjetProperties properties = context.getBean(MailjetProperties.class);

            assertThat(properties.getApiKey()).isEmpty();
            assertThat(properties.getSecretKey()).isEmpty();
        });
    }
}
