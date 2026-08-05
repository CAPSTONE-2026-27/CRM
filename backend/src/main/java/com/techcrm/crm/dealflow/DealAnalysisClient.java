package com.techcrm.crm.dealflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.techcrm.crm.ai.AiChatClient;
import com.techcrm.crm.ai.AiChatClient.ChatMessage;
import com.techcrm.crm.ai.AiJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deal flow steps 5-6 — reads a meeting write-up and extracts the business
 * parameters the scoring model needs.
 *
 * The model is constrained twice over: the prompt lists the exact accepted value
 * for every parameter, and {@link DealParameters#snap} pulls whatever comes back
 * onto that list anyway. Prompt constraints are a request, not a guarantee, and
 * the XGBoost scorer rejects unknown labels outright — so the second pass is
 * what actually holds.
 *
 * Each parameter carries a confidence and an explanation because a score derived
 * from six guesses and a score derived from six clear statements are not the
 * same score, and only the meeting text can tell them apart.
 */
@Component
public class DealAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(DealAnalysisClient.class);

    private final AiChatClient chatClient;

    public DealAnalysisClient(AiChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** One extracted parameter: what it is, how sure the model was, and why. */
    public record ExtractedValue(String value, Double confidence, String explanation) {
    }

    /**
     * @param parameters   parameter name -> extracted value
     * @param rawResponse  the model's reply verbatim, for later diagnosis
     * @param latencyMs    wall-clock time of the call
     */
    public record AnalysisResult(Map<String, ExtractedValue> parameters, String rawResponse, long latencyMs) {
    }

    /** Returns null when the model was unreachable or its reply unusable. The
     *  caller falls back to a rule-based reading rather than failing the
     *  submission: losing the meeting record would be worse than a rough score. */
    public AnalysisResult analyze(MeetingOutput meeting, String dealName, Integer leadScore) {
        long start = System.currentTimeMillis();

        String reply = chatClient.complete(List.of(
                ChatMessage.system(systemPrompt()),
                ChatMessage.user(describe(meeting, dealName, leadScore))));

        long latency = System.currentTimeMillis() - start;

        JsonNode json = AiJson.extractObject(reply);
        if (json == null) {
            log.warn("Deal analysis returned no usable JSON for meeting output {}", meeting.getId());
            return null;
        }

        Map<String, ExtractedValue> parameters = new LinkedHashMap<>();
        for (String name : DealParameters.ORDERED) {
            JsonNode node = json.get(name);
            if (node == null || node.isNull()) continue;

            // Two accepted shapes: the documented object, and a bare scalar for
            // models that ignore the nesting. Rejecting the second would throw
            // away a perfectly good reading over formatting.
            String value;
            Double confidence = null;
            String explanation = null;

            if (node.isObject()) {
                JsonNode valueNode = node.get("value");
                value = valueNode == null || valueNode.isNull() ? null : valueNode.asText();
                confidence = confidenceOf(node.get("confidence"));
                explanation = AiJson.text(node, "explanation");
            } else {
                value = node.asText();
            }

            if (value != null && !value.isBlank()) {
                parameters.put(name, new ExtractedValue(value.trim(), confidence, explanation));
            }
        }

        if (parameters.isEmpty()) {
            log.warn("Deal analysis parsed but extracted no parameters for meeting output {}", meeting.getId());
            return null;
        }

        return new AnalysisResult(parameters, reply, latency);
    }

    /** Accepts 0-1 and 0-100, since models produce both regardless of what the
     *  prompt asks for. Values above 1 are read as percentages. */
    private Double confidenceOf(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber() && !node.isTextual()) return null;
        double raw;
        if (node.isNumber()) {
            raw = node.asDouble();
        } else {
            try {
                raw = Double.parseDouble(node.asText().trim().replace("%", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (raw > 1.0) raw = raw / 100.0;
        return Math.max(0.0, Math.min(1.0, raw));
    }

    private String systemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a B2B sales analyst. You will be given a sales executive's structured write-up ")
                .append("of a customer meeting. Extract the business signals listed below and return them as JSON.\n\n")
                .append("Rules:\n")
                .append("- Base every value strictly on the meeting text. Never infer facts that are not there.\n")
                .append("- Use ONLY the allowed values listed for each field. Do not invent new ones.\n")
                .append("- confidence is 0.0-1.0: how clearly the meeting text supports your value. ")
                .append("Use a low confidence when the text is silent on that signal — do not guess confidently.\n")
                .append("- explanation is one short sentence quoting or paraphrasing the evidence.\n\n")
                .append("Fields and their allowed values:\n");

        for (String name : DealParameters.ORDERED) {
            if (DealParameters.RELATIONSHIP_STRENGTH.equals(name)) {
                prompt.append("- relationship_strength: a number from 0 to 10 (0 = hostile, 10 = trusted partner)\n");
            } else if (DealParameters.MAIN_OBJECTIONS.equals(name)) {
                prompt.append("- main_objections: a semicolon-separated selection of [")
                        .append(String.join(", ", DealParameters.OBJECTION_TOKENS))
                        .append("], or \"").append(DealParameters.NO_OBJECTIONS).append("\" if none were raised\n");
            } else {
                prompt.append("- ").append(name).append(": one of [")
                        .append(String.join(", ", DealParameters.ALLOWED_VALUES.get(name)))
                        .append("]\n");
            }
        }

        prompt.append("\nRespond with ONLY strict JSON, no prose and no markdown fences, in exactly this shape:\n")
                .append("{\"customer_sentiment\": {\"value\": \"Positive\", \"confidence\": 0.9, ")
                .append("\"explanation\": \"...\"}, \"buying_intent\": {\"value\": \"High\", \"confidence\": 0.8, ")
                .append("\"explanation\": \"...\"}, ...}\n")
                .append("Include all ").append(DealParameters.ORDERED.size()).append(" fields.");

        return prompt.toString();
    }

    /** Only the sections the executive actually filled in. Empty fields are
     *  omitted rather than sent blank, so the model isn't invited to read
     *  "not recorded" as "nothing happened". */
    private String describe(MeetingOutput m, String dealName, Integer leadScore) {
        List<String> lines = new ArrayList<>();
        lines.add("Opportunity: " + (dealName == null ? "—" : dealName)
                + (m.getOpportunityId() == null ? "" : " (" + m.getOpportunityId() + ")"));
        if (leadScore != null) lines.add("Originating lead score: " + leadScore + "/100");
        lines.add("Meeting " + m.getVersion() + " on " + m.getMeetingDate() + " at " + m.getMeetingTime()
                + (m.getMeetingType() == null ? "" : " (" + m.getMeetingType() + ")"));
        addIfPresent(lines, "Participants", m.getParticipants());
        lines.add("");

        addIfPresent(lines, "Meeting summary", m.getMeetingSummary());
        addIfPresent(lines, "Customer requirements", m.getCustomerRequirements());
        addIfPresent(lines, "Key discussion points", m.getKeyDiscussionPoints());
        addIfPresent(lines, "Customer questions", m.getCustomerQuestions());
        addIfPresent(lines, "Competitors mentioned", m.getCompetitorMentioned());
        addIfPresent(lines, "Objections raised", m.getObjections());
        addIfPresent(lines, "Budget discussion", m.getBudgetDiscussion());
        addIfPresent(lines, "Timeline", m.getTimeline());
        addIfPresent(lines, "Next steps", m.getNextSteps());
        addIfPresent(lines, "Executive remarks", m.getExecutiveRemarks());

        return String.join("\n", lines);
    }

    private void addIfPresent(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + ": " + value.trim());
        }
    }
}
