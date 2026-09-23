package com.techcrm.crm.contract.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many pages a generated contract PDF has.
 *
 * Deliberately a 40-line scan rather than a PDF library. The only PDFs this ever
 * sees are the ones this application just produced with LibreOffice, and those
 * write the page tree uncompressed — {@code /Type /Pages} with a {@code /Count},
 * and one {@code /Type /Page} per page, with no object streams. Pulling in
 * PDFBox (and its fonts, and its CVE feed) to read one integer out of our own
 * output would be a poor trade.
 *
 * The honest consequence is that this is a heuristic, not a parser: hand it a
 * PDF from somewhere else, with a compressed cross-reference stream, and it will
 * find nothing. So it reports {@link #UNKNOWN} rather than guessing, and callers
 * fall back to page 1 — a signature field in a slightly wrong place beats a
 * failed send.
 */
public final class PdfPageCount {

    private static final Logger log = LoggerFactory.getLogger(PdfPageCount.class);

    public static final int UNKNOWN = 0;

    /** The root page tree's /Count is the document's total page count. Nested
     *  page-tree nodes also carry one, so the largest wins. */
    private static final Pattern COUNT = Pattern.compile("/Count\\s+(\\d{1,6})");

    /** Fallback: one of these per page. The negative lookahead keeps it from
     *  matching /Pages, which is the tree node rather than a leaf. */
    private static final Pattern PAGE = Pattern.compile("/Type\\s*/Page(?![a-zA-Z])");

    private PdfPageCount() {
    }

    /** @return the page count, or {@link #UNKNOWN} when it cannot be established. */
    public static int of(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            return UNKNOWN;
        }

        // ISO-8859-1 maps every byte to exactly one char, so binary streams pass
        // through without the replacement characters UTF-8 would introduce and
        // without shifting any offsets.
        String text = new String(pdf, StandardCharsets.ISO_8859_1);

        int fromCount = UNKNOWN;
        Matcher matcher = COUNT.matcher(text);
        while (matcher.find()) {
            fromCount = Math.max(fromCount, Integer.parseInt(matcher.group(1)));
        }

        int fromLeaves = 0;
        Matcher pages = PAGE.matcher(text);
        while (pages.find()) {
            fromLeaves++;
        }

        if (fromCount > 0 && fromLeaves > 0 && fromCount != fromLeaves) {
            // Both signals present but disagreeing means the assumption above no
            // longer holds for this file. Trust the leaf count, which cannot be
            // inflated by a nested tree node, and say so.
            log.debug("PDF page count disagrees: /Count={} but {} page objects; using {}",
                    fromCount, fromLeaves, fromLeaves);
            return fromLeaves;
        }
        if (fromLeaves > 0) {
            return fromLeaves;
        }
        return Math.max(fromCount, UNKNOWN);
    }
}
