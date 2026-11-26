package com.techcrm.crm.marketing.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.techcrm.crm.marketing.ai.LlmService.LlmReply;
import com.techcrm.crm.marketing.ai.LlmService.LlmRequest;
import com.techcrm.crm.marketing.ai.LlmService.LlmUnavailableException;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.content.ContentChannel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline B, model step: writes content for the requested channels in one
 * call, so the headline, email and poster share one voice.
 *
 * Every channel's output passes ContentOutputValidator. When any channel
 * fails, the model gets one retry told exactly what was rejected; channels
 * still failing after that come back in {@link Result#rejected}, and the
 * caller decides whether to fall back to a template or report an error.
 */
@Component
public class ContentGenerationAi {

    private static final int MAX_TOKENS = 1800;
    private static final double TEMPERATURE = 0.4;

    private final LlmService llm;
    private final PromptLibrary prompts;

    public ContentGenerationAi(LlmService llm, PromptLibrary prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    /** A/B context for writing variant B. */
    public record VariantSpec(String element, Map<ContentChannel, Map<String, String>> variantA) {
    }

    /** @param userPrompt the first-attempt prompt (never the retry), stored for retraining */
    public record Result(
            Map<ContentChannel, ContentOutputValidator.Result> accepted,
            Map<ContentChannel, List<String>> rejected,
            String model,
            String userPrompt
    ) {
    }

    public Result generate(ContentSource source, List<ContentChannel> channels, VariantSpec variant, String extraInstructions)
            throws LlmUnavailableException {

        String userPrompt = buildUserPrompt(source, channels, variant, extraInstructions);
        LlmReply reply = llm.complete(new LlmRequest("content-generation", prompts.contentGeneration(), userPrompt,
                TEMPERATURE, MAX_TOKENS));
        Map<ContentChannel, ContentOutputValidator.Result> results = check(reply.content(), channels, source);

        List<String> problems = new ArrayList<>();
        results.values().forEach(r -> problems.addAll(r.errors()));
        if (!problems.isEmpty()) {
            String retryPrompt = userPrompt + "\n\nYour previous reply was rejected for these reasons:\n- "
                    + String.join("\n- ", problems.subList(0, Math.min(problems.size(), 12)))
                    + "\nWrite it again, fixing every point, and reply with only the JSON object.";
            reply = llm.complete(new LlmRequest("content-generation-retry", prompts.contentGeneration(), retryPrompt,
                    TEMPERATURE, MAX_TOKENS));
            Map<ContentChannel, ContentOutputValidator.Result> second = check(reply.content(), channels, source);
            // Keep whichever attempt was valid for each channel.
            for (ContentChannel ch : channels) {
                if (!results.get(ch).ok() && second.get(ch).ok()) results.put(ch, second.get(ch));
            }
        }

        Map<ContentChannel, ContentOutputValidator.Result> accepted = new LinkedHashMap<>();
        Map<ContentChannel, List<String>> rejected = new LinkedHashMap<>();
        results.forEach((ch, r) -> {
            if (r.ok()) accepted.put(ch, r);
            else rejected.put(ch, r.errors());
        });
        return new Result(accepted, rejected, reply.model(), userPrompt);
    }

    private static Map<ContentChannel, ContentOutputValidator.Result> check(String reply, List<ContentChannel> channels,
                                                                            ContentSource source) {
        JsonNode root = LeadEvaluationValidator.extractObject(reply);
        Map<ContentChannel, ContentOutputValidator.Result> out = new LinkedHashMap<>();
        for (ContentChannel ch : channels) {
            JsonNode node = root == null ? null : root.get(ch.jsonKey());
            out.put(ch, ContentOutputValidator.validate(ch, node, source.groundingText()));
        }
        return out;
    }

    static String buildUserPrompt(ContentSource source, List<ContentChannel> channels, VariantSpec variant,
                                  String extraInstructions) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("brief", source.brief());

        Map<String, Object> style = new LinkedHashMap<>(source.style());
        String extra = PromptLibrary.clean(extraInstructions, 500);
        if (extra != null) style.put("regeneration_feedback", extra);
        doc.put("style", style);

        Map<String, Object> channelDoc = new LinkedHashMap<>();
        for (ContentChannel ch : channels) {
            Map<String, Object> fields = new LinkedHashMap<>();
            ch.fieldLimits().forEach((k, v) -> fields.put(k, "max " + v + " characters"
                    + (ch.requiredFields().contains(k) ? ", required" : ", optional")));
            channelDoc.put(ch.jsonKey(), fields);
        }
        doc.put("channels", channelDoc);

        if (variant != null) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("element", variant.element());
            Map<String, Object> a = new LinkedHashMap<>();
            variant.variantA().forEach((ch, f) -> a.put(ch.jsonKey(), f));
            v.put("variant_a", a);
            doc.put("variant", v);
        }
        return MarketingJson.write(doc);
    }
}
