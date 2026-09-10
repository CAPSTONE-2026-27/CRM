package com.techcrm.crm.lead.score;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine decides every lead score in the system, so it is tested as a pure
 * function — no Spring context, no database, no model.
 *
 * The rule table is asserted value by value rather than spot-checked. It is
 * business policy someone will want to tune, and a test that pins every cell is
 * the difference between "changing a weight" and "changing a weight and
 * silently changing three other things".
 */
class LeadScoreFluctuationEngineTest {

    /** meeting-weight 0.6, the configured default. */
    private final LeadScoreFluctuationEngine engine = new LeadScoreFluctuationEngine(0.6);

    private LeadSignals signals(String sentiment, String intent, String decisionMaker,
                                String urgency, String interest) {
        return new LeadSignals(sentiment, intent, decisionMaker, urgency, interest);
    }

    private LeadSignals best() {
        return signals("Positive", "High", "Present", "High", "High");
    }

    private LeadSignals worst() {
        return signals("Negative", "Low", "Absent", "Low", "Low");
    }

    @Nested
    @DisplayName("the rule table")
    class RuleTable {

        @Test
        @DisplayName("a perfect meeting scores exactly 100")
        void perfectMeetingScoresHundred() {
            assertThat(engine.score(50, best()).meetingScore()).isEqualTo(100);
        }

        @Test
        @DisplayName("the worst meeting scores 15, not 0")
        void worstMeetingScoresFifteen() {
            // Low intent, urgency and interest all floor at 5 rather than 0: a
            // customer who attended and engaged is not worth the same as one who
            // did not. Only Negative sentiment and an Absent decision maker are
            // true zeros, because both are evidence rather than an absence of
            // enthusiasm.
            assertThat(engine.score(50, worst()).meetingScore()).isEqualTo(15);
        }

        @Test
        @DisplayName("parameter weights sum to 100")
        void weightsSumToHundred() {
            int total = LeadScoreFluctuationEngine.PARAMETER_WEIGHTS.values().stream()
                    .mapToInt(Integer::intValue).sum();
            assertThat(total).isEqualTo(100);
        }

        @ParameterizedTest
        @CsvSource({"Positive,20", "Neutral,10", "Negative,0"})
        void customerSentiment(String value, int points) {
            assertThat(pointsFor("customer_sentiment",
                    signals(value, "Low", "Absent", "Low", "Low"))).isEqualTo(points);
        }

        @ParameterizedTest
        @CsvSource({"High,30", "Medium,15", "Low,5"})
        void buyingIntent(String value, int points) {
            assertThat(pointsFor("buying_intent",
                    signals("Negative", value, "Absent", "Low", "Low"))).isEqualTo(points);
        }

        @ParameterizedTest
        @CsvSource({"Present,20", "Indirect,10", "Absent,0"})
        void decisionMakerInvolvement(String value, int points) {
            assertThat(pointsFor("decision_maker_involvement",
                    signals("Negative", "Low", value, "Low", "Low"))).isEqualTo(points);
        }

        @ParameterizedTest
        @CsvSource({"High,15", "Medium,10", "Low,5"})
        void customerUrgency(String value, int points) {
            assertThat(pointsFor("customer_urgency",
                    signals("Negative", "Low", "Absent", value, "Low"))).isEqualTo(points);
        }

        @ParameterizedTest
        @CsvSource({"High,15", "Medium,10", "Low,5"})
        void productInterestLevel(String value, int points) {
            assertThat(pointsFor("product_interest_level",
                    signals("Negative", "Low", "Absent", "Low", value))).isEqualTo(points);
        }

        private int pointsFor(String parameter, LeadSignals input) {
            return engine.score(50, input).contributions().stream()
                    .filter(c -> c.parameter().equals(parameter))
                    .findFirst().orElseThrow().points();
        }

        @Test
        @DisplayName("buying intent carries the most weight")
        void buyingIntentIsHeaviest() {
            // It is the closest single proxy for whether the deal happens, so
            // no other parameter may quietly overtake it.
            int intent = LeadScoreFluctuationEngine.PARAMETER_WEIGHTS.get("buying_intent");
            LeadScoreFluctuationEngine.PARAMETER_WEIGHTS.forEach((name, weight) -> {
                if (!name.equals("buying_intent")) assertThat(weight).isLessThan(intent);
            });
        }
    }

