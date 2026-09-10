package com.techcrm.crm.lead.score;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the five signals a model read out of a qualification meeting into a
 * meeting score, a priority band, and the lead's updated score.
 *
 * The language model extracts; this class scores. That split is the whole
 * design: a model asked for both a reading and a number can return a number its
 * own reading does not support, and afterwards there is no way to tell which of
 * the two was wrong. Here the number is a lookup and a sum over values the model
 * committed to in writing, so any score can be recomputed — and challenged —
 * from the stored signals months later.
 *
 * Deliberately a pure function with no dependencies: no repository, no clock, no
 * HTTP. Same signals in, same score out, forever. That is what makes the stored
 * history auditable rather than merely decorative.
 */
@Component
public class LeadScoreFluctuationEngine {

    /* ------------------------------------------------------------------
     * The rule table. This is business policy, not a learned model.
     *
     * Weights are absolute contributions to a 0-100 meeting score, not deltas
     * on the previous score:
     *
     *     Customer Sentiment          20
     *     Buying Intent               30   <- the heaviest, and rightly so:
     *     Decision Maker Involvement  20      it is the closest proxy for
     *     Customer Urgency            15      whether the deal happens at all
     *     Product Interest Level      15
     *                                ---
     *                                100
     *
     * Note the floors. Low intent still scores 5, not 0, and Low urgency scores
     * 5 — a lead that turned up to a meeting and engaged is not worth the same
     * as one that did not. Only Negative sentiment and an Absent decision maker
     * score a true zero, because both are active evidence rather than the
     * absence of enthusiasm. The minimum achievable score is therefore 15, not
     * 0, and that is intentional.
     * ------------------------------------------------------------------ */

    static final Map<String, Integer> SENTIMENT = Map.of(
            "Positive", 20,
            "Neutral", 10,
            "Negative", 0);

    static final Map<String, Integer> BUYING_INTENT = Map.of(
            "High", 30,
            "Medium", 15,
            "Low", 5);

    static final Map<String, Integer> DECISION_MAKER = Map.of(
            "Present", 20,
            "Indirect", 10,
            "Absent", 0);

    static final Map<String, Integer> URGENCY = Map.of(
            "High", 15,
            "Medium", 10,
            "Low", 5);

    static final Map<String, Integer> PRODUCT_INTEREST = Map.of(
            "High", 15,
            "Medium", 10,
            "Low", 5);

    /** Maximum contribution of each parameter, for reporting and validation. */
    static final Map<String, Integer> PARAMETER_WEIGHTS = new LinkedHashMap<>(Map.of(
            "customer_sentiment", 20,
            "buying_intent", 30,
            "decision_maker_involvement", 20,
            "customer_urgency", 15,
            "product_interest_level", 15));

    /* ---- Priority bands, inclusive lower bounds ---- */

    public static final int HIGH_PRIORITY_FLOOR = 85;
    public static final int MEDIUM_PRIORITY_FLOOR = 70;
    public static final int LOW_PRIORITY_FLOOR = 50;

    /**
     * How much the meeting score displaces the lead's existing score.
     *
     * The updated lead score is a blend rather than a replacement, and this is
     * the one number in the class that is a judgement rather than your stated
     * policy — flagging it because it is worth arguing about.
     *
     * Replacing outright would throw away the firmographics the initial score
     * was built from (company size, deal value, timeline), which one meeting
     * does not invalidate. Ignoring the meeting would make the timeline flat.
     * 0.6 weights the meeting higher than the profile, because observed
     * behaviour beats inferred fit, while keeping movement gradual enough that
     * a timeline reads as a trajectory rather than noise.
     *
     * Set lead.score.meeting-weight=1.0 to make the meeting score the lead
     * score outright.
     */
    private final double meetingWeight;

    public LeadScoreFluctuationEngine(
            @Value("${lead.score.meeting-weight:0.6}") double meetingWeight) {
        this.meetingWeight = Math.max(0.0, Math.min(1.0, meetingWeight));
    }

    /** One parameter's contribution to the meeting score. */
    public record Contribution(String parameter, String value, int points, int maxPoints) {
    }

    /**
     * @param meetingScore     0-100, computed from the five signals alone
     * @param priority         band derived from meetingScore
     * @param previousScore    the lead's score before this meeting
     * @param updatedScore     previousScore blended with meetingScore
     * @param scoreDifference  updatedScore - previousScore
     * @param contributions    per-parameter breakdown, heaviest first
     * @param changeReason     one-line human summary
     */
    public record MeetingScore(
            int meetingScore,
            String priority,
            int previousScore,
            int updatedScore,
            int scoreDifference,
            List<Contribution> contributions,
            String changeReason
    ) {
    }

