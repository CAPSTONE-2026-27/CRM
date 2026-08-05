package com.techcrm.crm.dealflow;

import com.techcrm.crm.dealflow.DealAnalysisClient.ExtractedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deal flow step 7 — turns extracted business parameters into model input.
 *
 * Produces two things from one reading:
 *
 *   modelInputs  the 17 values the XGBoost bundle expects, as the categorical
 *                labels it was trained on. Every categorical is snapped onto the
 *                bundle's vocabulary, because its scorer rejects anything else.
 *   features     a 0-1 numeric vector, oriented so higher is always better for
 *                the deal. Nothing consumes it today; it exists so a human can
 *                audit the translation and so a future model has a training
 *                target that doesn't require re-running the language model.
 *
 * Three of the 17 inputs are not extracted from the meeting at all —
 * total_meetings, lead_score and engagement_score are facts the CRM already
 * knows or can derive. Asking a language model to invent them from a write-up
 * would be strictly worse than reading them.
 */
@Service
public class FeatureEngineeringService {

    private static final Logger log = LoggerFactory.getLogger(FeatureEngineeringService.class);

    /**
     * Weights for the derived engagement score.
     *
     * These are a deliberate judgement, not a fitted result: they say a customer
     * who is positive and engaged with a decision maker in the room is more
     * engaged than one who merely says the right things. They sum to 1.0 so the
     * blend stays in 0-1 whatever the inputs.
     */
    private static final Map<String, Double> ENGAGEMENT_WEIGHTS = Map.of(
            DealParameters.CUSTOMER_SENTIMENT, 0.20,
            DealParameters.BUYING_INTENT, 0.25,
            DealParameters.PRODUCT_INTEREST_LEVEL, 0.15,
            DealParameters.DECISION_MAKER_INVOLVEMENT, 0.20,
            DealParameters.MEETING_OUTCOME, 0.20);

    /** What a feature engineering pass produced. */
    public record EngineeredFeatures(
            Map<String, Double> features,
            Map<String, Object> modelInputs,
            List<String> imputedFields,
            double meanConfidence
    ) {
    }

    public EngineeredFeatures engineer(Map<String, ExtractedValue> extracted,
                                       int totalMeetings,
                                       Double leadScore) {

        Map<String, Double> features = new LinkedHashMap<>();
        Map<String, Object> modelInputs = new LinkedHashMap<>();
        List<String> imputed = new ArrayList<>();

        /* ---- Categorical parameters ---- */
        for (String parameter : DealParameters.ORDERED) {
            if (DealParameters.RELATIONSHIP_STRENGTH.equals(parameter)
                    || DealParameters.MAIN_OBJECTIONS.equals(parameter)) {
                continue; // handled below — one is numeric, the other multi-valued
            }

            ExtractedValue extractedValue = extracted.get(parameter);
            String snapped = extractedValue == null
                    ? null
                    : DealParameters.snap(parameter, extractedValue.value());

            if (snapped == null) {
                snapped = DealParameters.DEFAULTS.get(parameter);
                imputed.add(parameter);
                if (extractedValue != null) {
                    log.debug("Analysis returned '{}' for {}, which the model doesn't accept — defaulting to '{}'",
                            extractedValue.value(), parameter, snapped);
                }
            }

            // competitor_mentions is this layer's name; the model calls the same
            // thing competitor_mention. Renaming it here keeps the mismatch in
            // one place instead of leaking into the prompt or the database.
            modelInputs.put(modelKeyFor(parameter), snapped);
            features.put(parameter, DealParameters.encode(snapped));
        }

        /* ---- relationship_strength: numeric 0-10, not a category ---- */
        Double relationship = parseNumber(extracted.get(DealParameters.RELATIONSHIP_STRENGTH));
        if (relationship == null) {
            relationship = 5.0;
            imputed.add(DealParameters.RELATIONSHIP_STRENGTH);
        }
        relationship = clamp(relationship, 0, 10);
        modelInputs.put("relationship_strength", relationship);
        features.put(DealParameters.RELATIONSHIP_STRENGTH, relationship / 10.0);

        /* ---- main_objections: multi-select, joined for the model ---- */
        List<String> objections = parseObjections(extracted.get(DealParameters.MAIN_OBJECTIONS));
        modelInputs.put("main_objections",
                objections.isEmpty() ? DealParameters.NO_OBJECTIONS : String.join("; ", objections));
        // Inverted so the vector's "higher is better" rule holds: more
        // objections is a worse position, and 4+ is treated as saturated.
        features.put("objection_load", 1.0 - Math.min(objections.size(), 4) / 4.0);
        features.put("objection_count", (double) objections.size());

        /* ---- The three inputs the CRM knows without asking a model ---- */
        modelInputs.put("total_meetings", Math.max(totalMeetings, 0));
        features.put("total_meetings", Math.min(totalMeetings, 10) / 10.0);

        double resolvedLeadScore = leadScore == null ? 50.0 : clamp(leadScore, 0, 100);
        if (leadScore == null) imputed.add("lead_score");
        modelInputs.put("lead_score", resolvedLeadScore);
        features.put("lead_score", resolvedLeadScore / 100.0);

        double engagement = deriveEngagementScore(features);
        modelInputs.put("engagement_score", Math.round(engagement * 1000.0) / 10.0);
        features.put("engagement_score", engagement);

        return new EngineeredFeatures(features, modelInputs, imputed, meanConfidence(extracted, imputed));
    }