    @Nested
    @DisplayName("priority bands")
    class Priority {

        @ParameterizedTest
        @CsvSource({
                "100, High Priority", "85, High Priority",
                "84, Medium Priority", "70, Medium Priority",
                "69, Low Priority", "50, Low Priority",
                "49, Very Low Priority", "15, Very Low Priority", "0, Very Low Priority"
        })
        @DisplayName("boundaries land on the correct side")
        void bandBoundaries(int score, String expected) {
            assertThat(engine.priorityFor(score)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a perfect meeting is High Priority")
        void perfectMeetingIsHighPriority() {
            assertThat(engine.score(50, best()).priority()).isEqualTo("High Priority");
        }

        @Test
        @DisplayName("the worst meeting is Very Low Priority")
        void worstMeetingIsVeryLowPriority() {
            assertThat(engine.score(50, worst()).priority()).isEqualTo("Very Low Priority");
        }
    }

    @Nested
    @DisplayName("updated lead score")
    class UpdatedScore {

        @Test
        @DisplayName("blends the meeting with the lead's existing score")
        void blendsWithPrevious() {
            // 0.4 * 80 + 0.6 * 100 = 92. Not 100: the firmographics behind the
            // initial score are not invalidated by one good conversation.
            var result = engine.score(80, best());
            assertThat(result.meetingScore()).isEqualTo(100);
            assertThat(result.updatedScore()).isEqualTo(92);
        }

        @Test
        @DisplayName("a bad meeting pulls a strong lead down")
        void badMeetingPullsDown() {
            var result = engine.score(90, worst());
            assertThat(result.updatedScore()).isLessThan(90);
            assertThat(result.scoreDifference()).isNegative();
        }

        @Test
        @DisplayName("a good meeting lifts a weak lead")
        void goodMeetingLiftsUp() {
            var result = engine.score(30, best());
            assertThat(result.scoreDifference()).isPositive();
        }

        @Test
        @DisplayName("meeting-weight 1.0 replaces the score outright")
        void fullWeightReplaces() {
            var replacing = new LeadScoreFluctuationEngine(1.0);
            assertThat(replacing.score(20, best()).updatedScore()).isEqualTo(100);
        }

        @Test
        @DisplayName("meeting-weight 0 leaves the score untouched")
        void zeroWeightIgnoresTheMeeting() {
            var ignoring = new LeadScoreFluctuationEngine(0.0);
            var result = ignoring.score(64, best());
            assertThat(result.updatedScore()).isEqualTo(64);
            // The meeting is still scored and banded even when it does not move
            // the lead — the manager still needs to see how it went.
            assertThat(result.meetingScore()).isEqualTo(100);
        }

        @Test
        @DisplayName("a null previous score starts from the neutral midpoint")
        void nullPreviousStartsAtFifty() {
            assertThat(engine.score(null, best()).previousScore()).isEqualTo(50);
        }

        @ParameterizedTest
        @CsvSource({"0", "50", "100"})
        @DisplayName("the updated score always lands within 0-100")
        void staysInRange(int previous) {
            assertThat(engine.score(previous, best()).updatedScore()).isBetween(0, 100);
            assertThat(engine.score(previous, worst()).updatedScore()).isBetween(0, 100);
        }

        @Test
        @DisplayName("the initial score records itself without inventing a movement")
        void initialHasNoMovement() {
            var result = engine.initial(82);
            assertThat(result.updatedScore()).isEqualTo(82);
            assertThat(result.scoreDifference()).isZero();
            assertThat(result.contributions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("robustness against the model")
    class Robustness {

        @Test
        @DisplayName("an unrecognised value is read as the middle of its scale, not the worst")
        void unknownValuesFallBackToNeutral() {
            var invented = signals("Ecstatic", "Enormous", "Maybe", "Whenever", "Quite");
            // 10 + 15 + 10 + 10 + 10 = 55. Scoring these as zero would punish
            // the lead for the model failing to parse.
            assertThat(engine.score(50, invented).meetingScore()).isEqualTo(55);
        }

        @Test
        @DisplayName("one bad signal does not discard the four good ones")
        void oneBadSignalDoesNotDiscardTheRest() {
            var mostlyGood = signals("Positive", "High", "Present", "High", "NOT_A_LEVEL");
            // 20 + 30 + 20 + 15 + 10(neutral fallback) = 95
            assertThat(engine.score(50, mostlyGood).meetingScore()).isEqualTo(95);
        }

        @Test
        @DisplayName("null signals score as an entirely neutral meeting")
        void nullSignalsAreSafe() {
            assertThat(engine.score(64, null).meetingScore()).isEqualTo(55);
        }

        @ParameterizedTest
        @CsvSource({"positive, Positive", "POSITIVE, Positive", "' Positive ', Positive"})
        @DisplayName("casing and whitespace are absorbed")
        void casingIsAbsorbed(String raw, String expected) {
            assertThat(signals(raw, "High", "Present", "High", "High").normalise()
                    .customerSentiment()).isEqualTo(expected);
        }

        @Test
        @DisplayName("isClean distinguishes a parsed reading from a repaired one")
        void isCleanFlagsRepairs() {
            assertThat(best().isClean()).isTrue();
            assertThat(signals("positive", "High", "Present", "High", "High").isClean()).isFalse();
        }
    }

    @Nested
    @DisplayName("audit trail")
    class AuditTrail {

        @Test
        @DisplayName("contributions add up to the meeting score")
        void contributionsReconcile() {
            var result = engine.score(60, signals("Positive", "Medium", "Present", "Low", "High"));
            int summed = result.contributions().stream()
                    .mapToInt(LeadScoreFluctuationEngine.Contribution::points).sum();
            // If these disagree, the stored breakdown does not explain the
            // stored number and the audit trail is decorative.
            assertThat(summed).isEqualTo(result.meetingScore());
        }

        @Test
        @DisplayName("every parameter appears, including the ones scoring zero")
        void allFiveParametersAreRecorded() {
            // A zero must be visible. "Decision Maker: Absent, 0/20" is the most
            // actionable line in the whole breakdown.
            assertThat(engine.score(50, worst()).contributions()).hasSize(5);
        }

        @Test
        @DisplayName("each contribution carries its maximum, so a share is readable")
        void contributionsCarryTheirMaximum() {
            var result = engine.score(50, best());
            result.contributions().forEach(c ->
                    assertThat(c.maxPoints())
                            .isEqualTo(LeadScoreFluctuationEngine.PARAMETER_WEIGHTS.get(c.parameter())));
        }

        @Test
        @DisplayName("the reason names both the strongest signal and the weakest")
        void reasonNamesBothEnds() {
            // The weak end is usually the actionable half: knowing intent is
            // High matters less than knowing the decision maker never showed.
            var result = engine.score(50, signals("Positive", "High", "Absent", "High", "High"));
            assertThat(result.changeReason())
                    .contains("Decision Maker Involvement")
                    .contains("held back by");
        }

        @Test
        @DisplayName("a perfect meeting has nothing holding it back")
        void perfectMeetingHasNoWeakEnd() {
            assertThat(engine.score(50, best()).changeReason()).doesNotContain("held back by");
        }
    }

    @Nested
    @DisplayName("qualification probability")
    class Probability {

        @Test
        @DisplayName("is not merely a copy of the meeting score")
        void isNotTheScore() {
            // The case the split exists for: delighted contact, no authority,
            // no intent. A good meeting and a poor bet.
            var enthusiasticNoAuthority = signals("Positive", "Low", "Absent", "Low", "High");
            int meetingScore = engine.score(50, enthusiasticNoAuthority).meetingScore();
            double probability = engine.qualificationProbability(enthusiasticNoAuthority, meetingScore);
            assertThat(probability).isLessThan(meetingScore);
        }

        @Test
        @DisplayName("rises when authority and intent are both present")
        void risesWithClosingSignals() {
            assertThat(engine.qualificationProbability(best(), 100)).isGreaterThan(90.0);
        }

        @ParameterizedTest
        @CsvSource({"0", "55", "100"})
        @DisplayName("always lands within 0-100")
        void staysInRange(int meetingScore) {
            assertThat(engine.qualificationProbability(LeadSignals.neutral(), meetingScore))
                    .isBetween(0.0, 100.0);
        }
    }
}
