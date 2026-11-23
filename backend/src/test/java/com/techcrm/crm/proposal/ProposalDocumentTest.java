package com.techcrm.crm.proposal;

import com.techcrm.crm.contract.ContractAssembly;
import com.techcrm.crm.contract.ContractLineItem;
import com.techcrm.crm.contract.ContractProperties;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.document.DocxGenerationService;
import com.techcrm.crm.contract.document.PdfConversionService;
import com.techcrm.crm.contract.document.PdfPageCount;
import com.techcrm.crm.proposal.template.ProposalTemplateService;
import jakarta.xml.bind.JAXBElement;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bundled proposal templates, rendered for real.
 *
 * Every other test in this module mocks the document layer. This one does not:
 * it loads the actual .docx files that ship in the jar, runs them through the
 * real docx4j renderer with real placeholder values, and reads the result back.
 * A template with a misspelled placeholder or a broken pricing row would pass
 * every mocked test and fail here — which is the point, because the first person
 * to see a wrong template otherwise is a customer.
 */
class ProposalDocumentTest {

    private final ProposalTemplateService templates =
            new ProposalTemplateService(new ProposalProperties(), new DefaultResourceLoader());
    private final DocxGenerationService generator = new DocxGenerationService();

    private byte[] render(ProposalType type, ProposalAssembly assembly) {
        Proposal proposal = ProposalFixtures.proposal();
        proposal.setProposalType(type);
        Map<String, String> placeholders = ProposalPlaceholders.build(proposal, assembly);
        return generator.generate(templates.load(type), placeholders, lineItems(assembly));
    }

    /** Every template must fill in, whichever one the resolver picks. */
    @Test
    void everyTemplateRendersWithNoPlaceholdersLeftBehind() {
        for (ProposalType type : ProposalType.values()) {
            String text = textOf(render(type, ProposalFixtures.assembly()));

            assertThat(text)
                    .as("%s still contains an unfilled placeholder", type)
                    .doesNotContain("{{")
                    .doesNotContain("}}");
        }
    }

    @Test
    void theCustomerAndThePriceReachTheDocument() {
        String text = textOf(render(ProposalType.STANDARD_SALES_PROPOSAL, ProposalFixtures.assembly()));

        assertThat(text).contains("PRP-000042");
        assertThat(text).contains("Northwind Traders Pvt Ltd");
        assertThat(text).contains("Asha Menon");
        assertThat(text).contains("Ravi Kulkarni");
        assertThat(text).contains("TechCRM Solutions");
        assertThat(text).contains("450,000.00");
        assertThat(text).contains("INR");
    }

    /** The offer's expiry is the whole difference between this document and a
     *  contract, so it has to be on the page. */
    @Test
    void theValidityIsPrinted() {
        var assembly = ProposalFixtures.assembly();
        String text = textOf(render(ProposalType.STANDARD_SALES_PROPOSAL, assembly));

        assertThat(text).contains(ProposalPlaceholders.formatDate(assembly.validUntil()));
        assertThat(text).contains("valid until");
    }

    /** The marker row is cloned once per line, and the marker itself is gone. */
    @Test
    void thePricingTableGrowsOneRowPerLine() {
        var assembly = ProposalFixtures.assembly(List.of(
                line(1, "Fleet Telematics Unit", "10.00", "15000.00", "150000.00"),
                line(2, "Installation", "10.00", "2000.00", "20000.00"),
                line(3, "Annual support", "1.00", "45000.00", "45000.00")));

        byte[] docx = render(ProposalType.STANDARD_SALES_PROPOSAL, assembly);

        // One header row plus one per line item.
        assertThat(pricingRowCount(docx)).isEqualTo(4);

        String text = textOf(docx);
        assertThat(text).contains("Installation");
        assertThat(text).contains("Annual support");
        assertThat(text).doesNotContain("{{item.");
    }

    /** A single line is the common case — the deal-value fallback. */
    @Test
    void asingleLineLeavesOneRow() {
        byte[] docx = render(ProposalType.PROFESSIONAL_SERVICES_PROPOSAL, ProposalFixtures.assembly(
                List.of(line(1, "Northwind ERP rollout", "1.00", "450000.00", "450000.00"))));

        assertThat(pricingRowCount(docx)).isEqualTo(2);
    }

