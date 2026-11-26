package com.techcrm.crm.marketing.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.techcrm.crm.ai.AiTask;
import com.techcrm.crm.marketing.common.MarketingJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls any OpenAI-compatible chat-completions endpoint. By default that is
 * the CRM's own model server (ai/server/main.py), the same one lead scoring
 * uses; set MARKETING_AI_BASE_URL / MARKETING_AI_MODEL to point the module
 * at a dedicated fine-tuned marketing model instead.
 *
 * Confidentiality: campaign briefs can hold unreleased products, pricing and
 * offers. Unless marketing.ai.allow-external-provider is explicitly true, this
 * client refuses to send anything to an endpoint that is not on this machine
 * or a private network, so confidential campaign data cannot leak to a hosted
 * provider (or into its training data) through a configuration slip.
 *
 * Prompts and replies are never logged: only the task label, sizes and timing.
 */
@Service
public class OpenAiCompatibleLlmService implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmService.class);

    private final RestClient restClient;
    private final String model;
    private final boolean configured;
    private final boolean selfHosted;
    private final boolean externalAllowed;

    private volatile Boolean lastCallSucceeded;
    private volatile OffsetDateTime lastCallAt;

    public OpenAiCompatibleLlmService(
            @Value("${marketing.ai.base-url:${ai.base-url:http://localhost:8001/v1}}") String baseUrl,
            @Value("${marketing.ai.model:${ai.model-name:crm-llama-3.1-8b-lora}}") String model,
            @Value("${marketing.ai.api-key:${ai.api-key:}}") String apiKey,
            @Value("${marketing.ai.request-timeout-ms:${ai.request-timeout-ms:180000}}") long timeoutMs,
            @Value("${marketing.ai.allow-external-provider:false}") boolean externalAllowed) {

        this.model = model;
        this.configured = baseUrl != null && !baseUrl.isBlank();
        this.selfHosted = configured && isPrivateHost(baseUrl);
        this.externalAllowed = externalAllowed;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(timeoutMs, 10_000)));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (configured) builder = builder.baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.restClient = builder.build();

        if (configured && !selfHosted && !externalAllowed) {
            log.warn("Marketing AI endpoint is not self-hosted and marketing.ai.allow-external-provider is false; "
                    + "campaign data will not be sent to it and the rules/template fallbacks will be used.");
        }
    }

    @Override
    public LlmReply complete(LlmRequest request) throws LlmUnavailableException {
        if (!configured) {
            throw new LlmUnavailableException("No marketing AI endpoint is configured");
        }
        if (!selfHosted && !externalAllowed) {
            throw new LlmUnavailableException(
                    "The configured AI endpoint is an external provider and is not authorised to receive campaign data");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())));
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());

        long started = System.nanoTime();
        try {
            String raw = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(AiTask.HEADER, AiTask.MARKETING)
                    .body(MarketingJson.write(body))
                    .retrieve()
                    .body(String.class);
            String content = extractContent(raw);
            if (content == null || content.isBlank()) {
                throw new LlmUnavailableException("The model returned an empty reply");
            }
            markCall(true);
            log.info("marketing-ai task={} model={} promptChars={} replyChars={} ms={}", request.task(), model,
                    request.userPrompt().length(), content.length(), (System.nanoTime() - started) / 1_000_000);
            return new LlmReply(content, model);
        } catch (RestClientException e) {
            markCall(false);
            log.warn("marketing-ai task={} model={} failed after {}ms: {}", request.task(), model,
                    (System.nanoTime() - started) / 1_000_000, e.getMessage());
            throw new LlmUnavailableException("The AI model could not be reached", e);
        } catch (LlmUnavailableException e) {
            markCall(false);
            throw e;
        }
    }

    private static String extractContent(String raw) {
        JsonNode root = MarketingJson.read(raw, JsonNode.class, null);
        if (root == null) return null;
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return null;
        JsonNode content = choices.get(0).path("message").path("content");
        return content.isMissingNode() || content.isNull() ? null : content.asText();
    }

    private void markCall(boolean ok) {
        lastCallSucceeded = ok;
        lastCallAt = OffsetDateTime.now();
    }

    static boolean isPrivateHost(String baseUrl) {
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            if (host == null) return false;
            String h = host.toLowerCase();
            if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".internal")
                    || h.endsWith(".lan") || h.equals("host.docker.internal")) {
                return true;
            }
            // Literal IPs only; a hostname is not resolved here, so a public
            // name that happens to resolve privately is still treated as external.
            if (!h.matches("[0-9.]+") && !h.contains(":")) return false;
            InetAddress address = InetAddress.getByName(h);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public boolean selfHosted() {
        return selfHosted;
    }

    @Override
    public boolean externalProviderAllowed() {
        return externalAllowed;
    }

    @Override
    public Boolean lastCallSucceeded() {
        return lastCallSucceeded;
    }

    @Override
    public OffsetDateTime lastCallAt() {
        return lastCallAt;
    }
}
