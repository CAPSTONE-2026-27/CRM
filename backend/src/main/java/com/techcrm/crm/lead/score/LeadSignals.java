package com.techcrm.crm.lead.score;

import java.util.List;

/**
 * The five business signals the fine-tuned model extracts from a qualification
 * meeting, and the only input the scoring engine reads.
 *
 * A record rather than the analysis entity so the engine can be a pure function
 * of plain values: {@link LeadScoreFluctuationEngine} is the one place a lead's
 * score is decided, and it must be testable without a database, an HTTP call or
 * a Spring context.
 *
 * The model never produces a score. It reads the meeting and names the five
 * signals; the arithmetic is the engine's. A model asked for both a reading and
 * a number can return a number its own reading does not support, and nothing
 * afterwards can tell which of the two was wrong.
 *
 * Every value is a fixed vocabulary enforced three times over — here by
 * {@link #normalise}, in the database by a CHECK constraint, and in the prompt.
 * That is not redundancy: the model is the least reliable of the three, and an
 * unrecognised value must become a visible neutral rather than a silent zero
 * that reads like a deliberate judgement of "worst case".
 */
public record LeadSignals(
        String customerSentiment,
        String buyingIntent,
        String decisionMakerInvolvement,
        String customerUrgency,
        String productInterestLevel
) {

    /* ---- Accepted vocabulary. Mirrors the DB CHECK and the prompt. ---- */

    public static final List<String> SENTIMENT = List.of("Positive", "Neutral", "Negative");
    public static final List<String> BUYING_INTENT = List.of("High", "Medium", "Low");
    public static final List<String> DECISION_MAKER = List.of("Present", "Indirect", "Absent");
    public static final List<String> URGENCY = List.of("High", "Medium", "Low");
    public static final List<String> PRODUCT_INTEREST = List.of("High", "Medium", "Low");

    /**
     * The reading used when the model omits a signal or returns something
     * outside the vocabulary.
     *
     * Middle of each scale, never the worst. A missing reading means "the notes
     * did not say", and scoring that as Negative/Absent/Low would punish a lead
     * for the model's failure to parse rather than for anything the customer
     * did.
     */
    public static LeadSignals neutral() {
        return new LeadSignals("Neutral", "Medium", "Indirect", "Medium", "Medium");
    }

    /**
     * Snaps every field onto its vocabulary, falling back per field.
     *
     * Per field, not whole-record: one unparseable signal should not discard
     * the four the model read correctly.
     */
    public LeadSignals normalise() {
        LeadSignals fallback = neutral();
        return new LeadSignals(
                snap(customerSentiment, SENTIMENT, fallback.customerSentiment()),
                snap(buyingIntent, BUYING_INTENT, fallback.buyingIntent()),
                snap(decisionMakerInvolvement, DECISION_MAKER, fallback.decisionMakerInvolvement()),
                snap(customerUrgency, URGENCY, fallback.customerUrgency()),
                snap(productInterestLevel, PRODUCT_INTEREST, fallback.productInterestLevel()));
    }

    /** True when every field already sits in its vocabulary. */
    public boolean isClean() {
        return this.equals(normalise());
    }

    /**
     * Exact, then case-insensitive, then containment — enough to absorb
     * "positive", "High intent" and "decision maker present" without pretending
     * "Excellent" is a value the engine knows how to weigh.
     */
    private static String snap(String raw, List<String> allowed, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim();
        for (String candidate : allowed) {
            if (candidate.equals(value)) return candidate;
        }
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) return candidate;
        }
        String lower = value.toLowerCase();
        String best = null;
        for (String candidate : allowed) {
            String c = candidate.toLowerCase();
            if (lower.contains(c) || c.contains(lower)) {
                if (best == null || candidate.length() > best.length()) best = candidate;
            }
        }
        return best == null ? fallback : best;
    }
}
