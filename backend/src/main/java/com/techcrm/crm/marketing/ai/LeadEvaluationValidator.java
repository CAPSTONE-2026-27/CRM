package com.techcrm.crm.marketing.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.leads.CampaignLead.Eligibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks one lead-evaluation reply against its contract before anything is
 * stored. Rejects rather than repairs: a reply for the wrong lead, a score out
 * of range or a missing decision is a failed evaluation, not something to
 * guess around.
 */
public final class LeadEvaluationValidator {

    public record LeadEvaluation(
            String leadRef,
            int score,
            Eligibility eligibility,
            Double confidence,
            List<String> positiveFactors,
            List<String> negativeFactors,
            String reason
    ) {
    }

    static final int MAX_FACTORS = 6;
    static final int MAX_FACTOR_CHARS = 200;
    static final int MAX_REASON_CHARS = 1000;
    private static final Pattern TAGS = Pattern.compile("<[^>]{0,200}>");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");

    private LeadEvaluationValidator() {
    }

    public static LeadEvaluation parse(String reply, String expectedLeadRef) throws InvalidAiOutputException {
        JsonNode root = extractObject(reply);
        if (root == null) throw new InvalidAiOutputException("Reply was not a JSON object");

        String leadRef = text(root, "lead_id");
        if (leadRef == null || !leadRef.trim().equalsIgnoreCase(expectedLeadRef)) {
            throw new InvalidAiOutputException("lead_id did not match the lead that was sent (expected "
                    + expectedLeadRef + ")");
        }

        JsonNode scoreNode = root.get("score");
        if (scoreNode == null || !scoreNode.isNumber()) {
            throw new InvalidAiOutputException("score must be a number");
        }
        double rawScore = scoreNode.asDouble();
        if (rawScore < 0 || rawScore > 100 || Double.isNaN(rawScore)) {
            throw new InvalidAiOutputException("score must be between 0 and 100");
        }

        String eligibilityRaw = text(root, "eligibility");
        Eligibility eligibility = switch (eligibilityRaw == null ? "" : eligibilityRaw.trim().toLowerCase(Locale.ROOT)) {
            case "eligible" -> Eligibility.ELIGIBLE;
            case "not_eligible", "not eligible", "ineligible" -> Eligibility.NOT_ELIGIBLE;
            default -> throw new InvalidAiOutputException("eligibility must be 'eligible' or 'not_eligible'");
        };

        Double confidence = null;
        JsonNode confidenceNode = root.get("confidence");
        if (confidenceNode != null && confidenceNode.isNumber()) {
            double c = confidenceNode.asDouble();
            if (c >= 0 && c <= 1) confidence = Math.round(c * 100) / 100.0;
        }

        String reason = sanitise(text(root, "reason"), MAX_REASON_CHARS);
        if (reason == null) throw new InvalidAiOutputException("reason is required");

        return new LeadEvaluation(
                expectedLeadRef,
                (int) Math.round(rawScore),
                eligibility,
                confidence,
                factors(root.get("positive_factors")),
                factors(root.get("negative_factors")),
                reason);
    }

    private static List<String> factors(JsonNode node) throws InvalidAiOutputException {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) return out;
        if (!node.isArray()) throw new InvalidAiOutputException("factors must be lists of strings");
        for (JsonNode item : node) {
            if (!item.isTextual()) continue;
            String cleaned = sanitise(item.asText(), MAX_FACTOR_CHARS);
            if (cleaned != null && out.size() < MAX_FACTORS) out.add(cleaned);
        }
        return out;
    }

    static String sanitise(String value, int max) {
        if (value == null) return null;
        String cleaned = CONTROL.matcher(TAGS.matcher(value).replaceAll("")).replaceAll(" ").trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max - 1) + "…";
    }

    /** First '{' to last '}': instruction-tuned models often wrap JSON in fences or prose. */
    static JsonNode extractObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        JsonNode node = MarketingJson.read(raw.substring(start, end + 1), JsonNode.class, null);
        return node != null && node.isObject() ? node : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
