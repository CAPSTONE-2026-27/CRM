package com.techcrm.crm.contract.document;

import com.techcrm.crm.contract.ContractAssembly;
import com.techcrm.crm.contract.ContractFixtures;
import com.techcrm.crm.contract.ContractLineItem;
import com.techcrm.crm.contract.ContractPlaceholders;
import com.techcrm.crm.contract.ContractProperties;
import com.techcrm.crm.contract.ContractType;
import com.techcrm.crm.contract.template.ContractTemplateService;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.ContentAccessor;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rendering step, against the real bundled templates.
 *
 * No mocks: the whole point of these is that a genuine .docx round-trips through
 * docx4j with its placeholders filled and its formatting intact, which a fake
 * template would not prove.
 */
class DocxGenerationServiceTest {

    DocxGenerationService service;
    ContractTemplateService templates;

    @BeforeEach
    void setUp() {
        service = new DocxGenerationService();
        templates = new ContractTemplateService(new ContractProperties(), new DefaultResourceLoader());
    }

    private Map<String, String> placeholders() {
        return ContractPlaceholders.build(ContractFixtures.contract(), ContractFixtures.assembly());
    }

    @ParameterizedTest
    @EnumSource(ContractType.class)
    void everyTemplateRendersWithNothingLeftUnresolved(ContractType type) {
        byte[] docx = service.generate(templates.load(type), placeholders(),
                ContractFixtures.assembly().lineItems());

        String text = textOf(docx);
        assertThat(text)
                .as("unresolved placeholder left in %s", type)
                .doesNotContain("{{").doesNotContain("}}");
        assertThat(text).contains("CTR-000042");
        assertThat(text).contains("Northwind Traders Pvt Ltd");
    }

    @Test
    void fillsTheCustomerAndCommercialTermsIntoTheDocument() {
        byte[] docx = service.generate(
                templates.load(ContractType.STANDARD_SALES_AGREEMENT),
                placeholders(), ContractFixtures.assembly().lineItems());

        String text = textOf(docx);
        assertThat(text).contains("Asha Menon");
        assertThat(text).contains("asha.menon@northwind.example");
        assertThat(text).contains("Ravi Kulkarni");
        assertThat(text).contains("OPP-000031");
        assertThat(text).contains("450,000.00");
        assertThat(text).contains("01 September 2026");
    }

    @Test
    void clonesTheScheduleRowOncePerLineItem() {
        List<ContractAssembly.ResolvedLineItem> items = List.of(
                item(1, "Fleet Telematics Unit", "30", "15000.00", "450000.00"),
                item(2, "Installation service", "30", "1200.00", "36000.00"),
                item(3, "Annual support", "1", "90000.00", "90000.00"));

        byte[] docx = service.generate(
                templates.load(ContractType.STANDARD_SALES_AGREEMENT), placeholders(), items);

        String text = textOf(docx);
        assertThat(text).contains("Fleet Telematics Unit")
                .contains("Installation service")
                .contains("Annual support")
                .contains("1,200.00")
                .contains("90,000.00");

        // Header row plus one per item, and the marker row itself gone.
        assertThat(scheduleRowCount(docx)).isEqualTo(4);
        assertThat(text).doesNotContain("{{item.");
    }

    @Test
    void aSingleLineItemLeavesASingleRow() {
        byte[] docx = service.generate(
                templates.load(ContractType.STANDARD_SALES_AGREEMENT),
                placeholders(), ContractFixtures.assembly().lineItems());

        assertThat(scheduleRowCount(docx)).isEqualTo(2);
    }

    /**
     * The case a naive per-run replace gets wrong. Word splits a typed
     * placeholder across runs for reasons the author never sees — a spell-check
     * boundary, a tracked change — and the document then ships with the braces
     * still in it.
     */
    @Test
    void substitutesAPlaceholderThatWordSplitAcrossThreeRuns() {
        P paragraph = paragraphOfRuns("Between ", "{{cust", "omerNa", "me}}", " and us");

        service.substitute(paragraph, Map.of("customerName", "Asha Menon"), true);

        assertThat(textOf(paragraph)).isEqualTo("Between Asha Menon and us");
    }

    @Test
    void substitutesTwoPlaceholdersInOneParagraphWithoutCorruptingEitherOffset() {
        P paragraph = paragraphOfRuns("{{companyName}} of {{companyAddress}}, signed");

        Map<String, String> values = new LinkedHashMap<>();
        values.put("companyName", "Northwind");
        values.put("companyAddress", "MG Road");
        service.substitute(paragraph, values, true);

        assertThat(textOf(paragraph)).isEqualTo("Northwind of MG Road, signed");
    }

