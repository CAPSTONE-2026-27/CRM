package com.techcrm.crm.contract.document;

import com.techcrm.crm.contract.ContractAssembly;
import com.techcrm.crm.contract.ContractPlaceholders;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.wml.Br;
import org.docx4j.wml.ContentAccessor;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a contract template into a finished DOCX.
 *
 * Two things make this more than a string replace.
 *
 * <b>Word splits placeholders across runs.</b> A template author types
 * {@code {{customerName}}} and Word may store it as three runs — because of a
 * spell-check boundary, a tracked revision, or a stray formatting toggle. A
 * naive per-run replace silently misses those and ships a contract with the
 * literal braces in it. So substitution happens against a paragraph's whole
 * concatenated text, and the result is written back run by run: the run holding
 * a placeholder's first character receives the value, the runs it bled into are
 * emptied, and every run the placeholder did not touch is left exactly as it
 * was. That is what preserves a bold label sitting next to a plain value.
 *
 * <b>The schedule is a real table.</b> A marker row containing
 * {@code {{item.*}}} placeholders is cloned once per line item and the original
 * removed, so the table keeps the template's borders, shading and column widths
 * rather than being rebuilt in code.
 *
 * Nothing here writes XML as text: values go through the OpenXML object model,
 * so an ampersand in a company name cannot corrupt the document.
 */
