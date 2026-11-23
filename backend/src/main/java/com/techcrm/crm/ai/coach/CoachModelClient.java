package com.techcrm.crm.ai.coach;

import com.fasterxml.jackson.databind.JsonNode;
import com.techcrm.crm.ai.AiChatClient.ChatMessage;
import com.techcrm.crm.ai.AiJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The deal coach's model: Qwen3 4B Instruct served by a local Ollama.
 *
 * Separate from {@link com.techcrm.crm.ai.AiChatClient} on purpose. That client
 * points at the fine-tuned Llama server (ai/server/main.py), whose adapters are
 * trained for scoring and extraction, not conversation. The coach is open-ended
 * chat over a context block, which a small instruct model handles well and
 * answers several times faster — and moving it off the Llama server stops a
 * long chat from holding that server's single inference lock while a lead is
 * waiting to be scored.
 *
 * Talks Ollama's native {@code /api/chat} rather than its OpenAI-compatible
 * {@code /v1} route, because only the native API accepts {@code num_ctx} and
 * {@code keep_alive}. Ollama's default context window is small enough that the
 * CRM snapshot plus a few turns of history would be silently truncated from the
 * front — which drops the system prompt, guardrails first.
 */
@Component
public class CoachModelClient {

    private static final Logger log = LoggerFactory.getLogger(CoachModelClient.class);

    private final RestClient restClient;
    private final String model;
    private final boolean enabled;
    private final Map<String, Object> options;
    private final String keepAlive;

    public CoachModelClient(
            @Value("${ai.coach.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ai.coach.model:qwen3:4b-instruct}") String model,
            @Value("${ai.coach.request-timeout-ms:120000}") long timeoutMs,
            @Value("${ai.coach.temperature:0.2}") double temperature,
            @Value("${ai.coach.context-tokens:8192}") int contextTokens,
            @Value("${ai.coach.max-output-tokens:700}") int maxOutputTokens,
            @Value("${ai.coach.keep-alive:30m}") String keepAlive) {

        this.model = model;
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.keepAlive = keepAlive;
        // Low temperature: the coach quotes figures from the snapshot, and the
        // more it samples the more readily a 4B model drifts off them. top_k/top_p
        // are Qwen's own recommended non-thinking settings.
        this.options = Map.of(
                "temperature", temperature,
                "top_p", 0.8,
                "top_k", 20,
                "repeat_penalty", 1.05,
                "num_ctx", contextTokens,
                "num_predict", maxOutputTokens);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.min(timeoutMs, 5_000)));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = RestClient.builder()
                .baseUrl(enabled ? baseUrl : "http://localhost")
                .requestFactory(requestFactory)
                .build();
    }

    public String getModel() {
        return model;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Streams a reply, invoking {@code onDelta} per chunk. Blocks until the model
     * finishes, so call it off the request thread.
     *
     * @throws IllegalStateException when Ollama is unreachable, the model is not
     *                               pulled, or the coach is switched off
     */
    public void stream(List<ChatMessage> messages, Consumer<String> onDelta) {
        if (!enabled) {
            throw new IllegalStateException("Deal coach model is not configured (ai.coach.base-url is blank)");
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "stream", true,
                // Qwen3 hybrid builds think before answering unless told not to.
                // The instruct build ignores this; it matters if someone points
                // the coach at a hybrid tag, where it keeps chain-of-thought out
                // of the reply (CoachGuardrails.ThinkFilter is the second line).
                "think", false,
                "keep_alive", keepAlive,
                "options", options);
        try {
            restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new IllegalStateException("Ollama returned " + response.getStatusCode());
                        }
                        // Newline-delimited JSON, one object per chunk, the last
                        // carrying "done": true.
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank()) {
                                    continue;
                                }
                                JsonNode chunk = AiJson.parse(line);
                                if (chunk == null) {
                                    continue;
                                }
                                if (chunk.hasNonNull("error")) {
                                    throw new IllegalStateException("Ollama: " + chunk.get("error").asText());
                                }
                                JsonNode content = chunk.path("message").path("content");
                                if (content.isTextual() && !content.asText().isEmpty()) {
                                    onDelta.accept(content.asText());
                                }
                                if (chunk.path("done").asBoolean(false)) {
                                    break;
                                }
                            }
                        }
                        return null;
                    });
        } catch (RestClientException | UncheckedIOException e) {
            throw new IllegalStateException("Deal coach model stream failed: " + e.getMessage(), e);
        }
    }

    /** True when Ollama answers and has the configured model pulled. */
    public boolean isModelAvailable() {
        if (!enabled) {
            return false;
        }
        try {
            JsonNode tags = AiJson.parse(restClient.get().uri("/api/tags").retrieve().body(String.class));
            if (tags == null) {
                return false;
            }
            for (JsonNode entry : tags.path("models")) {
                String name = entry.path("name").asText("");
                // "qwen3:4b-instruct" is listed as-is; a bare name is ":latest".
                if (name.equals(model) || name.equals(model + ":latest")) {
                    return true;
                }
            }
            log.warn("Ollama is up but model '{}' is not pulled — run: ollama pull {}", model, model);
            return false;
        } catch (RestClientException e) {
            log.debug("Ollama unreachable: {}", e.getMessage());
            return false;
        }
    }
}
