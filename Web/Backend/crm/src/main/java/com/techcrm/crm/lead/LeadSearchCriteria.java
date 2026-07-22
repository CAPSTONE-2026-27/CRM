package com.techcrm.crm.lead;

import java.time.OffsetDateTime;

/** Normalizes every incoming text param once (trim + collapse internal
 *  whitespace) before any predicate is built, so "  acme  " and "acme"
 *  produce identical results. */
public record LeadSearchCriteria(
        String q,
        String fullName,
        String company,
        String email,
        String phone,
        String status,
        Long assignedToId,
        String sourceChannel,
        String industry,
        OffsetDateTime createdFrom,
        OffsetDateTime createdTo
) {
    public static LeadSearchCriteria of(
            String q, String fullName, String company, String email, String phone,
            String status, Long assignedToId, String sourceChannel, String industry,
            OffsetDateTime createdFrom, OffsetDateTime createdTo
    ) {
        return new LeadSearchCriteria(
                normalize(q), normalize(fullName), normalize(company), normalize(email), normalize(phone),
                normalize(status), assignedToId, normalize(sourceChannel), normalize(industry),
                createdFrom, createdTo
        );
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }
}