@Service
public class DocxGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DocxGenerationService.class);

    /** Non-greedy, and deliberately permissive about the name so that an
     *  unresolved-but-well-formed placeholder is still recognised and stripped
     *  rather than being printed to the customer. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    /** The row of the schedule table that gets cloned per line item. */
    private static final String LINE_ITEM_MARKER = "{{item.";

    private final ObjectFactory factory = new ObjectFactory();

    /**
     * Pays docx4j's one-off initialisation cost up front.
     *
     * The first {@link #generate} call in a JVM spends around twenty seconds
     * building the JAXB context before it reads a byte of the template — the
     * "package read; elapsed time" docx4j logs is almost entirely that, not the
     * 7 KB file. Every later call is fast.
     *
     * Left where it lands, that cost is paid by whichever contract happens to be
     * generated first, stretching one request past a minute and putting the
     * database write at the end of it outside the lifetime of a pooled
     * connection. Absorbing it at startup keeps the first real request the same
     * length as every other.
     *
     * Best-effort by design: failing to warm a cache must never stop the
     * application from starting.
     */
    public void warmUp(byte[] templateBytes) {
        long start = System.currentTimeMillis();
        try {
            WordprocessingMLPackage.load(new ByteArrayInputStream(templateBytes));
            log.info("docx4j warmed up in {} ms; the first contract will not pay this cost",
                    System.currentTimeMillis() - start);
        } catch (Docx4JException | RuntimeException e) {
            log.warn("docx4j warm-up failed; the first contract generated will be slower", e);
        }
    }

    /**
     * @param templateBytes  the .docx template, as loaded by ContractTemplateService
     * @param placeholders   scalar values, from {@link ContractPlaceholders#build}
     * @param lineItems      the schedule; may be empty, in which case the marker row is dropped
     * @return the rendered .docx
     */
    public byte[] generate(byte[] templateBytes,
                           Map<String, String> placeholders,
                           List<ContractAssembly.ResolvedLineItem> lineItems) {

        try {
            WordprocessingMLPackage pkg =
                    WordprocessingMLPackage.load(new ByteArrayInputStream(templateBytes));

            // Headers and footers carry the contract number and page furniture,
            // so they are substituted too — a document whose body is filled in
            // but whose footer still reads {{contractId}} is not finished.
            for (Object part : targetParts(pkg)) {
                expandLineItemTables(part, lineItems);
                substitute(part, placeholders, true);
                expandLineBreaks(part);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pkg.save(out);
            return out.toByteArray();

        } catch (Docx4JException e) {
            log.error("Contract DOCX generation failed", e);
            throw new ContractDocumentException("Contract document could not be generated", e);
        }
    }

    /** The main document plus every header and footer, as the JAXB roots to walk. */
    private List<Object> targetParts(WordprocessingMLPackage pkg) {
        List<Object> parts = new ArrayList<>();
        parts.add(pkg.getMainDocumentPart().getJaxbElement());
        for (Part part : pkg.getParts().getParts().values()) {
            if (part instanceof HeaderPart header) {
                parts.add(header.getJaxbElement());
            } else if (part instanceof FooterPart footer) {
                parts.add(footer.getJaxbElement());
            }
        }
        return parts;
    }

    /* ------------------------------------------------------- line item table */

    private void expandLineItemTables(Object root, List<ContractAssembly.ResolvedLineItem> lineItems) {
        for (Tbl table : collect(root, Tbl.class)) {
            List<Object> rows = table.getContent();
            int markerIndex = indexOfMarkerRow(rows);
            if (markerIndex < 0) {
                continue;
            }

            Tr markerRow = (Tr) XmlUtils.unwrap(rows.get(markerIndex));
            rows.remove(markerIndex);

            int insertAt = markerIndex;
            for (ContractAssembly.ResolvedLineItem item : lineItems) {
                Tr row = XmlUtils.deepCopy(markerRow);
                // stripUnresolved = false: a schedule row may also carry a
                // scalar placeholder such as {{currency}}, and blanking it here
                // would beat the main pass to it.
                substitute(row, lineItemPlaceholders(item), false);
                rows.add(insertAt++, row);
            }
            // An empty schedule cannot happen today (the assembler always
            // resolves at least one line), but dropping the marker row rather
            // than leaving it is the only sane behaviour if that ever changes.
        }
    }

    private int indexOfMarkerRow(List<Object> rows) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = XmlUtils.unwrap(rows.get(i));
            if (row instanceof Tr tr && textOf(tr).contains(LINE_ITEM_MARKER)) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, String> lineItemPlaceholders(ContractAssembly.ResolvedLineItem item) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("item.lineNumber", String.valueOf(item.lineNumber()));
        values.put("item.description", item.description());
        values.put("item.quantity", ContractPlaceholders.formatAmount(item.quantity()));
        values.put("item.unitPrice", ContractPlaceholders.formatAmount(item.unitPrice()));
        values.put("item.lineTotal", ContractPlaceholders.formatAmount(item.lineTotal()));
        return values;
    }

    /* ---------------------------------------------------------- substitution */

    /**
     * @param stripUnresolved blank out placeholders this map has no value for.
     *                        True on the final pass, so nothing of the form
     *                        {@code {{...}}} can reach the customer; false on
     *                        intermediate passes, which must leave the
     *                        placeholders they do not own for a later one.
     */
    void substitute(Object root, Map<String, String> values, boolean stripUnresolved) {
        for (P paragraph : collect(root, P.class)) {
            substituteInParagraph(paragraph, values, stripUnresolved);
        }
    }

    /**
     * Replaces every placeholder in one paragraph.
     *
     * Matches are applied right to left. Because each edit only ever changes
     * text at or after its own start offset, everything to the left of a match
     * still sits at its original index when that match's turn comes — which is
     * what lets the offsets found against the original text stay valid
     * throughout, without re-scanning after every substitution.
     */
    private void substituteInParagraph(P paragraph, Map<String, String> values, boolean stripUnresolved) {
        List<Text> texts = collect(paragraph, Text.class);
        if (texts.isEmpty()) {
            return;
        }

        String[] current = new String[texts.size()];
        int[] starts = new int[texts.size()];
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            current[i] = texts.get(i).getValue() == null ? "" : texts.get(i).getValue();
            starts[i] = joined.length();
            joined.append(current[i]);
        }

        String full = joined.toString();
        if (!full.contains("{{")) {
            return;
        }

        List<int[]> ranges = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(full);
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = values.get(name);
            if (replacement == null) {
                if (!stripUnresolved) {
                    continue;
                }
                replacement = "";
                log.debug("Contract placeholder {{{}}} had no value; rendered as empty", name);
            }
            ranges.add(new int[]{matcher.start(), matcher.end()});
            replacements.add(replacement);
        }
        if (ranges.isEmpty()) {
            return;
        }

        for (int m = ranges.size() - 1; m >= 0; m--) {
            int start = ranges.get(m)[0];
            int end = ranges.get(m)[1];
            int first = nodeContaining(starts, start);
            int last = nodeContaining(starts, end - 1);

            String prefix = current[first].substring(0, start - starts[first]);
            String suffix = current[last].substring(end - starts[last]);

            if (first == last) {
                current[first] = prefix + replacements.get(m) + suffix;
            } else {
                current[first] = prefix + replacements.get(m);
                for (int k = first + 1; k < last; k++) {
                    current[k] = "";
                }
                current[last] = suffix;
            }
        }

        for (int i = 0; i < texts.size(); i++) {
            Text text = texts.get(i);
            text.setValue(current[i]);
            // Without xml:space="preserve" Word collapses the leading and
            // trailing spaces that separate a substituted value from the words
            // around it ("Between ACME Ltdand the Supplier").
            text.setSpace("preserve");
        }
    }

    /**
     * Which text node an offset in the concatenated text falls in.
     *
     * Uses the original node boundaries, not the possibly-edited current
     * lengths, for the reason given on {@link #substituteInParagraph}. Empty
     * nodes have a zero-width span and so can never be selected — an empty run
     * between the two halves of a split placeholder must not become an endpoint.
     */
    private int nodeContaining(int[] starts, int offset) {
        int last = starts.length - 1;
        for (int i = 0; i < last; i++) {
            if (offset >= starts[i] && offset < starts[i + 1]) {
                return i;
            }
        }
        return last;
    }

    /* ---------------------------------------------------------- line breaks */

    /**
     * A DOCX text node cannot contain a newline, so a multi-line value — a
     * billing address, most often — has to become alternating text and
     * {@code <w:br/>} elements inside its run. Done after substitution so it
     * only ever sees values, never template text.
     */
    private void expandLineBreaks(Object root) {
        for (R run : collect(root, R.class)) {
            List<Object> content = run.getContent();
            for (int i = 0; i < content.size(); i++) {
                Object unwrapped = XmlUtils.unwrap(content.get(i));
                if (!(unwrapped instanceof Text text)) {
                    continue;
                }
                String value = text.getValue();
                if (value == null || (value.indexOf('\n') < 0 && value.indexOf('\r') < 0)) {
                    continue;
                }

                String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
                text.setValue(lines[0]);
                text.setSpace("preserve");

                int insertAt = i + 1;
                for (int line = 1; line < lines.length; line++) {
                    content.add(insertAt++, factory.createBr());
                    Text next = factory.createText();
                    next.setValue(lines[line]);
                    next.setSpace("preserve");
                    content.add(insertAt++, factory.createRT(next));
                }
                i = insertAt - 1;
            }
        }
    }

    /* -------------------------------------------------------------- helpers */

    private String textOf(Object root) {
        StringBuilder sb = new StringBuilder();
        for (Text text : collect(root, Text.class)) {
            sb.append(text.getValue() == null ? "" : text.getValue());
        }
        return sb.toString();
    }

    /** Depth-first walk over the JAXB tree, unwrapping the {@code JAXBElement}
     *  wrappers docx4j uses for substitution-group members. */
    private <T> List<T> collect(Object root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collectInto(root, type, found);
        return found;
    }

    private <T> void collectInto(Object node, Class<T> type, List<T> found) {
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
