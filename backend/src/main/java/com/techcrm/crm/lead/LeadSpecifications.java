package com.techcrm.crm.lead;

import org.springframework.data.jpa.domain.Specification;

/** Composable Specifications for Lead search. Text predicates use
 *  criteriaBuilder.lower(...) server-side (not Java .toLowerCase()) with
 *  LIKE wildcards escaped, so real data containing '%' or '_' (e.g. a
 *  company literally named "50%_off") can't distort the match. */
public final class LeadSpecifications {

    private static final char ESCAPE_CHAR = '\\';

    private LeadSpecifications() {
    }

    public static Specification<Lead> organizationId(Long organizationId) {
        return (root, query, cb) -> cb.equal(root.get("organizationId"), organizationId);
    }

    public static Specification<Lead> assignedToId(Long assignedToId) {
        return (root, query, cb) -> cb.equal(root.get("assignedToId"), assignedToId);
    }

    public static Specification<Lead> fullNameContains(String value) {
        return likeField("fullName", value);
    }

    public static Specification<Lead> companyContains(String value) {
        return likeField("company", value);
    }

    public static Specification<Lead> emailContains(String value) {
        return likeField("email", value);
    }

    public static Specification<Lead> phoneContains(String value) {
        return likeField("phone", value);
    }

    public static Specification<Lead> statusEquals(String value) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("status")), value.toLowerCase());
    }

    public static Specification<Lead> sourceChannelEquals(String value) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("sourceChannel")), value.toLowerCase());
    }

    public static Specification<Lead> industryEquals(String value) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("industry")), value.toLowerCase());
    }

    public static Specification<Lead> createdFrom(java.time.OffsetDateTime from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<Lead> createdTo(java.time.OffsetDateTime to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to);
    }

    /** OR across fullName/company/email/phone/notes — the free-text "q" param. */
    public static Specification<Lead> globalSearch(String value) {
        String pattern = likePattern(value);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("fullName")), pattern, ESCAPE_CHAR),
                cb.like(cb.lower(cb.coalesce(root.get("company"), "")), pattern, ESCAPE_CHAR),
                cb.like(cb.lower(cb.coalesce(root.get("email"), "")), pattern, ESCAPE_CHAR),
                cb.like(cb.lower(cb.coalesce(root.get("phone"), "")), pattern, ESCAPE_CHAR),
                cb.like(cb.lower(cb.coalesce(root.get("notes"), "")), pattern, ESCAPE_CHAR)
        );
    }

    private static Specification<Lead> likeField(String field, String value) {
        String pattern = likePattern(value);
        return (root, query, cb) -> cb.like(cb.lower(cb.coalesce(root.get(field), "")), pattern, ESCAPE_CHAR);
    }

    private static String likePattern(String value) {
        String escaped = value.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
