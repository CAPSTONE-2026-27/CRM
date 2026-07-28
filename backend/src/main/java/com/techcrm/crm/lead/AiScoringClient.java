package com.techcrm.crm.lead;

import com.fasterxml.jackson.databind.JsonNode;
import com.techcrm.crm.ai.AiChatClient;
import com.techcrm.crm.ai.AiChatClient.ChatMessage;
import com.techcrm.crm.ai.AiJson;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores a lead by prompting an OpenAI-compatible chat model (see AiChatClient).
 * Returns null when the model is unreachable or its reply is unusable, leaving
 * the lead unscored rather than failing the caller's request.
 */
@Component
public class AiScoringClient {

    private static final String SYSTEM_PROMPT =
            "You are a CRM lead-scoring assistant. Score the lead from 0-100 based on fit and intent signals "
                    + "(deal value, industry, company size, source channel, and buying signals in the notes). "
                    + "Respond with ONLY strict JSON, no prose, no markdown fences: "
                    + "{\"score\": <0-100>, \"label\": \"Hot\"|\"Warm\"|\"Cold\", \"reason\": \"<one sentence>\"}";

    private final AiChatClient chatClient;

    public AiScoringClient(AiChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public AiScoreResult score(Lead lead) {
        String reply = chatClient.complete(List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user(describe(lead))));

        JsonNode json = AiJson.extractObject(reply);
        if (json == null) return null;

        Integer score = AiJson.clampedScore(json, "score");
        if (score == null) return null;

        return new AiScoreResult(score, normaliseLabel(AiJson.text(json, "label"), score), AiJson.text(json, "reason"));
    }

    /** Only the fields the sales rep actually filled in — absent values are
     *  omitted rather than sent as empty, so the model isn't invited to read
     *  "unknown" as "none". */
    private String describe(Lead lead) {
        List<String> lines = new ArrayList<>();
        lines.add("Contact: " + lead.getFullName());
        lines.add("Company: " + lead.getCompany());
        if (lead.getIndustry() != null) lines.add("Industry: " + lead.getIndustry());
        if (lead.getEmployeeCount() != null) lines.add("Company size: " + lead.getEmployeeCount() + " employees");
        if (lead.getProduct() != null) lines.add("Product interest: " + lead.getProduct());
        if (lead.getEstimatedDealValue() != null) lines.add("Estimated deal value: " + lead.getEstimatedDealValue());
        if (lead.getSourceChannel() != null) lines.add("Source channel: " + lead.getSourceChannel());
        if (lead.getNotes() != null) lines.add("Notes from sales executive: " + lead.getNotes());
        return String.join("\n", lines);
    }

    private String normaliseLabel(String label, int score) {
        if (label != null) {
            String value = label.trim().toLowerCase();
            if (value.startsWith("hot")) return "Hot";
            if (value.startsWith("cold")) return "Cold";
            if (value.startsWith("warm")) return "Warm";
        }
        if (score >= 75) return "Hot";
        if (score >= 45) return "Warm";
        return "Cold";
    }
}
