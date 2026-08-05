package com.techcrm.crm.lead;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Writes leads as RFC 4180 CSV.
 *
 * Every value is quoted unconditionally rather than only when it contains a
 * delimiter. Notes and reasoning fields routinely contain commas, quotes and
 * newlines, and "quote only when necessary" is the rule that eventually gets
 * one case wrong and corrupts the file for every row after it.
 */
final class LeadCsvExporter {

    private static final List<String> HEADERS = List.of(
            "Lead ID", "Full name", "Company", "Industry", "Employee count",
            "Email", "Phone", "Product", "Estimated deal value", "Source channel",
            "Capture method", "Notes", "AI score", "AI score label", "AI score reason",
            "Status", "Qualification status", "Qualification probability", "Qualification reasoning",
            "Assigned to", "Assigned at", "Assignment status",
            "Contact status", "Contact status updated at", "Contact notes",
            "Converted deal ID", "Converted at", "Created at");

    private LeadCsvExporter() {
    }

    static void write(List<Lead> leads, Writer out) throws IOException {
        // Excel opens UTF-8 CSV as the system codepage without this, which
        // mangles any non-ASCII customer name.
        out.write('﻿');

        writeRow(out, HEADERS.toArray(new String[0]));

        for (Lead l : leads) {
            writeRow(out,
                    str(l.getId()), l.getFullName(), l.getCompany(), l.getIndustry(), l.getEmployeeCount(),
                    l.getEmail(), l.getPhone(), l.getProduct(), str(l.getEstimatedDealValue()), l.getSourceChannel(),
                    l.getCaptureMethod(), l.getNotes(), str(l.getAiScore()), l.getAiScoreLabel(), l.getAiScoreReason(),
                    l.getStatus(), l.getQualificationStatus(), str(l.getQualificationProbability()),
                    l.getQualificationReasoning(),
                    str(l.getAssignedToId()), str(l.getAssignedAt()), l.getAssignmentStatus(),
                    l.getContactStatus(), str(l.getContactStatusUpdatedAt()), l.getContactNotes(),
                    str(l.getConvertedDealId()), str(l.getConvertedAt()), str(l.getCreatedAt()));
        }
        out.flush();
    }

    private static void writeRow(Writer out, String... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.write(',');
            out.write('"');
            out.write(values[i] == null ? "" : values[i].replace("\"", "\"\""));
            out.write('"');
        }
        out.write("\r\n");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