    /**
     * Scores one qualification meeting.
     *
     * @param previousScore the lead's score before this meeting; null is read as
     *                      50 so a lead that was never scored still produces a
     *                      coherent first movement
     */
    public MeetingScore score(Integer previousScore, LeadSignals rawSignals) {
        LeadSignals signals = (rawSignals == null ? LeadSignals.neutral() : rawSignals).normalise();

        List<Contribution> contributions = new ArrayList<>();
        add(contributions, "customer_sentiment", signals.customerSentiment(), SENTIMENT);
        add(contributions, "buying_intent", signals.buyingIntent(), BUYING_INTENT);
        add(contributions, "decision_maker_involvement", signals.decisionMakerInvolvement(), DECISION_MAKER);
        add(contributions, "customer_urgency", signals.customerUrgency(), URGENCY);
        add(contributions, "product_interest_level", signals.productInterestLevel(), PRODUCT_INTEREST);

        int meetingScore = contributions.stream().mapToInt(Contribution::points).sum();

        int previous = previousScore == null ? 50 : clamp(previousScore);
        int updated = clamp((int) Math.round(
                (1 - meetingWeight) * previous + meetingWeight * meetingScore));

        // Heaviest parameter first: the UI and the reason line both want the
        // signals that decided the score, not all five in arbitrary order.
        contributions.sort(Comparator.comparingInt(Contribution::points).reversed());

        return new MeetingScore(
                meetingScore, priorityFor(meetingScore), previous, updated,
                updated - previous, List.copyOf(contributions),
                describe(contributions, updated - previous));
    }

    /**
     * The band a meeting score falls into.
     *
     * A band rather than a raw number is what a manager triages on: the
     * difference between 84 and 86 is noise, the difference between Medium and
     * High Priority is a decision about whose calendar it goes on.
     */
    public String priorityFor(int meetingScore) {
        if (meetingScore >= HIGH_PRIORITY_FLOOR) return "High Priority";
        if (meetingScore >= MEDIUM_PRIORITY_FLOOR) return "Medium Priority";
        if (meetingScore >= LOW_PRIORITY_FLOOR) return "Low Priority";
        return "Very Low Priority";
    }

    /**
     * The lead's first score, recorded so the timeline starts somewhere.
     *
     * Takes the capture-time score as given rather than recomputing it: that
     * score comes from firmographics — company size, deal value, purchase
     * timeline — which meeting signals say nothing about. Re-deriving it here
     * would silently discard them.
     */
    public MeetingScore initial(int captureScore) {
        int score = clamp(captureScore);
        return new MeetingScore(score, priorityFor(score), score, score, 0,
                List.of(), "Initial score at capture");
    }

    /**
     * Likelihood this lead is worth pursuing, 0-100.
     *
     * Not a copy of the meeting score, and weighted differently on purpose. The
     * score answers "how did that meeting go"; this answers "how likely is
     * working this lead to pay off", and the two come apart — a delighted
     * contact with no authority is a good meeting and a poor bet. So the two
     * signals that gate a close, authority and intent, carry three quarters of
     * the weight between them.
     */
    public double qualificationProbability(LeadSignals rawSignals, int meetingScore) {
        LeadSignals signals = (rawSignals == null ? LeadSignals.neutral() : rawSignals).normalise();

        double authority = switch (signals.decisionMakerInvolvement()) {
            case "Present" -> 1.0;
            case "Indirect" -> 0.5;
            default -> 0.0;
        };
        double intent = switch (signals.buyingIntent()) {
            case "High" -> 1.0;
            case "Medium" -> 0.5;
            default -> 0.1;
        };
        double urgency = switch (signals.customerUrgency()) {
            case "High" -> 1.0;
            case "Medium" -> 0.5;
            default -> 0.1;
        };

        // The meeting score contributes, but as the minority term: it is built
        // from these same signals, so weighting it heavily would double-count.
        double weighted = 0.40 * authority + 0.35 * intent + 0.10 * urgency
                + 0.15 * (meetingScore / 100.0);
        return Math.round(Math.max(0, Math.min(100, weighted * 100)) * 10.0) / 10.0;
    }

    private void add(List<Contribution> into, String parameter, String value, Map<String, Integer> weights) {
        into.add(new Contribution(parameter, value,
                weights.getOrDefault(value, 0), PARAMETER_WEIGHTS.get(parameter)));
    }

    /**
     * A one-line explanation built from the strongest and weakest parameters.
     *
     * Both ends, not just the top: "Buying Intent: High" alone tells a manager
     * why the score is good but not what is holding it back, and the second is
     * usually the actionable half.
     */
    private String describe(List<Contribution> contributions, int difference) {
        if (contributions.isEmpty()) {
            return difference == 0 ? "No material change" : "Score adjusted";
        }
        List<Contribution> byStrength = contributions.stream()
                // Ratio, not raw points: 15/15 on urgency is a stronger showing
                // than 20/30 on intent, even though the latter scores more.
                .sorted(Comparator.comparingDouble(c -> -(double) c.points() / c.maxPoints()))
                .toList();

        Contribution best = byStrength.get(0);
        Contribution worst = byStrength.get(byStrength.size() - 1);

        String summary = label(best.parameter()) + ": " + best.value();
        if (!worst.parameter().equals(best.parameter())
                && worst.points() < worst.maxPoints()) {
            summary += "; held back by " + label(worst.parameter()) + ": " + worst.value();
        }
        return summary;
    }

    private String label(String parameter) {
        StringBuilder out = new StringBuilder();
        for (String word : parameter.split("_")) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
