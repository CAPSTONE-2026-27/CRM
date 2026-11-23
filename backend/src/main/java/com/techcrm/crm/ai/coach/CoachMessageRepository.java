package com.techcrm.crm.ai.coach;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Messages are only ever reached through a conversation the caller owns
 * (CoachHistoryService checks that first), so these finders take a
 * conversation id alone.
 */
public interface CoachMessageRepository extends JpaRepository<CoachMessage, Long> {

    /** Newest first — the caller reverses it for display. */
    List<CoachMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);
}
