package com.techcrm.crm.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.techcrm.crm.ai.AiChatClient;
import com.techcrm.crm.ai.AiChatClient.ChatMessage;
import com.techcrm.crm.ai.AiJson;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Summarises a customer meeting and re-scores the lead from the rep's notes.
 *
 * Both come from a single model call: the score has to be justified by the same
 * notes the summary is drawn from, so splitting them risks the two disagreeing.
 * Returns null when the model is unavailable — the rep can still write their own
 * summary and set the score by hand.
 */
@Component
public class MeetingAnalysisClient {

    private static final String SYSTEM_PROMPT =
            "You are a CRM assistant supporting a sales representative after a customer meeting. "
                    + "You will be given a lead's profile, its previous AI score, and the rep's raw meeting notes. "
                    + "Do two things: (1) write a concise 2-4 sentence summary of what happened in the meeting, "
                    + "(2) re-score the lead from 0-100 based on the buying signals in those notes, taking the "
                    + "previous score as the starting point and moving it up or down only as far as the notes justify. "
                    + "Weigh budget confirmation, decision-maker involvement, timeline, explicit next steps, and "
                    + "competitor pressure. Base everything strictly on the notes provided — never invent details. "
                    + "Respond with ONLY strict JSON, no prose, no markdown fences: "
                    + "{\"summary\": \"<2-4 sentences>\", \"score\": <0-100>, \"label\": \"Hot\"|\"Warm\"|\"Cold\", "
                    + "\"reasons\": [\"<short phrase>\", \"...\"]}. "
                    + "Give 2-5 reasons, each a short phrase explaining the score movement.";

    private final AiChatClient chatClient;

    public MeetingAnalysisClient(AiChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public record MeetingAnalysisRequest(
            String fullName,
            String company,
            String industry,
            String product,
            BigDecimal estimatedDealValue,
            String notes,
            Integer previousScore,
            String meetingDate,
            String meetingTime,
            String meetingOutput
    ) {
    }

    public record MeetingAnalysisResult(String summary, Integer score, String label, List<String> reasons) {
    }

    public MeetingAnalysisResult analyze(MeetingAnalysisRequest request) {
        String reply = chatClient.complete(List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user(describe(request))));

        JsonNode json = AiJson.extractObject(reply);
        if (json == null) return null;

        Integer score = AiJson.clampedScore(json, "score");
        String summary = AiJson.text(json, "summary");
        if (score == null || summary == null || summary.isBlank()) return null;

        List<String> reasons = new ArrayList<>();
        JsonNode reasonsNode = json.get("reasons");
        if (reasonsNode != null && reasonsNode.isArray()) {
            reasonsNode.forEach(node -> {
                String text = node.asText("").trim();
                if (!text.isEmpty() && reasons.size() < 5) reasons.add(text);
            });
        }
        return new MeetingAnalysisResult(summary, score, AiJson.text(json, "label"), reasons);
    }

    private String describe(MeetingAnalysisRequest r) {
        List<String> lines = new ArrayList<>();
        lines.add("Contact: " + r.fullName());
        lines.add("Company: " + r.company());
        if (r.industry() != null) lines.add("Industry: " + r.industry());
        if (r.product() != null) lines.add("Product interest: " + r.product());
        if (r.estimatedDealValue() != null) lines.add("Estimated deal value: " + r.estimatedDealValue());
        if (r.notes() != null) lines.add("Existing notes: " + r.notes());
        lines.add("Previous AI score: " + (r.previousScore() == null ? "not scored yet" : r.previousScore()));
        lines.add("");
        lines.add("Meeting held on " + r.meetingDate() + " at " + r.meetingTime() + ".");
        lines.add("Rep's meeting notes:");
        lines.add(r.meetingOutput());
        return String.join("\n", lines);
    }
}
