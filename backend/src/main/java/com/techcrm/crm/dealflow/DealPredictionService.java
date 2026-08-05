package com.techcrm.crm.dealflow;

import com.techcrm.crm.dealflow.FeatureEngineeringService.EngineeredFeatures;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deal flow steps 8-10 — reads the model's number and says what to do about it.
 *
 * The score itself comes from the XGBoost bundle. Everything here is
 * interpretation: which factors moved it, how risky the deal looks, and what the
 * sales manager should be asked to approve. Keeping that separate from the model
 * matters because it is the part a sales leader will want to argue with and
 * change, and none of it should require a retrain.
 */
@Service
public class DealPredictionService {

    /* ---- Step 10's decision thresholds, exactly as specified ---- */
    static final double PROPOSAL_THRESHOLD = 80;
    static final double FOLLOW_UP_THRESHOLD = 60;

    static final String ACTION_PROPOSE = "Proceed with proposal";
    static final String ACTION_FOLLOW_UP = "Schedule follow-up meeting";
    static final String ACTION_IMPROVE =
            "Improve customer engagement, resolve objections, increase stakeholder involvement";

    /** Feature value below which a signal counts as working against the deal,
     *  and above which it counts for it. The gap between them is deliberate:
     *  a middling signal is not evidence in either direction. */
    private static final double NEGATIVE_BELOW = 0.40;
    private static final double POSITIVE_ABOVE = 0.70;

    /** The recommendation the score implies. Public because the manager review
     *  panel shows it and freezes it into the review record. */
    public String recommendedAction(double dealScore) {
        if (dealScore >= PROPOSAL_THRESHOLD) return ACTION_PROPOSE;
        if (dealScore >= FOLLOW_UP_THRESHOLD) return ACTION_FOLLOW_UP;
        return ACTION_IMPROVE;
    }

    /**
     * Risk is not simply the inverse of the score.
     *
     * A deal can score well and still be risky — a named competitor, an
     * unallocated budget or a stack of objections are each a way for a strong
     * deal to fall over. So the score sets the baseline and explicit risk
     * signals can escalate it, but never de-escalate it.
     */
    public String riskLevel(double dealScore, Map<String, Double> features, List<String> imputedFields) {
        String base = dealScore >= 70 ? "LOW" : dealScore >= 45 ? "MEDIUM" : "HIGH";

        int escalations = 0;
        if (features.getOrDefault(DealParameters.BUDGET_STATUS, 1.0) <= 0.40) escalations++;
        if (features.getOrDefault(DealParameters.COMPETITOR_MENTIONS, 0.0) >= 1.0) escalations++;
        if (features.getOrDefault("objection_count", 0.0) >= 3) escalations++;
        if (features.getOrDefault(DealParameters.DECISION_MAKER_INVOLVEMENT, 1.0) <= 0.0) escalations++;
        // A reading where most signals had to be defaulted is itself a risk: the
        // score is confident about a meeting the model could barely read.
        if (imputedFields.size() >= DealParameters.ORDERED.size() / 2) escalations++;

        if (escalations >= 2) return escalate(escalate(base));
        if (escalations == 1) return escalate(base);
        return base;
    }

    private String escalate(String level) {
        return switch (level) {
            case "LOW" -> "MEDIUM";
            case "MEDIUM" -> "HIGH";
            default -> "HIGH";
        };
    }

    /**
     * The signals a sales manager should read first, phrased as findings rather
     * than field names.
     *
     * Imputed parameters are excluded from both lists: "no decision maker
     * involved" is a real finding, but "we defaulted decision-maker involvement
     * because the write-up never mentioned it" is not, and presenting the second
     * as the first is how a manager ends up chasing a problem that was never
     * observed.
     */
    public Factors factors(Map<String, Double> features, Map<String, String> labels, List<String> imputedFields) {
        List<String> positive = new ArrayList<>();
        List<String> negative = new ArrayList<>();

        for (String parameter : DealParameters.ORDERED) {
            if (imputedFields.contains(parameter)) continue;

            Double value = features.get(parameter);
            String label = labels.get(parameter);
            if (value == null || label == null) continue;

            String display = DealParameters.DISPLAY_NAMES.getOrDefault(parameter, parameter);

            // Competitor presence and named risks read backwards: a high encoded
            // value there means the signal is present, which is bad news.
            if (DealParameters.COMPETITOR_MENTIONS.equals(parameter)) {
                if (value >= 1.0) negative.add("Competitor in play — " + label);
                continue;
            }
            if (DealParameters.RISK_FACTORS.equals(parameter)) {
                if (!"No Risk Identified".equals(label)) negative.add("Risk flagged — " + label);
                else positive.add("No risks identified");
                continue;
            }

            if (value >= POSITIVE_ABOVE) positive.add(display + ": " + label);
            else if (value <= NEGATIVE_BELOW) negative.add(display + ": " + label);
        }

        double objections = features.getOrDefault("objection_count", 0.0);
        if (objections > 0) {
            negative.add((int) objections + " open objection" + (objections > 1 ? "s" : ""));
        } else if (!imputedFields.contains(DealParameters.MAIN_OBJECTIONS)) {
            positive.add("No objections raised");
        }

        return new Factors(cap(positive), cap(negative));
    }

    /** Five is what fits a dashboard card without scrolling; beyond that a
     *  manager stops reading rather than reads more carefully. */
    private List<String> cap(List<String> items) {
        return items.size() <= 5 ? List.copyOf(items) : List.copyOf(items.subList(0, 5));
    }

    public record Factors(List<String> positive, List<String> negative) {
    }

    /** Pulls the categorical labels back out of the engineered inputs, for the
     *  factor phrasing above. */
    public Map<String, String> labelsFrom(EngineeredFeatures engineered) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        for (String parameter : DealParameters.ORDERED) {
            String key = DealParameters.COMPETITOR_MENTIONS.equals(parameter) ? "competitor_mention" : parameter;
            Object value = engineered.modelInputs().get(key);
            if (value instanceof String text) labels.put(parameter, text);
        }
        return labels;
    }
}
