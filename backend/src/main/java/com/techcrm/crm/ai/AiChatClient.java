package com.techcrm.crm.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Talks to any OpenAI-compatible chat-completions endpoint.
 *
 * That interface is deliberately the integration point rather than a bespoke
 * scoring service: a hosted API (Groq) and a self-hosted server (vLLM, Ollama,
 * llama.cpp) expose the same contract, so pointing this at a fine-tuned model
 * later is a configuration change — AI_BASE_URL, AI_MODEL_NAME, AI_API_KEY —
 * with no code to touch.
 */
@Component
public class AiChatClient {

    private static final Logger log = LoggerFactory.getLogger(AiChatClient.class);

    private final RestClient restClient;
    private final String model;
    private final boolean configured;

    public AiChatClient(
            @Value("${ai.base-url:https://api.groq.com/openai/v1}") String baseUrl,
            @Value("${ai.model-name:llama-3.1-8b-instant}") String model,
            @Value("${ai.api-key:}") String apiKey,
            @Value("${ai.request-timeout-ms:30000}") long timeoutMs) {

        this.model = model;
        // A self-hosted server needs no key; a hosted one does. Blank simply
        // means "don't send the header", not "misconfigured".
        this.configured = baseUrl != null && !baseUrl.isBlank();

        // Timeouts matter here: a hung model call would otherwise hold an HTTP
        // worker thread for as long as the socket stays open.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);

        if (apiKey != null && !apiKey.isBlank()) {
            builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.restClient = builder.build();
    }

    public String getModel() {
        return model;
    }

    /* ---- OpenAI chat-completions wire format ---- */

    public record ChatMessage(String role, String content) {

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }
    }

    private record ChatRequest(String model, List<ChatMessage> messages) {
    }

    private record StreamingChatRequest(String model, List<ChatMessage> messages, boolean stream) {
    }

    private record ResponseMessage(String content) {
    }

    private record Choice(ResponseMessage message) {
    }

    private record ChatResponse(List<Choice> choices) {
    }

    /**
     * Streams a reply token by token, invoking {@code onDelta} for each chunk.
     *
     * Uses the same chat-completions endpoint with {@code stream: true}, which
     * responds as server-sent events. Blocks until the model finishes, so call
     * it off the request thread.
     *
     * @throws IllegalStateException when the model is unreachable or not configured
     */
    public void stream(List<ChatMessage> messages, Consumer<String> onDelta) {
        if (!configured) {
            throw new IllegalStateException("AI base URL is not configured");
        }
        try {
            restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(new StreamingChatRequest(model, messages, true))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new IllegalStateException("Model returned " + response.getStatusCode());
                        }
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) {
                                    continue;
                                }
                                String payload = line.substring(5).trim();
                                // The provider signals end-of-stream with a
                                // literal [DONE] rather than closing cleanly.
                                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                                    continue;
                                }
                                String delta = extractDelta(payload);
                                if (delta != null && !delta.isEmpty()) {
                                    onDelta.accept(delta);
                                }
                            }
                        }
                        return null;
                    });
        } catch (RestClientException | UncheckedIOException e) {
            throw new IllegalStateException("Model stream failed: " + e.getMessage(), e);
        }
    }

    /** Pulls choices[0].delta.content out of one streamed chunk. */
    private String extractDelta(String payload) {
        JsonNode root = AiJson.parse(payload);
        if (root == null) {
            return null;
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode delta = choices.get(0).get("delta");
        if (delta == null) {
            return null;
        }
        JsonNode content = delta.get("content");
        return content == null || content.isNull() ? null : content.asText();
    }

    /**
     * Returns the assistant's reply, or null when the model is unreachable or
     * misconfigured. Callers degrade rather than fail: an unavailable model
     * must never block a user from saving their own work.
     */
    public String complete(List<ChatMessage> messages) {
        if (!configured) {
            log.debug("AI base URL is not configured; skipping model call");
            return null;
        }
        try {
            ChatResponse response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ChatRequest(model, messages))
                    .retrieve()
                    .body(ChatResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                return null;
            }
            ResponseMessage message = response.choices().get(0).message();
            return message == null ? null : message.content();
        } catch (RestClientException e) {
            log.warn("AI request failed ({}): {}", model, e.getMessage());
            return null;
        }
    }
}
