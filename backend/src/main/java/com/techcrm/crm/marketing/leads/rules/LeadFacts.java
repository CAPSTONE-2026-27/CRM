package com.techcrm.crm.marketing.leads.rules;

import com.techcrm.crm.lead.Lead;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The business attributes of a lead that campaign targeting may use, normalised
 * once so the rules engine and the LLM see the same values.
 *
 * Deliberately excludes personal data: no name, email, phone, company name or
 * free-text notes. Those are not needed to judge campaign fit, so they are
 * never placed in a model prompt. {@code leadRef} is an opaque reference the
 * model must echo back, which is how its answer is tied to the right lead.
 *
 * Ranges are [min, max] with max null meaning unbounded; a single known value
 * has min == max. Company size and purchase timeline are recorded as bands
 * ("51-200", "Within 2 Months"), so a rule can be definitively met, definitively
 * failed, or undecidable from the band; RuleEngine reports the last as UNKNOWN
 * rather than guessing.
 */
public record LeadFacts(
        String leadRef,
        String industry,
        String companySize,
        Long companySizeMin,
        Long companySizeMax,
        String location,
        String customerType,
        String leadSource,
        String productInterest,
        String interestLevel,
        Integer crmLeadScore,
        BigDecimal estimatedDealValue,
        String purchaseTimeline,
        Long timelineDaysMin,
        Long timelineDaysMax,
        Integer productQuantity,
        String qualificationStatus,
        String contactStatus
) {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*");

    public static String refFor(Long leadId) {
        return "LD" + leadId;
    }

    public static LeadFacts from(Lead lead) {
        long[] size = parseSize(lead.getEmployeeCount());
        long[] timeline = timelineDays(lead.getPurchaseTimeline());
        return new LeadFacts(
                refFor(lead.getId()),
                blankToNull(lead.getIndustry()),
                blankToNull(lead.getEmployeeCount()),
                size == null ? null : size[0],
                size == null ? null : (size[1] < 0 ? null : size[1]),
                blankToNull(lead.getCompanyLocation()),
                blankToNull(lead.getCustomerType()),
                blankToNull(lead.getSourceChannel()),
                blankToNull(lead.getProduct()),
                interestLevel(lead.getStatus()),
                lead.getAiScore(),
                lead.getEstimatedDealValue(),
                blankToNull(lead.getPurchaseTimeline()),
                timeline == null ? null : timeline[0],
                timeline == null ? null : (timeline[1] < 0 ? null : timeline[1]),
                lead.getProductQuantity(),
                blankToNull(lead.getQualificationStatus()),
                blankToNull(lead.getContactStatus()));
    }

    /** Lead temperature from CRM scoring, expressed as product-interest level. */
    static String interestLevel(String status) {
        if (status == null) return null;
        return switch (status.trim().toUpperCase()) {
            case "HOT" -> "High";
            case "WARM" -> "Medium";
            case "COLD" -> "Low";
            default -> null;
        };
    }

    /**
     * "51-200" -> [51, 200]; "500+" -> [500, -1]; "1,000" -> [1000, 1000].
     * Returns null when no number can be read. -1 marks an open upper bound.
     */
    static long[] parseSize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher m = NUMBER.matcher(raw);
        Long first = null;
        Long second = null;
        while (m.find()) {
            long n = Long.parseLong(m.group().replace(",", ""));
            if (first == null) first = n;
            else if (second == null) second = n;
        }
        if (first == null) return null;
        if (second != null) return new long[]{Math.min(first, second), Math.max(first, second)};
        String lower = raw.toLowerCase();
        if (raw.contains("+") || lower.contains("more") || lower.contains("above") || lower.contains("over")) {
            return new long[]{first, -1};
        }
        if (lower.contains("less") || lower.contains("under") || lower.contains("below") || raw.contains("<")) {
            return new long[]{0, first};
        }
        return new long[]{first, first};
    }

    /** The CRM's six trained timeline strings (LeadRequest.PURCHASE_TIMELINES) as day ranges. */
    static long[] timelineDays(String timeline) {
        if (timeline == null) return null;
        return switch (timeline.trim()) {
            case "Immediately" -> new long[]{0, 0};
            case "Within 15 Days" -> new long[]{0, 15};
            case "Within 1 Month" -> new long[]{0, 30};
            case "Within 2 Months" -> new long[]{0, 60};
            case "Within 3 Months" -> new long[]{0, 90};
            case "More than 3 Months" -> new long[]{91, -1};
            default -> null;
        };
    }

    /** The shape sent to the model: only populated fields, snake_case keys. */
    public Map<String, Object> toPromptMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lead_id", leadRef);
        put(m, "industry", industry);
        put(m, "company_size_employees", companySize);
        put(m, "location", location);
        put(m, "customer_type", customerType);
        put(m, "lead_source", leadSource);
        put(m, "product_interest", productInterest);
        put(m, "interest_level", interestLevel);
        put(m, "crm_lead_score", crmLeadScore);
        put(m, "estimated_deal_value_inr", estimatedDealValue == null ? null : estimatedDealValue.toPlainString());
        put(m, "purchase_timeline", purchaseTimeline);
        put(m, "product_quantity", productQuantity);
        put(m, "qualification_status", qualificationStatus);
        put(m, "contact_status", contactStatus);
        return m;
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
