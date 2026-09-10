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
        OffsetDateTime createdTo,
        // Per-column filters from the lead list header row.
        String product,
        String qualificationStatus,
        String contactStatus,
        Integer scoreMin,
        Integer scoreMax,
        Boolean unassigned
) {
    public static LeadSearchCriteria of(
            String q, String fullName, String company, String email, String phone,
            String status, Long assignedToId, String sourceChannel, String industry,
            OffsetDateTime createdFrom, OffsetDateTime createdTo,
            String product, String qualificationStatus, String contactStatus,
            Integer scoreMin, Integer scoreMax, Boolean unassigned
    ) {
        return new LeadSearchCriteria(
                normalize(q), normalize(fullName), normalize(company), normalize(email), normalize(phone),
                normalize(status), assignedToId, normalize(sourceChannel), normalize(industry),
                createdFrom, createdTo,
                normalize(product), normalize(qualificationStatus), normalize(contactStatus),
                scoreMin, scoreMax, unassigned
        );
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }
}