    /** Runs the placeholder did not touch must keep their own formatting, which
     *  means keeping their own text nodes rather than being collapsed into the
     *  first one. */
    @Test
    void leavesUntouchedRunsAlone() {
        P paragraph = paragraphOfRuns("Total: ", "{{totalAmount}}", " (inclusive)");

        service.substitute(paragraph, Map.of("totalAmount", "450,000.00"), true);

        List<Text> texts = collect(paragraph, Text.class);
        assertThat(texts).hasSize(3);
        assertThat(texts.get(0).getValue()).isEqualTo("Total: ");
        assertThat(texts.get(1).getValue()).isEqualTo("450,000.00");
        assertThat(texts.get(2).getValue()).isEqualTo(" (inclusive)");
    }

    @Test
    void blanksAPlaceholderNothingCanFillRatherThanPrintingItToTheCustomer() {
        P paragraph = paragraphOfRuns("VAT: {{customerVatNumber}}.");

        service.substitute(paragraph, Map.of(), true);

        assertThat(textOf(paragraph)).isEqualTo("VAT: .");
    }

    /** The intermediate pass must not consume placeholders it does not own — the
     *  schedule row is substituted before the document-wide pass runs. */
    @Test
    void anIntermediatePassLeavesPlaceholdersItHasNoValueFor() {
        P paragraph = paragraphOfRuns("{{item.description}} priced in {{currency}}");

        service.substitute(paragraph, Map.of("item.description", "Telematics Unit"), false);

        assertThat(textOf(paragraph)).isEqualTo("Telematics Unit priced in {{currency}}");
    }

    /** A DOCX text node cannot hold a newline, so a multi-line billing address
     *  has to become real line breaks rather than one run-together line. */
    @Test
    void turnsAMultiLineAddressIntoLineBreaks() {
        byte[] docx = service.generate(
                templates.load(ContractType.STANDARD_SALES_AGREEMENT),
                placeholders(), ContractFixtures.assembly().lineItems());

        String text = textOf(docx);
        assertThat(text).contains("4th Floor, Prestige Tower");
        assertThat(text).contains("Bengaluru 560001");
        // The newline became a <w:br/>, so no text node still carries one.
        assertThat(text).doesNotContain("\n");
        assertThat(breakCount(docx)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theOutputIsAValidDocxThatDocx4jCanReopen() {
        byte[] docx = service.generate(
                templates.load(ContractType.PROFESSIONAL_SERVICES_AGREEMENT),
                placeholders(), ContractFixtures.assembly().lineItems());

        assertThat(new String(docx, 0, 2)).isEqualTo("PK");
        assertThat(load(docx).getMainDocumentPart()).isNotNull();
    }

    /* ------------------------------------------------------------- helpers */

    private static ContractAssembly.ResolvedLineItem item(
            int line, String description, String qty, String unit, String total) {
        return new ContractAssembly.ResolvedLineItem(line, description,
                new BigDecimal(qty), new BigDecimal(unit), new BigDecimal(total),
                ContractLineItem.Source.LEAD_PRODUCT);
    }

    private static P paragraphOfRuns(String... runTexts) {
        ObjectFactory factory = new ObjectFactory();
        P paragraph = factory.createP();
        for (String runText : runTexts) {
            R run = factory.createR();
            Text text = factory.createText();
            text.setValue(runText);
            run.getContent().add(factory.createRT(text));
            paragraph.getContent().add(run);
        }
        return paragraph;
    }

    private static WordprocessingMLPackage load(byte[] docx) {
        try {
            return WordprocessingMLPackage.load(new ByteArrayInputStream(docx));
        } catch (Exception e) {
            throw new AssertionError("Generated document is not a readable .docx", e);
        }
    }

    private static String textOf(byte[] docx) {
        return textOf(load(docx).getMainDocumentPart().getJaxbElement());
    }

    private static String textOf(Object root) {
        StringBuilder sb = new StringBuilder();
        for (Text text : collect(root, Text.class)) {
            sb.append(text.getValue() == null ? "" : text.getValue());
        }
        return sb.toString();
    }

    private static int scheduleRowCount(byte[] docx) {
        Object root = load(docx).getMainDocumentPart().getJaxbElement();
        List<Tbl> tables = collect(root, Tbl.class);
        assertThat(tables).as("schedule table").hasSize(1);
        return collect(tables.get(0), Tr.class).size();
    }

    private static int breakCount(byte[] docx) {
        Object root = load(docx).getMainDocumentPart().getJaxbElement();
        return collect(root, org.docx4j.wml.Br.class).size();
    }

    private static <T> List<T> collect(Object root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collectInto(root, type, found);
        return found;
    }

    private static <T> void collectInto(Object node, Class<T> type, List<T> found) {
        Object unwrapped = XmlUtils.unwrap(node);
        if (type.isInstance(unwrapped)) {
            found.add(type.cast(unwrapped));
        }
        if (unwrapped instanceof ContentAccessor accessor) {
            for (Object child : accessor.getContent()) {
                collectInto(child, type, found);
            }
        }
    }
}
