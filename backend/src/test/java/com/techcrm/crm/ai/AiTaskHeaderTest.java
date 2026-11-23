package com.techcrm.crm.ai;

import com.sun.net.httpserver.HttpServer;
import com.techcrm.crm.ai.AiChatClient.ChatMessage;
import com.techcrm.crm.lead.AiScoringClient;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.score.LeadMeetingExtractionClient;
import com.techcrm.crm.lead.score.LeadMeetingExtractionClient.ExtractionRequest;
import com.techcrm.crm.marketing.ai.LlmService.LlmRequest;
import com.techcrm.crm.marketing.ai.OpenAiCompatibleLlmService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model server picks its LoRA adapter from the X-CRM-Task header. These
 * tests run each real client against a local HTTP server and check the header
 * actually leaves the JVM — a missing header degrades silently to prompt
 * matching, so nothing else would catch it.
 */
class AiTaskHeaderTest {

    private static final String REPLY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"score\\\": 60}\"}}]}";

    private HttpServer server;
    /** One entry per request: the X-CRM-Task value, or null when absent. */
    private final List<String> taskHeaders = Collections.synchronizedList(new ArrayList<>());
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            taskHeaders.add(exchange.getRequestHeaders().getFirst(AiTask.HEADER));
            exchange.getRequestBody().readAllBytes();
            byte[] body = REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AiChatClient chatClient() {
        return new AiChatClient(baseUrl, "crm-llama-3.1-8b-lora", "", 5000);
    }

    @Test
    void taggedCallSendsTheHeader() {
        chatClient().complete(AiTask.GENERAL, List.of(ChatMessage.user("hi")));
        assertThat(taskHeaders).containsExactly(AiTask.GENERAL);
    }

    @Test
    void untaggedCallSendsNoHeader() {
        chatClient().complete(List.of(ChatMessage.user("hi")));
        assertThat(taskHeaders).containsExactly((String) null);
    }

    @Test
    void leadScoringIsTagged() {
        Lead lead = new Lead();
        lead.setFullName("Priya Menon");
        lead.setCompany("Pioneer Hospitality");
        new AiScoringClient(chatClient()).score(lead);
        assertThat(taskHeaders).containsExactly(AiTask.LEAD_SCORING);
    }

    @Test
    void meetingExtractionIsTagged() {
        new LeadMeetingExtractionClient(chatClient(), "v1").extract(new ExtractionRequest(
                "Priya Menon", "Pioneer Hospitality", null, null, null, null, null, null, null, null,
                null, 1, null, null, "Discussed budget and timeline."));
        assertThat(taskHeaders).containsExactly(AiTask.MEETING_EXTRACTION);
    }

    @Test
    void marketingIsTagged() throws Exception {
        new OpenAiCompatibleLlmService(baseUrl, "m", "", 5000, false)
                .complete(new LlmRequest("lead-evaluation", "system", "user", 0.0, 10));
        assertThat(taskHeaders).containsExactly(AiTask.MARKETING);
    }
}