    private String modelKeyFor(String parameter) {
        return DealParameters.COMPETITOR_MENTIONS.equals(parameter) ? "competitor_mention" : parameter;
    }

    /**
     * Blends the engagement-related features into a single 0-1 signal.
     *
     * The model was trained with engagement_score as an independent input, so it
     * has to be supplied. Deriving it from the same reading rather than asking
     * the language model for it separately keeps it consistent with the parts it
     * is made of — a "highly engaged" verdict alongside negative sentiment and a
     * cancelled meeting would be noise the model can't reconcile.
     */
    private double deriveEngagementScore(Map<String, Double> features) {
        double total = 0;
        for (Map.Entry<String, Double> weight : ENGAGEMENT_WEIGHTS.entrySet()) {
            total += weight.getValue() * features.getOrDefault(weight.getKey(), 0.5);
        }
        return clamp(total, 0, 1);
    }

    /**
     * Mean confidence across the parameters the model actually read.
     *
     * Imputed fields count as zero rather than being skipped: a reading where
     * eight of fourteen signals had to be defaulted is not as trustworthy as one
     * where all fourteen were found, and averaging only the six that were found
     * would hide exactly that.
     */
    private double meanConfidence(Map<String, ExtractedValue> extracted, List<String> imputed) {
        double total = 0;
        for (String parameter : DealParameters.ORDERED) {
            if (imputed.contains(parameter)) continue;
            ExtractedValue value = extracted.get(parameter);
            // A value the model returned without a confidence is treated as
            // moderately trusted — it was read, just not self-assessed.
            total += value == null ? 0 : (value.confidence() == null ? 0.7 : value.confidence());
        }
        return total / DealParameters.ORDERED.size();
    }

    private List<String> parseObjections(ExtractedValue value) {
        if (value == null || value.value() == null || value.value().isBlank()) return List.of();

        String raw = value.value().trim();
        if (raw.equalsIgnoreCase(DealParameters.NO_OBJECTIONS) || raw.equalsIgnoreCase("none")) return List.of();

        List<String> tokens = new ArrayList<>();
        for (String part : raw.split("[;,]")) {
            String candidate = part.trim();
            if (candidate.isEmpty()) continue;

            // Same snapping problem as the categoricals: the model's phrasing has
            // to land on a token the bundle was trained with, or the score is
            // rejected outright.
            DealParameters.OBJECTION_TOKENS.stream()
                    .filter(token -> token.equalsIgnoreCase(candidate)
                            || token.toLowerCase().contains(candidate.toLowerCase())
                            || candidate.toLowerCase().contains(token.toLowerCase()))
                    .findFirst()
                    .ifPresent(token -> {
                        if (!tokens.contains(token)) tokens.add(token);
                    });
        }
        return tokens;
    }

    private Double parseNumber(ExtractedValue value) {
        if (value == null || value.value() == null) return null;
        // Models return "7", "7/10" and "7 out of 10" interchangeably; take the
        // first number and ignore the rest.
        return Arrays.stream(value.value().trim().split("[^0-9.]+"))
                .filter(part -> !part.isEmpty())
                .findFirst()
                .map(part -> {
                    try {
                        return Double.parseDouble(part);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
