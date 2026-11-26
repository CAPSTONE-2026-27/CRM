package com.techcrm.crm.ai.coach;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A user's coach conversations are theirs alone: listed, opened, extended and deleted by their own ids. */
class CoachHistoryServiceTest {

    private static final AuthenticatedUser REP = new AuthenticatedUser(11L, 1L, Role.SALES_REP, List.of(), false);

    private CoachConversationRepository conversations;
    private CoachMessageRepository messages;
    private CoachHistoryService service;

    @BeforeEach
    void setUp() {
        conversations = mock(CoachConversationRepository.class);
        messages = mock(CoachMessageRepository.class);
        service = new CoachHistoryService(conversations, messages);
        when(conversations.save(any(CoachConversation.class))).thenAnswer(inv -> {
            CoachConversation c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(500L);
            }
            return c;
        });
    }

    private static CoachConversation owned(long id) {
        CoachConversation c = new CoachConversation();
        c.setId(id);
        c.setOrganizationId(1L);
        c.setUserId(11L);
        c.setTitle("Earlier chat");
        return c;
    }

    private static CoachMessage message(long id, String role, String content) {
        CoachMessage m = new CoachMessage();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void listsOnlyTheCallersConversations() {
        when(conversations.findByOrganizationIdAndUserIdOrderByUpdatedAtDesc(eq(1L), eq(11L), any(Pageable.class)))
                .thenReturn(List.of(owned(7)));

        assertThat(service.conversations(REP)).extracting(CoachHistoryService.ConversationSummary::id).containsExactly(7L);
    }

    @Test
    void opensAConversationOldestMessageFirst() {
        when(conversations.findByIdAndOrganizationIdAndUserId(7L, 1L, 11L)).thenReturn(Optional.of(owned(7)));
        when(messages.findByConversationIdOrderByIdDesc(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(message(2, "assistant", "Focus on Reliance"), message(1, "user", "What next?")));

        assertThat(service.messages(REP, 7L))
                .extracting(CoachHistoryService.HistoryMessage::content)
                .containsExactly("What next?", "Focus on Reliance");
    }

    @Test
    void someoneElsesConversationIsNotFound() {
        when(conversations.findByIdAndOrganizationIdAndUserId(99L, 1L, 11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.messages(REP, 99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> service.saveQuestion(REP, 99L, "hi")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.delete(REP, 99L)).isInstanceOf(ResponseStatusException.class);
        verify(messages, never()).findByConversationIdOrderByIdDesc(anyLong(), any());
        verify(messages, never()).save(any());
        verify(conversations, never()).delete(any());
    }

    @Test
    void firstQuestionStartsAConversationTitledFromIt() {
        Long id = service.saveQuestion(REP, null, "Which of my deals need attention this week?");

        assertThat(id).isEqualTo(500L);
        verify(conversations, atLeastOnce()).save(argThat(c -> c.getUserId() == 11L && c.getOrganizationId() == 1L
                && "Which of my deals need attention this week?".equals(c.getTitle())));
        verify(messages).save(argThat(m -> m.getConversationId() == 500L && "user".equals(m.getRole())));
    }

    @Test
    void answerForADeletedConversationIsDropped() {
        // The user deleted the chat while its answer was still generating.
        when(conversations.findByIdAndOrganizationIdAndUserId(7L, 1L, 11L)).thenReturn(Optional.empty());

        service.saveAnswer(REP, 7L, "Focus on Reliance");

        verify(messages, never()).save(any());
    }

    @Test
    void doesNotStoreABlankAnswer() {
        service.saveAnswer(REP, 7L, "   ");

        verify(messages, never()).save(any());
    }

    @Test
    void longQuestionsAreClippedAtAWordForTheTitle() {
        String title = CoachHistoryService.title(
                "How should I handle the price objection on the Reliance POS rollout deal before Friday's meeting?");

        assertThat(title).hasSizeLessThanOrEqualTo(CoachHistoryService.TITLE_CHARS + 1).endsWith("…");
        assertThat(title).doesNotContain("  ");
    }
}
