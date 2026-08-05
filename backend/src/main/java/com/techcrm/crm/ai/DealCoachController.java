package com.techcrm.crm.ai;

import com.techcrm.crm.ai.AiChatClient.ChatMessage;
import com.techcrm.crm.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Deal coach — the in-app assistant.
 *
 * Streams over SSE because a full answer can take several seconds and watching
 * it arrive beats staring at a spinner. The event shape (`delta` / `error` /
 * `done`) is the contract the frontend's streamCopilotChat already parses.
 */
@RestController
@RequestMapping("/api/copilot")
public class DealCoachController {

    private static final Logger log = LoggerFactory.getLogger(DealCoachController.class);

    /** How long a single answer may take before the connection is closed. */
    private static final long STREAM_TIMEOUT_MS = 120_000;

    private static final String SYSTEM_PROMPT =
            "You are Deal coach, an assistant inside a CRM used by a sales team. "
                    + "Answer questions about leads, deals, accounts, cases and pipeline strategy. "
                    + "Be concise and practical — a few sentences or a short list, not an essay. "
                    + "You do not have live access to their records, so when a question depends on "
                    + "specific data, say what you would look at rather than inventing figures.";

    private final AiChatClient chatClient;

    public DealCoachController(AiChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public record ChatTurn(@NotBlank String role, @NotBlank String content) {
    }

    public record ChatRequest(@NotEmpty List<@Valid ChatTurn> messages) {
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@AuthenticationPrincipal AuthenticatedUser caller,
                           @Valid @RequestBody ChatRequest request) {

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        for (ChatTurn turn : request.messages()) {
            // Only the two roles the model accepts from a client; anything else
            // would let a crafted request inject a second system prompt.
            String role = "assistant".equalsIgnoreCase(turn.role()) ? "assistant" : "user";
            messages.add(new ChatMessage(role, turn.content()));
        }

        // Off the request thread: the model call blocks for seconds, and holding
        // a servlet thread for that would cap concurrent chats at the pool size.
        CompletableFuture.runAsync(() -> {
            try {
                chatClient.stream(messages,
                        delta -> send(emitter, "delta", "{\"text\":" + jsonString(delta) + "}"));
                send(emitter, "done", "{}");
                emitter.complete();
            } catch (Exception e) {
                log.warn("Deal coach stream failed for user {}: {}", caller.userId(), e.getMessage());
                send(emitter, "error", "{\"message\":" + jsonString(
                        "The assistant is unavailable right now. Please try again.") + "}");
                emitter.complete();
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // The client navigated away or the stream is already closed. Not an
            // error worth surfacing — the work is simply no longer wanted.
            log.debug("Deal coach client disconnected: {}", e.getMessage());
        }
    }

    /** Minimal JSON string escaping — the payloads here are a single field. */
    private String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