    /**
     * The document is a real .docx that Word and LibreOffice can open, not just
     * bytes that happen to come out of the renderer.
     */
    @Test
    void theResultIsAReadableWordDocument() {
        for (ProposalType type : ProposalType.values()) {
            byte[] docx = render(type, ProposalFixtures.assembly());
            assertThat(load(docx).getMainDocumentPart()).isNotNull();
            assertThat(docx.length).isGreaterThan(4000);
        }
    }

    /**
     * The last step of the real pipeline, exercised through the real services.
     *
     * Skipped where LibreOffice is not installed — it is the one thing here that
     * needs it, and CI without it should still run everything above.
     */
    @Test
    @EnabledIf("libreOfficeAvailable")
    void convertsToAPdfWithLibreOffice() throws Exception {
        ContractProperties properties = new ContractProperties();
        properties.getLibreoffice().setPath(libreOfficePath());
        properties.getStorage().setRoot(
                Files.createTempDirectory("crm-proposal-pdf-test").toString());

        var storage = new DocumentStorageService(properties);
        byte[] docx = render(ProposalType.STANDARD_SALES_PROPOSAL, ProposalFixtures.assembly());

        Path temporary = storage.writeTemporary("PRP-000042.docx", docx);
        try {
            byte[] pdf = new PdfConversionService(properties).convertToPdf(temporary);

            assertThat(pdf).isNotEmpty();
            assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                    .as("LibreOffice must produce a real PDF")
                    .isEqualTo("%PDF-");
            assertThat(PdfPageCount.of(pdf))
                    .as("a generated proposal is at least one page and not absurdly long")
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

    /* --------------------------------------------------------------- helpers */

    private static ProposalAssembly.ResolvedLineItem line(
            int number, String description, String quantity, String unitPrice, String lineTotal) {
        return new ProposalAssembly.ResolvedLineItem(number, description,
                new BigDecimal(quantity), new BigDecimal(unitPrice), new BigDecimal(lineTotal),
                ProposalLineItem.Source.LEAD_PRODUCT);
    }

    /** The renderer is the contract module's, so proposal lines are adapted the
     *  same way {@code ProposalService} adapts them. */
    private static List<ContractAssembly.ResolvedLineItem> lineItems(ProposalAssembly assembly) {
        return assembly.lineItems().stream()
                .map(i -> new ContractAssembly.ResolvedLineItem(
                        i.lineNumber(), i.description(), i.quantity(), i.unitPrice(), i.lineTotal(),
                        i.source() == ProposalLineItem.Source.LEAD_PRODUCT
                                ? ContractLineItem.Source.LEAD_PRODUCT
                                : ContractLineItem.Source.DEAL_VALUE))
                .toList();
    }

    private static WordprocessingMLPackage load(byte[] docx) {
        try {
            return WordprocessingMLPackage.load(new ByteArrayInputStream(docx));
        } catch (Exception e) {
            throw new AssertionError("Generated document is not a readable .docx", e);
        }
    }

    private static String textOf(byte[] docx) {
        StringBuilder sb = new StringBuilder();
        for (Text text : collect(load(docx).getMainDocumentPart().getJaxbElement(), Text.class)) {
            sb.append(text.getValue() == null ? "" : text.getValue());
        }
        return sb.toString();
    }

    private static int pricingRowCount(byte[] docx) {
        List<Tbl> tables = collect(load(docx).getMainDocumentPart().getJaxbElement(), Tbl.class);
        assertThat(tables).as("pricing table").hasSize(1);
        return collect(tables.get(0), Tr.class).size();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> collect(Object node, Class<T> type) {
        List<T> found = new ArrayList<>();
        walk(node, type, found);
        return found;
    }

    private static <T> void walk(Object node, Class<T> type, List<T> found) {
        if (node instanceof JAXBElement<?> element) {
            node = element.getValue();
        }
        if (node == null) {
            return;
        }
        if (type.isInstance(node)) {
            found.add(type.cast(node));
        }
        if (node instanceof org.docx4j.wml.ContentAccessor accessor) {
            for (Object child : accessor.getContent()) {
                walk(child, type, found);
            }
        } else if (node instanceof List<?> list) {
            for (Object child : list) {
                walk(child, type, found);
            }
        }
    }
}
