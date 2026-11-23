package com.techcrm.crm.contract.document;

import com.techcrm.crm.contract.ContractAssembly;
import com.techcrm.crm.contract.ContractFixtures;
import com.techcrm.crm.contract.ContractPlaceholders;
import com.techcrm.crm.contract.ContractProperties;
import com.techcrm.crm.contract.ContractType;
import com.techcrm.crm.contract.template.ContractTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PdfPageCountTest {

    @Test
    void readsTheCountFromTheRootPageTree() {
        String pdf = """
                %PDF-1.7
                1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
                2 0 obj << /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >> endobj
                3 0 obj << /Type /Page /Parent 2 0 R >> endobj
                4 0 obj << /Type /Page /Parent 2 0 R >> endobj
                5 0 obj << /Type /Page /Parent 2 0 R >> endobj
                """;

        assertThat(PdfPageCount.of(pdf.getBytes(StandardCharsets.ISO_8859_1))).isEqualTo(3);
    }

    /** /Pages must not be mistaken for a page. */
    @Test
    void doesNotCountThePageTreeNodeAsAPage() {
        String pdf = "%PDF-1.7\n1 0 obj << /Type /Pages /Kids [2 0 R] /Count 1 >> endobj\n"
                + "2 0 obj << /Type /Page >> endobj\n";

        assertThat(PdfPageCount.of(pdf.getBytes(StandardCharsets.ISO_8859_1))).isEqualTo(1);
    }

    /** A nested page tree carries its own /Count, so the leaf objects are the
     *  trustworthy signal when the two disagree. */
    @Test
    void prefersTheLeafCountOverANestedTreesCount() {
        String pdf = """
                %PDF-1.7
                1 0 obj << /Type /Pages /Kids [2 0 R] /Count 2 >> endobj
                2 0 obj << /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >> endobj
                3 0 obj << /Type /Page >> endobj
                4 0 obj << /Type /Page >> endobj
                5 0 obj << /Type /Page >> endobj
                """;

        assertThat(PdfPageCount.of(pdf.getBytes(StandardCharsets.ISO_8859_1))).isEqualTo(3);
    }

    /** Reporting UNKNOWN is what lets the caller fall back to page 1 rather than
     *  sending a field to a page that may not exist. */
    @Test
    void reportsUnknownRatherThanGuessing() {
        assertThat(PdfPageCount.of(null)).isEqualTo(PdfPageCount.UNKNOWN);
        assertThat(PdfPageCount.of(new byte[0])).isEqualTo(PdfPageCount.UNKNOWN);
        assertThat(PdfPageCount.of("not a pdf at all".getBytes(StandardCharsets.ISO_8859_1)))
                .isEqualTo(PdfPageCount.UNKNOWN);
    }

    @Test
    void survivesBinaryStreamContent() {
        byte[] binary = new byte[2048];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) (i % 256);
        }
        String head = "%PDF-1.7\n1 0 obj << /Type /Pages /Count 2 >> endobj\n"
                + "2 0 obj << /Type /Page >> endobj\n3 0 obj << /Type /Page >> endobj\nstream\n";

        byte[] pdf = new byte[head.length() + binary.length];
        System.arraycopy(head.getBytes(StandardCharsets.ISO_8859_1), 0, pdf, 0, head.length());
        System.arraycopy(binary, 0, pdf, head.length(), binary.length);

        assertThat(PdfPageCount.of(pdf)).isEqualTo(2);
    }

    /**
     * The case that actually matters: a real PDF produced by this application's
     * own pipeline. Skipped where LibreOffice is not installed, since it is the
     * one thing here that needs it.
     */
    @Test
    @EnabledIf("libreOfficeAvailable")
    void countsPagesInAPdfThisApplicationGenerated() {
        ContractProperties properties = new ContractProperties();
        properties.getLibreoffice().setPath(libreOfficePath());
        properties.getStorage().setRoot(System.getProperty("java.io.tmpdir") + "/crm-pagecount-test");

        var templates = new ContractTemplateService(properties, new DefaultResourceLoader());
        var storage = new DocumentStorageService(properties);
        ContractAssembly assembly = ContractFixtures.assembly();

        byte[] docx = new DocxGenerationService().generate(
                templates.load(ContractType.PROFESSIONAL_SERVICES_AGREEMENT),
                ContractPlaceholders.build(ContractFixtures.contract(), assembly),
                assembly.lineItems());

        Path temporary = storage.writeTemporary("page-count-probe.docx", docx);
        try {
            byte[] pdf = new PdfConversionService(properties).convertToPdf(temporary);
            assertThat(PdfPageCount.of(pdf))
                    .as("a generated contract is at least one page and not absurdly long")
                    .isBetween(1, 10);
        } finally {
            storage.deleteTemporary(temporary);
        }
    }

    static boolean libreOfficeAvailable() {
        return Files.isRegularFile(Path.of(libreOfficePath()));
    }

    private static String libreOfficePath() {
        String configured = System.getProperty("libreoffice.path");
        if (configured != null) {
            return configured;
        }
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? "C:/Program Files/LibreOffice/program/soffice.exe"
                : "/usr/bin/soffice";
    }
}
