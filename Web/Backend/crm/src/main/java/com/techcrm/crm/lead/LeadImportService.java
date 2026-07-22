package com.techcrm.crm.lead;

import com.techcrm.crm.auth.AuthenticatedUser;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bulk lead creation from a CSV file. Accepts a fairly loose header row —
 * both developer-style names (fullName, estimatedDealValue) and
 * human-written ones (Name, Deal Value) resolve to the same field, matched
 * case/punctuation/whitespace-insensitively (see FIELD_ALIASES) — since a
 * real person building a spreadsheet by hand won't naturally write
 * camelCase headers. Rows are created and org-scoped/auto-assigned
 * immediately; AI scoring is dispatched per-row on a background thread
 * (see LeadService#scoreAsync) rather than blocking this request, since
 * scoring a single lead alone already takes 40-70s.
 */
@Service
public class LeadImportService {

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setTrim(true)
            .setIgnoreSurroundingSpaces(true)
            .setAllowMissingColumnNames(true)
            .setAllowDuplicateHeaderNames(true)
            .build();

    // field name -> accepted header spellings, normalized (lowercase,
    // letters/digits only) at comparison time.
    private static final Map<String, List<String>> FIELD_ALIASES = Map.ofEntries(
            Map.entry("fullName", List.of("fullname", "name", "leadname", "contactname", "contact")),
            Map.entry("company", List.of("company", "companyname", "organization", "organisation", "org", "business", "businessname")),
            Map.entry("industry", List.of("industry", "sector")),
            Map.entry("employeeCount", List.of("employeecount", "employees", "companysize", "teamsize", "numemployees", "noofemployees")),
            Map.entry("email", List.of("email", "emailaddress", "workemail", "mail")),
            Map.entry("phone", List.of("phone", "phonenumber", "mobile", "contactnumber", "telephone", "mobilenumber")),
            Map.entry("product", List.of("product", "productinterest", "interestedproduct", "interested")),
            Map.entry("estimatedDealValue", List.of("estimateddealvalue", "dealvalue", "value", "budget", "estimatedvalue", "dealsize", "amount")),
            Map.entry("sourceChannel", List.of("sourcechannel", "source", "leadsource", "channel")),
            Map.entry("notes", List.of("notes", "note", "comments", "comment", "description", "remarks"))
    );

    private final LeadService leadService;
    private final Validator validator;

    public LeadImportService(LeadService leadService, Validator validator) {
        this.leadService = leadService;
        this.validator = validator;
    }

    public LeadImportResult importCsv(AuthenticatedUser caller, String csv) {
        List<FailedRow> failed = new ArrayList<>();
        List<RowWarning> warnings = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();
        int imported = 0;

        try (CSVParser parser = CSVParser.parse(new StringReader(csv), CSV_FORMAT)) {
            Map<String, String> headerByField = resolveHeaders(parser.getHeaderNames());

            if (!headerByField.containsKey("fullName")) {
                failed.add(new FailedRow(0,
                        "No column recognized as the lead's name. Detected headers: " + parser.getHeaderNames()
                                + " — expected one of: fullName, Name, Contact Name."));
                return new LeadImportResult(0, warnings, failed);
            }

            int rowNum = 0;
            for (CSVRecord record : parser) {
                rowNum++;
                try {
                    LeadRequest request = toRequest(record, headerByField);

                    Set<ConstraintViolation<LeadRequest>> violations = validator.validate(request);
                    if (!violations.isEmpty()) {
                        failed.add(new FailedRow(rowNum, violations.iterator().next().getMessage()));
                        continue;
                    }

                    String fingerprint = fingerprint(request);
                    if (!seenInBatch.add(fingerprint)) {
                        failed.add(new FailedRow(rowNum, "Duplicate of another row earlier in this same file (same name, email, and company)"));
                        continue;
                    }
                    if (leadService.isDuplicate(caller.organizationId(), request)) {
                        failed.add(new FailedRow(rowNum, "Duplicate: a lead with this name, email, and company already exists"));
                        continue;
                    }

                    Lead lead = leadService.createUnscored(caller, request);
                    leadService.scoreAsync(lead.getId(), caller.organizationId());
                    imported++;

                    List<String> missing = leadService.missingFields(request);
                    if (!missing.isEmpty()) {
                        warnings.add(new RowWarning(rowNum, missing));
                    }
                } catch (Exception e) {
                    failed.add(new FailedRow(rowNum, e.getMessage() != null ? e.getMessage() : "Could not process this row"));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse CSV file", e);
        }

        return new LeadImportResult(imported, warnings, failed);
    }

    private String fingerprint(LeadRequest request) {
        return normalizeForFingerprint(request.fullName()) + "|"
                + normalizeForFingerprint(request.email()) + "|"
                + normalizeForFingerprint(request.company());
    }

    private String normalizeForFingerprint(String value) {
        return (value == null || value.isBlank()) ? "" : value.trim().toLowerCase();
    }

    /** Maps our field names to whichever actual CSV header spells them,
     *  matching case/space/punctuation-insensitively against FIELD_ALIASES. */
    private Map<String, String> resolveHeaders(List<String> actualHeaders) {
        Map<String, String> result = new LinkedHashMap<>();

        for (String actualHeader : actualHeaders) {
            String normalized = normalize(actualHeader);

            for (Map.Entry<String, List<String>> entry : FIELD_ALIASES.entrySet()) {
                String field = entry.getKey();
                if (result.containsKey(field)) {
                    continue; // first matching column wins if headers repeat
                }
                if (entry.getValue().contains(normalized)) {
                    result.put(field, actualHeader);
                    break;
                }
            }
        }

        return result;
    }

    // Strips everything but letters/digits, so "Full Name", "full-name",
    // and "fullName" all normalize to "fullname" — this also incidentally
    // strips a leading UTF-8 byte-order-mark (which Excel's "CSV UTF-8"
    // export prepends to the file and would otherwise silently break
    // matching on the very first header column), since it isn't [a-z0-9].
    private String normalize(String header) {
        return header == null ? "" : header.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private LeadRequest toRequest(CSVRecord record, Map<String, String> headerByField) {
        return new LeadRequest(
                column(record, headerByField, "fullName"),
                column(record, headerByField, "company"),
                column(record, headerByField, "industry"),
                column(record, headerByField, "employeeCount"),
                column(record, headerByField, "email"),
                column(record, headerByField, "phone"),
                column(record, headerByField, "product"),
                parseDecimal(column(record, headerByField, "estimatedDealValue")),
                column(record, headerByField, "sourceChannel"),
                "CSV_IMPORT",
                column(record, headerByField, "notes"),
                null,
                null
        );
    }

    private String column(CSVRecord record, Map<String, String> headerByField, String field) {
        String header = headerByField.get(field);
        if (header == null || !record.isMapped(header)) {
            return null;
        }
        String value = record.get(header);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private BigDecimal parseDecimal(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
