package com.techcrm.crm.ai;

import com.techcrm.crm.ai.AiChatClient.ChatMessage;
import com.techcrm.crm.ai.coach.CoachContextService;
import com.techcrm.crm.ai.coach.CoachContextService.CoachContext;
import com.techcrm.crm.ai.coach.CoachGuardrails;
import com.techcrm.crm.ai.coach.CoachHistoryService;
import com.techcrm.crm.ai.coach.CoachHistoryService.ConversationSummary;
import com.techcrm.crm.ai.coach.CoachHistoryService.HistoryMessage;
import com.techcrm.crm.ai.coach.CoachModelClient;
import com.techcrm.crm.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deal coach — the in-app assistant.
 *
 * Runs on Qwen3 4B Instruct via Ollama ({@link CoachModelClient}), grounded in
 * a snapshot of the caller's own CRM data ({@link CoachContextService}) and
 * constrained by {@link CoachGuardrails}. If Ollama is down and
 * {@code ai.coach.fallback-to-default} is on, the same grounded prompt goes to
 * the default model server instead, so the coach degrades rather than dies.
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
    private static final long STREAM_TIMEOUT_MS = 180_000;

    private final CoachModelClient coachModel;
    private final AiChatClient fallbackClient;
    private final CoachContextService contextService;
    private final CoachHistoryService historyService;
    private final boolean fallbackEnabled;
    private final ThreadPoolExecutor coachExecutor;

    public DealCoachController(CoachModelClient coachModel,
                               AiChatClient fallbackClient,
                               CoachContextService contextService,
                               CoachHistoryService historyService,
                               @Value("${ai.coach.fallback-to-default:true}") boolean fallbackEnabled,
                               @Value("${ai.coach.max-concurrent-chats:4}") int maxConcurrentChats,
                               @Value("${ai.coach.max-queued-chats:16}") int maxQueuedChats) {
        this.coachModel = coachModel;
        this.fallbackClient = fallbackClient;
        this.contextService = contextService;
        this.historyService = historyService;
        this.fallbackEnabled = fallbackEnabled;
        // Its own bounded pool, not the common ForkJoinPool: a model call blocks
        // for up to three minutes, and the common pool is sized to the core count
        // and shared with every parallel stream in the app — a handful of open
        // chats would starve it. Bounded because Ollama serves one or two
        // generations at a time anyway, so an unbounded queue would only let
        // requests pile up until they timed out. Past the queue, callers are told
        // the coach is busy instead of waiting on a connection that will expire.
        this.coachExecutor = new ThreadPoolExecutor(
                1, Math.max(1, maxConcurrentChats),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, maxQueuedChats)),
                runnable -> {
                    Thread thread = new Thread(runnable, "deal-coach");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        coachExecutor.shutdownNow();
    }

    public record ChatTurn(@NotBlank String role, @NotBlank @Size(max = 8_000) String content) {
    }

    /** {@code conversationId} null starts a new conversation. */
    public record ChatRequest(Long conversationId, @NotEmpty @Size(max = 60) List<@Valid ChatTurn> messages) {
    }

    public record CoachStatus(String provider, String model, boolean available, String scope) {
    }

    /** What the coach runs on and what it can see — for the status card. */
    @GetMapping("/status")
    public CoachStatus status(@AuthenticationPrincipal AuthenticatedUser caller) {
        return new CoachStatus("Ollama", coachModel.getModel(), coachModel.isModelAvailable(),
                contextService.scopeFor(caller));
    }

    /** The caller's past chats, most recent first — the "Past chats" list. */
    @GetMapping("/conversations")
    public List<ConversationSummary> conversations(@AuthenticationPrincipal AuthenticatedUser caller) {
        return historyService.conversations(caller);
    }

    /** One of the caller's chats, oldest message first. 404 for anyone else's. */
    @GetMapping("/conversations/{conversationId}/messages")
    public List<HistoryMessage> messages(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable Long conversationId) {
        return historyService.messages(caller, conversationId);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@AuthenticationPrincipal AuthenticatedUser caller,
                                                   @PathVariable Long conversationId) {
        historyService.delete(caller, conversationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@AuthenticationPrincipal AuthenticatedUser caller,
                           @Valid @RequestBody ChatRequest request) {

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);

        List<ChatMessage> history = CoachGuardrails.sanitizeHistory(request.messages().stream()
                .map(turn -> new ChatMessage(turn.role(), turn.content()))
                .toList());

        if (history.isEmpty() || !"user".equals(history.get(history.size() - 1).role())) {
            send(emitter, "error", "{\"message\":" + jsonString("Send a question to the deal coach.") + "}");
            emitter.complete();
            return emitter;
        }

        // Screened before the model is called: a fixed refusal is guaranteed to
        // hold, a 4B model's judgement under adversarial prompting is not. The
        // whole window is screened, not just this question — the client replays
        // the conversation on every request, so an injection parked in an earlier
        // turn reaches the model just as surely as one in the newest.
        String question = history.get(history.size() - 1).content();
        // Saved before the model runs, so the question is still there if the
        // user leaves the page or reloads while the answer is being written.
        // Throws 404 for a conversation that isn't the caller's.
        Long conversationId = historyService.saveQuestion(caller, request.conversationId(), question);
        // First event, so the browser can file a new chat under its id before
        // any of the answer arrives.
        send(emitter, "conversation", "{\"id\":" + conversationId + "}");

        Optional<String> refusal = CoachGuardrails.screenConversation(history);
        if (refusal.isPresent()) {
            log.info("Deal coach refused a prompt-injection attempt from user {}", caller.userId());
            historyService.saveAnswer(caller, conversationId, refusal.get());
            send(emitter, "delta", "{\"text\":" + jsonString(refusal.get()) + "}");
            send(emitter, "done", "{}");
            emitter.complete();
            return emitter;
        }

        // Built on the request thread, inside a read-only transaction, from the
        // caller's own visibility scope.
        CoachContext context = contextService.build(caller);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(CoachGuardrails.systemPrompt(
                context.userName(), context.roleLabel(), context.scopeDescription(), context.snapshot())));
        messages.addAll(history);

        // Off the request thread: the model call blocks for seconds, and holding
        // a servlet thread for that would cap concurrent chats at the pool size.
        Runnable answerTask = () -> {
            AtomicBoolean anyOutput = new AtomicBoolean(false);
            // The whole visible reply, for the history. Built regardless of
            // whether the browser is still listening: a user who navigated away
            // mid-answer gets the finished reply when they come back.
            StringBuilder answer = new StringBuilder();
            CoachGuardrails.ThinkFilter filter = new CoachGuardrails.ThinkFilter();
            try {
                try {
                    coachModel.stream(messages, delta -> emit(emitter, filter.accept(delta), anyOutput, answer));
                } catch (IllegalStateException primaryFailure) {
                    // Only fall back if nothing has been shown yet — splicing a
                    // second model's answer onto half of the first's reads as
                    // one garbled reply.
                    if (!fallbackEnabled || anyOutput.get()) {
                        throw primaryFailure;
                    }
                    log.warn("Deal coach model ({}) unavailable, falling back to {}: {}",
                            coachModel.getModel(), fallbackClient.getModel(), primaryFailure.getMessage());
                    fallbackClient.stream(messages, delta -> emit(emitter, filter.accept(delta), anyOutput, answer));
                }
                emit(emitter, filter.flush(), anyOutput, answer);
                historyService.saveAnswer(caller, conversationId, answer.toString());
                send(emitter, "done", "{}");
                emitter.complete();
            } catch (Exception e) {
                log.warn("Deal coach stream failed for user {}: {}", caller.userId(), e.getMessage());
                send(emitter, "error", "{\"message\":" + jsonString(
                        "The assistant is unavailable right now. Please try again.") + "}");
                emitter.complete();
            }
        };

        try {
            coachExecutor.execute(answerTask);
        } catch (RejectedExecutionException saturated) {
            log.warn("Deal coach queue full, rejecting chat from user {}", caller.userId());
            send(emitter, "error", "{\"message\":" + jsonString(
                    "The assistant is handling too many chats right now. Please try again in a moment.") + "}");
            emitter.complete();
        }

        return emitter;
    }

    private void emit(SseEmitter emitter, String text, AtomicBoolean anyOutput, StringBuilder answer) {
        if (text == null || text.isEmpty()) {
            return;
        }
        anyOutput.set(true);
        answer.append(text);
        send(emitter, "delta", "{\"text\":" + jsonString(text) + "}");
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
