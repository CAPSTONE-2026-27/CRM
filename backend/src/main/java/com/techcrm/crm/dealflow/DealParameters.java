package com.techcrm.crm.dealflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The vocabulary shared by the analysis model, the feature engineering layer and
 * the XGBoost bundle.
 *
 * The categorical values here are not a free choice: they are exactly what the
 * trained bundle accepts. Its scorer runs in strict mode and rejects an unseen
 * label with a 400 rather than quietly substituting the median, so anything the
 * language model invents has to be snapped back onto this list before it is sent
 * — see {@link #snap}. A confidently wrong score is worse than a rejected one,
 * and both are worse than a snapped one that says what it did.
 *
 * The numeric encodings are this layer's own calibration, not something learned:
 * they exist so a human can read "Fully Approved -> 1.0" and so a future model
 * can train on the engineered vector. The XGBoost model never sees them — it
 * gets the labels and applies its own encoders, which own the real ordinal
 * ordering. Duplicating that here would create two definitions of the feature
 * contract and a silent drift the first time they disagreed.
 */
public final class DealParameters {

    private DealParameters() {
    }

    /* ---- The 14 parameters the analysis model extracts, in display order ---- */

    public static final String CUSTOMER_SENTIMENT = "customer_sentiment";
    public static final String BUYING_INTENT = "buying_intent";
    public static final String RELATIONSHIP_STRENGTH = "relationship_strength";
    public static final String BUDGET_STATUS = "budget_status";
    public static final String DECISION_MAKER_INVOLVEMENT = "decision_maker_involvement";
    public static final String CUSTOMER_URGENCY = "customer_urgency";
    public static final String MAIN_OBJECTIONS = "main_objections";
    public static final String PRODUCT_INTEREST_LEVEL = "product_interest_level";
    public static final String MEETING_OUTCOME = "meeting_outcome";
    public static final String CUSTOMER_REQUIREMENTS = "customer_requirements";
    public static final String RISK_FACTORS = "risk_factors";
    public static final String COMPETITOR_MENTIONS = "competitor_mentions";
    public static final String IMPLEMENTATION_READINESS = "implementation_readiness";
    public static final String UPSELL_OPPORTUNITY = "upsell_opportunity";

    public static final List<String> ORDERED = List.of(
            CUSTOMER_SENTIMENT, BUYING_INTENT, RELATIONSHIP_STRENGTH, BUDGET_STATUS,
            DECISION_MAKER_INVOLVEMENT, CUSTOMER_URGENCY, MAIN_OBJECTIONS, PRODUCT_INTEREST_LEVEL,
            MEETING_OUTCOME, CUSTOMER_REQUIREMENTS, RISK_FACTORS, COMPETITOR_MENTIONS,
            IMPLEMENTATION_READINESS, UPSELL_OPPORTUNITY);

    /** Human labels for the parameter viewer. */
    public static final Map<String, String> DISPLAY_NAMES = Map.ofEntries(
            Map.entry(CUSTOMER_SENTIMENT, "Customer sentiment"),
            Map.entry(BUYING_INTENT, "Buying intent"),
            Map.entry(RELATIONSHIP_STRENGTH, "Relationship strength"),
            Map.entry(BUDGET_STATUS, "Budget status"),
            Map.entry(DECISION_MAKER_INVOLVEMENT, "Decision maker involvement"),
            Map.entry(CUSTOMER_URGENCY, "Customer urgency"),
            Map.entry(MAIN_OBJECTIONS, "Main objections"),
            Map.entry(PRODUCT_INTEREST_LEVEL, "Product interest level"),
            Map.entry(MEETING_OUTCOME, "Meeting outcome"),
            Map.entry(CUSTOMER_REQUIREMENTS, "Customer requirements"),
            Map.entry(RISK_FACTORS, "Risk factors"),
            Map.entry(COMPETITOR_MENTIONS, "Competitor mentioned"),
            Map.entry(IMPLEMENTATION_READINESS, "Implementation readiness"),
            Map.entry(UPSELL_OPPORTUNITY, "Upsell opportunity"));

    /* ---- Accepted values, mirroring the trained bundle ---- */

    public static final List<String> SENTIMENT_VALUES = List.of("Negative", "Neutral", "Positive");
    public static final List<String> INTENT_VALUES = List.of("Low", "Medium", "High");
    public static final List<String> BUDGET_VALUES =
            List.of("Not Allocated", "Under Review", "Partially Approved", "Fully Approved");
    public static final List<String> DECISION_MAKER_VALUES = List.of("No", "Indirect", "Yes");
    public static final List<String> URGENCY_VALUES = List.of("Low", "Medium", "High", "Critical");
    public static final List<String> INTEREST_VALUES = List.of("Low", "Medium", "High", "Very High");
    public static final List<String> OUTCOME_VALUES = List.of(
            "No Show / Cancelled", "Rescheduled", "Discussed Requirements", "Proposal Sent", "Verbal Agreement");
    public static final List<String> YES_NO_VALUES = List.of("No", "Yes");
    public static final List<String> READINESS_VALUES =
            List.of("Not Ready", "Partially Ready", "Ready", "Fully Ready");

    public static final List<String> REQUIREMENT_VALUES = List.of(
            "API / Technical Integration", "Basic Feature Set", "Compliance-driven Requirements",
            "Customized Integration", "Enterprise-grade Security", "Multi-department Rollout",
            "Scalable Infrastructure", "Standard Package");

    public static final List<String> RISK_VALUES = List.of(
            "No Risk Identified", "Budget Constraints", "Competitor Pressure", "Economic Uncertainty",
            "Internal Politics", "Stakeholder Turnover", "Technical Concerns", "Timeline Conflict");

    /** Multi-select. Sent to the model as a semicolon-joined string. */
    public static final List<String> OBJECTION_TOKENS = List.of(
            "Budget Not Allocated", "Competitor Preference", "Lack of Internal Buy-in",
            "Long Implementation Time", "Missing Features", "No Urgent Business Need",
            "Poor Past Experience / Support", "Price Too High", "Security / Compliance Concerns",
            "Unfavorable Contract Terms");

    public static final String NO_OBJECTIONS = "No Objections";

    /** The accepted values for each categorical parameter. relationship_strength
     *  is absent because it is numeric (0-10), not a category. */
    public static final Map<String, List<String>> ALLOWED_VALUES = Map.ofEntries(
            Map.entry(CUSTOMER_SENTIMENT, SENTIMENT_VALUES),
            Map.entry(BUYING_INTENT, INTENT_VALUES),
            Map.entry(BUDGET_STATUS, BUDGET_VALUES),
            Map.entry(DECISION_MAKER_INVOLVEMENT, DECISION_MAKER_VALUES),
            Map.entry(CUSTOMER_URGENCY, URGENCY_VALUES),
            Map.entry(PRODUCT_INTEREST_LEVEL, INTEREST_VALUES),
            Map.entry(MEETING_OUTCOME, OUTCOME_VALUES),
            Map.entry(CUSTOMER_REQUIREMENTS, REQUIREMENT_VALUES),
            Map.entry(RISK_FACTORS, RISK_VALUES),
            Map.entry(COMPETITOR_MENTIONS, YES_NO_VALUES),
            Map.entry(IMPLEMENTATION_READINESS, READINESS_VALUES),
            Map.entry(UPSELL_OPPORTUNITY, YES_NO_VALUES));

    /** Used when the model omits a parameter or returns something unmappable.
     *  Every default is the neutral or most conservative option, so a missing
     *  reading pulls the score toward the middle rather than inventing a
     *  favourable signal. */
    public static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(CUSTOMER_SENTIMENT, "Neutral"),
            Map.entry(BUYING_INTENT, "Medium"),
            Map.entry(BUDGET_STATUS, "Under Review"),
            Map.entry(DECISION_MAKER_INVOLVEMENT, "Indirect"),
            Map.entry(CUSTOMER_URGENCY, "Medium"),
            Map.entry(PRODUCT_INTEREST_LEVEL, "Medium"),
            Map.entry(MEETING_OUTCOME, "Discussed Requirements"),
            Map.entry(CUSTOMER_REQUIREMENTS, "Standard Package"),
            Map.entry(RISK_FACTORS, "No Risk Identified"),
            Map.entry(COMPETITOR_MENTIONS, "No"),
            Map.entry(IMPLEMENTATION_READINESS, "Partially Ready"),
            Map.entry(UPSELL_OPPORTUNITY, "No"));

    /* ---- Numeric encodings for the engineered feature vector ---- */

    private static final Map<String, Double> NUMERIC_ENCODING = buildNumericEncoding();

    private static Map<String, Double> buildNumericEncoding() {
        Map<String, Double> m = new LinkedHashMap<>();
        // Sentiment is deliberately not evenly spaced: "Negative" in a sales
        // meeting is a much stronger signal than "Neutral" is a weak one.
        m.put("Negative", 0.10);
        m.put("Neutral", 0.50);
        m.put("Positive", 0.92);

        m.put("Low", 0.20);
        m.put("Medium", 0.55);
        m.put("High", 0.85);
        m.put("Very High", 1.00);
        m.put("Critical", 1.00);

        m.put("Not Allocated", 0.10);
        m.put("Under Review", 0.40);
        m.put("Partially Approved", 0.70);
        m.put("Fully Approved", 1.00);

        m.put("No", 0.00);
        m.put("Indirect", 0.50);
        m.put("Yes", 1.00);

        m.put("No Show / Cancelled", 0.00);
        m.put("Rescheduled", 0.25);
        m.put("Discussed Requirements", 0.55);
        m.put("Proposal Sent", 0.80);
        m.put("Verbal Agreement", 1.00);

        m.put("Not Ready", 0.00);
        m.put("Partially Ready", 0.40);
        m.put("Ready", 0.75);
        m.put("Fully Ready", 1.00);

        m.put("No Risk Identified", 1.00);
        return m;
    }

    /**
     * The 0-1 encoding of a label, oriented so that higher always means better
     * for the deal. Unknown labels encode as 0.5 rather than 0 — an unreadable
     * signal is not a bad one.
     */
    public static double encode(String value) {
        if (value == null) return 0.5;
        Double encoded = NUMERIC_ENCODING.get(value);
        if (encoded != null) return encoded;
        // Named risks and requirement types have no natural ordering; they are
        // one-hot features to the model, so the engineered vector records only
        // whether one is present.
        if (RISK_VALUES.contains(value)) return 0.25;
        if (REQUIREMENT_VALUES.contains(value)) return 0.5;
        return 0.5;
    }

    /**
     * Maps whatever the language model said onto the nearest value the XGBoost
     * bundle knows, or null when nothing matches.
     *
     * Exact match, then case-insensitive, then containment in either direction —
     * enough to absorb "positive", "Positive sentiment" and "Fully approved"
     * without pretending "Excellent" is a value the model was trained on. A null
     * return is the caller's cue to impute and record that it did.
     */
    public static String snap(String parameter, String raw) {
        List<String> allowed = ALLOWED_VALUES.get(parameter);
        if (allowed == null || raw == null) return null;

        String value = raw.trim();
        if (value.isEmpty()) return null;

        for (String candidate : allowed) {
            if (candidate.equals(value)) return candidate;
        }
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) return candidate;
        }
        return byContainment(allowed, value);
    }

    /**
     * Falls back to substring matching in either direction, which absorbs
     * "positive sentiment" and "Fully approved" without pretending "Excellent"
     * is a value the model knows.
     *
     * Two orderings matter here and they conflict, so both are applied in turn.
     * Models asked for one value routinely return several ("API / Technical
     * Integration; Compliance-driven Requirements"), and the first one named is
     * the one they considered most salient — so an earlier match beats a later
     * one. Where two candidates start at the same position, the longer wins, so
     * "very high" resolves to "Very High" rather than "High".
     */
    private static String byContainment(List<String> allowed, String value) {
        String lower = value.toLowerCase();

        String best = null;
        int bestPosition = Integer.MAX_VALUE;

        for (String candidate : allowed) {
            String c = candidate.toLowerCase();
            int position = lower.indexOf(c);

            if (position < 0) {
                // The other direction: the candidate contains the value, e.g.
                // value "high" against candidate "Very High". Treated as a
                // match at position 0 since there is no offset to speak of.
                if (!c.contains(lower)) continue;
                position = 0;
            }

            if (position < bestPosition
                    || (position == bestPosition && best != null && candidate.length() > best.length())) {
                best = candidate;
                bestPosition = position;
            }
        }
        return best;
    }
}
