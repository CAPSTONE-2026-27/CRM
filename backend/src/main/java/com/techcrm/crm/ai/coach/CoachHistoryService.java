package com.techcrm.crm.ai.coach;

import com.techcrm.crm.auth.AuthenticatedUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deal coach conversations, persisted per user.
 *
 * Every conversation is loaded by id AND the caller's organization and user id
 * ({@link #require}), and messages are only reached through a conversation that
 * passed that check — so one user can never read, extend or delete another's
 * chat, even by guessing ids. A conversation outside the caller's reach is a
 * 404, not a 403, so ids can't be probed.
 *
 * The question is saved as soon as it is asked and the answer when it finishes.
 * A user who leaves the page mid-answer and comes back (or reloads) still sees
 * what they asked, and the answer appears once the model is done, because the
 * backend keeps generating after the browser disconnects.
 */
@Service
public class CoachHistoryService {

    /** Messages loaded when a conversation is opened. Older ones stay stored. */
    static final int MESSAGE_LIMIT = 100;

    /** Conversations shown in the "Past chats" list. */
    static final int CONVERSATION_LIMIT = 50;

    static final int TITLE_CHARS = 60;

    private final CoachConversationRepository conversations;
    private final CoachMessageRepository messages;

    public CoachHistoryService(CoachConversationRepository conversations, CoachMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    public record ConversationSummary(Long id, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        static ConversationSummary from(CoachConversation c) {
            return new ConversationSummary(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    public record HistoryMessage(Long id, String role, String content, OffsetDateTime createdAt) {

        static HistoryMessage from(CoachMessage m) {
            return new HistoryMessage(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt());
        }
    }

    /** The caller's conversations, most recently active first. */
    @Transactional(readOnly = true)
    public List<ConversationSummary> conversations(AuthenticatedUser caller) {
        return conversations.findByOrganizationIdAndUserIdOrderByUpdatedAtDesc(
                        caller.organizationId(), caller.userId(), PageRequest.of(0, CONVERSATION_LIMIT))
                .stream().map(ConversationSummary::from).toList();
    }

    /** One of the caller's conversations, oldest message first. */
    @Transactional(readOnly = true)
    public List<HistoryMessage> messages(AuthenticatedUser caller, Long conversationId) {
        require(caller, conversationId);
        List<HistoryMessage> newestFirst = new ArrayList<>(messages
                .findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, MESSAGE_LIMIT))
                .stream().map(HistoryMessage::from).toList());
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /**
     * Saves the question, starting a new conversation when {@code conversationId}
     * is null. Returns the conversation it was saved to.
     */
    @Transactional
    public Long saveQuestion(AuthenticatedUser caller, Long conversationId, String question) {
        CoachConversation conversation = conversationId == null
                ? start(caller, question)
                : require(caller, conversationId);
        append(caller, conversation, "user", question);
        return conversation.getId();
    }

    /** Called off the request thread once the reply is complete. Blank replies are not stored. */
    @Transactional
    public void saveAnswer(AuthenticatedUser caller, Long conversationId, String answer) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        // Re-checked rather than trusted: the conversation may have been deleted
        // while the answer was being generated. Then there is nothing to add to.
        conversations.findByIdAndOrganizationIdAndUserId(conversationId, caller.organizationId(), caller.userId())
                .ifPresent(conversation -> append(caller, conversation, "assistant", answer.strip()));
    }

    /** Deletes one of the caller's conversations; its messages go with it (ON DELETE CASCADE). */
    @Transactional
    public void delete(AuthenticatedUser caller, Long conversationId) {
        conversations.delete(require(caller, conversationId));
    }

    private CoachConversation start(AuthenticatedUser caller, String question) {
        CoachConversation conversation = new CoachConversation();
        conversation.setOrganizationId(caller.organizationId());
        conversation.setUserId(caller.userId());
        conversation.setTitle(title(question));
        conversation.setUpdatedAt(OffsetDateTime.now());
        return conversations.save(conversation);
    }

    private void append(AuthenticatedUser caller, CoachConversation conversation, String role, String content) {
        CoachMessage message = new CoachMessage();
        message.setOrganizationId(caller.organizationId());
        message.setUserId(caller.userId());
        message.setConversationId(conversation.getId());
        message.setRole(role);
        message.setContent(content);
        messages.save(message);

        conversation.setUpdatedAt(OffsetDateTime.now());
        conversations.save(conversation);
    }

    private CoachConversation require(AuthenticatedUser caller, Long conversationId) {
        return conversations.findByIdAndOrganizationIdAndUserId(conversationId, caller.organizationId(), caller.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    /** The first question on one line, clipped at a word boundary. */
    static String title(String question) {
        String line = question.strip().replaceAll("\\s+", " ");
        if (line.length() <= TITLE_CHARS) {
            return line;
        }
        int cut = line.lastIndexOf(' ', TITLE_CHARS);
        return line.substring(0, cut > TITLE_CHARS / 2 ? cut : TITLE_CHARS) + "…";
    }
}
