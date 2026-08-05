package com.techcrm.crm.dealflow;

import com.techcrm.crm.dealflow.DealAnalysisClient.ExtractedValue;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fallback used when the analysis model is unreachable or unusable.
 *
 * Keyword matching, and no pretence otherwise: every value it returns carries a
 * low confidence and an explanation that says it came from here. The alternative
 * — refusing to score — would mean a sales executive who wrote up a meeting gets
 * nothing back because a third-party API was down, and the write-up is the part
 * worth keeping.
 *
 * Analyses built this way are stored with status DEGRADED so a weak score can be
 * told apart from a confident one after the fact.
 */
@Component
public class HeuristicMeetingAnalyzer {

    private static final String SOURCE = "Derived by keyword match — the analysis model was unavailable.";

    /** Deliberately low: these readings are worth having, not worth trusting. */
    private static final double CONFIDENCE = 0.25;

    public Map<String, ExtractedValue> analyze(MeetingOutput meeting) {
        String text = corpus(meeting).toLowerCase();
        Map<String, ExtractedValue> parameters = new LinkedHashMap<>();

        parameters.put(DealParameters.CUSTOMER_SENTIMENT, value(sentiment(text)));
        parameters.put(DealParameters.BUYING_INTENT, value(intent(text)));
        parameters.put(DealParameters.BUDGET_STATUS, value(budget(text)));
        parameters.put(DealParameters.DECISION_MAKER_INVOLVEMENT, value(decisionMaker(text, meeting)));
        parameters.put(DealParameters.CUSTOMER_URGENCY, value(urgency(text)));
        parameters.put(DealParameters.MEETING_OUTCOME, value(outcome(text)));
        parameters.put(DealParameters.COMPETITOR_MENTIONS,
                value(isBlank(meeting.getCompetitorMentioned()) && !containsAny(text, "competitor", "rival", "alternative vendor")
                        ? "No" : "Yes"));
        parameters.put(DealParameters.MAIN_OBJECTIONS, value(objections(meeting, text)));
        parameters.put(DealParameters.PRODUCT_INTEREST_LEVEL, value(intent(text)));
        parameters.put(DealParameters.RELATIONSHIP_STRENGTH, value(relationship(text)));
        parameters.put(DealParameters.IMPLEMENTATION_READINESS, value(readiness(text)));

        // No keyword reliably distinguishes these from a write-up, so they are
        // left out rather than guessed. The feature engineering layer will
        // default them and record that it did, which is the honest outcome.
        return parameters;
    }

    private ExtractedValue value(String v) {
        return new ExtractedValue(v, CONFIDENCE, SOURCE);
    }

    private String corpus(MeetingOutput m) {
        return String.join("\n", nullSafe(m.getMeetingSummary()), nullSafe(m.getCustomerRequirements()),
                nullSafe(m.getKeyDiscussionPoints()), nullSafe(m.getCustomerQuestions()),
                nullSafe(m.getCompetitorMentioned()), nullSafe(m.getObjections()),
                nullSafe(m.getBudgetDiscussion()), nullSafe(m.getTimeline()),
                nullSafe(m.getNextSteps()), nullSafe(m.getExecutiveRemarks()));
    }

    private String sentiment(String text) {
        if (containsAny(text, "not interested", "unhappy", "frustrated", "disappointed", "negative", "pushback")) {
            return "Negative";
        }
        if (containsAny(text, "excited", "enthusiastic", "very positive", "impressed", "keen", "loved")) {
            return "Positive";
        }
        return "Neutral";
    }

    private String intent(String text) {
        if (containsAny(text, "ready to buy", "sign the contract", "purchase order", "go ahead", "move forward")) {
            return "High";
        }
        if (containsAny(text, "just exploring", "early stage", "no timeline", "not a priority")) {
            return "Low";
        }
        return "Medium";
    }

    private String budget(String text) {
        if (containsAny(text, "budget approved", "budget is approved", "funds allocated", "signed off")) {
            return "Fully Approved";
        }
        if (containsAny(text, "partially approved", "partial budget", "some budget")) return "Partially Approved";
        if (containsAny(text, "no budget", "budget not allocated", "next fiscal", "no funds")) return "Not Allocated";
        return "Under Review";
    }

    private String decisionMaker(String text, MeetingOutput meeting) {
        String participants = nullSafe(meeting.getParticipants()).toLowerCase();
        if (containsAny(participants + " " + text, "ceo", "cto", "cfo", "director", "vp ", "head of", "decision maker")) {
            return "Yes";
        }
        if (containsAny(text, "will check with", "needs approval", "escalate internally")) return "Indirect";
        return "Indirect";
    }

    private String urgency(String text) {
        if (containsAny(text, "urgent", "asap", "immediately", "critical", "this week")) return "Critical";
        if (containsAny(text, "this quarter", "this month", "soon")) return "High";
        if (containsAny(text, "next year", "no rush", "sometime")) return "Low";
        return "Medium";
    }

    private String outcome(String text) {
        if (containsAny(text, "verbal agreement", "agreed to proceed", "verbally committed")) return "Verbal Agreement";
        if (containsAny(text, "proposal sent", "sent the proposal", "quotation shared")) return "Proposal Sent";
        if (containsAny(text, "rescheduled", "postponed")) return "Rescheduled";
        if (containsAny(text, "no show", "cancelled", "did not attend")) return "No Show / Cancelled";
        return "Discussed Requirements";
    }

    private String relationship(String text) {
        if (containsAny(text, "long-standing", "trusted", "strong relationship", "existing customer")) return "8";
        if (containsAny(text, "first meeting", "new contact", "cold")) return "3";
        return "5";
    }

    private String readiness(String text) {
        if (containsAny(text, "ready to implement", "infrastructure in place", "team is ready")) return "Ready";
        if (containsAny(text, "not ready", "no infrastructure", "needs preparation")) return "Not Ready";
        return "Partially Ready";
    }

    private String objections(MeetingOutput meeting, String text) {
        String objectionText = nullSafe(meeting.getObjections()).toLowerCase() + " " + text;
        List<String> matched = new java.util.ArrayList<>();

        if (containsAny(objectionText, "too expensive", "price too high", "costly", "cheaper")) {
            matched.add("Price Too High");
        }
        if (containsAny(objectionText, "no budget", "budget not allocated")) matched.add("Budget Not Allocated");
        if (containsAny(objectionText, "missing feature", "does not support", "lacks")) matched.add("Missing Features");
        if (containsAny(objectionText, "security", "compliance", "gdpr", "audit")) {
            matched.add("Security / Compliance Concerns");
        }
        if (containsAny(objectionText, "takes too long", "long implementation", "months to deploy")) {
            matched.add("Long Implementation Time");
        }
        if (containsAny(objectionText, "prefers competitor", "already using", "happy with current")) {
            matched.add("Competitor Preference");
        }
        if (containsAny(objectionText, "no urgent", "not a priority", "no business need")) {
            matched.add("No Urgent Business Need");
        }

        return matched.isEmpty() ? DealParameters.NO_OBJECTIONS : String.join("; ", matched);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
