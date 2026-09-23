package com.techcrm.crm.contract.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractEmailTemplatesTest {

    @TempDir Path dir;
    ContractEmailTemplates templates;

    private static final Map<String, String> VALUES = Map.of(
            "signerName", "O'Brien & Sons",
            "contractNumber", "CTR-000005",
            "senderEmail", "sales@techcrm.example?x=1&y=2");

    @BeforeEach
    void setUp() throws IOException {
        MailjetProperties properties = new MailjetProperties();
        properties.getTemplates().setLocation(dir.toUri().toString());
        templates = new ContractEmailTemplates(properties, new DefaultResourceLoader());

        write(ContractEmailTemplates.SUBJECT, "Contract ${contractNumber} for ${signerName}\n");
        write(ContractEmailTemplates.HTML, "<p>Hello ${signerName}</p><a href=\"mailto:${senderEmail}\">Reply</a>");
        write(ContractEmailTemplates.TEXT, "Hello ${signerName}, reply to ${senderEmail}");
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void fillsEveryPlaceholder() {
        var rendered = templates.render(VALUES);

        assertThat(rendered.subject()).isEqualTo("Contract CTR-000005 for O'Brien & Sons");
        assertThat(rendered.text()).isEqualTo(
                "Hello O'Brien & Sons, reply to sales@techcrm.example?x=1&y=2");
    }

    /** Markup-safe in HTML, but untouched in the subject and the text body. */
    @Test
    void escapesValuesInTheHtmlBodyOnly() {
        var rendered = templates.render(VALUES);

        assertThat(rendered.html()).contains("Hello O&#39;Brien &amp; Sons");
        assertThat(rendered.html()).contains("href=\"mailto:sales@techcrm.example?x=1&amp;y=2\"");
        assertThat(rendered.subject()).doesNotContain("&amp;");
    }

    /** The requirement: wording can be changed while the backend is running. */
    @Test
    void anEditedTemplateIsUsedOnTheNextSendWithoutARestart() throws IOException {
        assertThat(templates.render(VALUES).subject()).startsWith("Contract CTR-000005");

        write(ContractEmailTemplates.SUBJECT, "Please sign ${contractNumber}");

        assertThat(templates.render(VALUES).subject()).isEqualTo("Please sign CTR-000005");
    }

    @Test
    void aMultiLineSubjectBecomesOneLine() throws IOException {
        write(ContractEmailTemplates.SUBJECT, "Contract\n  ${contractNumber}\n");

        assertThat(templates.render(VALUES).subject()).isEqualTo("Contract CTR-000005");
    }

    /** A typo stays visible in a test email rather than silently vanishing. */
    @Test
    void anUnknownPlaceholderIsLeftAsWritten() throws IOException {
        write(ContractEmailTemplates.TEXT, "Hello ${signerNmae}");

        assertThat(templates.render(VALUES).text()).isEqualTo("Hello ${signerNmae}");
    }

    @Test
    void aMissingTemplateIsAServerError() throws IOException {
        Files.delete(dir.resolve(ContractEmailTemplates.HTML));

        assertThatThrownBy(() -> templates.render(VALUES))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("contract-email.html");
    }

    /** The files shipped in the repository fill cleanly — no stray placeholder. */
    @Test
    void theShippedTemplatesUseOnlyKnownPlaceholders() {
        MailjetProperties properties = new MailjetProperties();
        properties.getTemplates().setLocation(Path.of("email-templates").toAbsolutePath().toUri().toString());
        var shipped = new ContractEmailTemplates(properties, new DefaultResourceLoader());

        var rendered = shipped.render(Map.of(
                "signerName", "Meera", "contractNumber", "CTR-000005", "contractTitle", "Standard Sales Agreement",
                "senderName", "Piyush", "senderEmail", "p@example.com"));

        assertThat(rendered.subject() + rendered.html() + rendered.text()).doesNotContain("${");
        assertThat(rendered.html()).contains("CTR-000005").contains("Meera");
        assertThat(rendered.text()).contains("CTR-000005").contains("Meera");
    }
}
