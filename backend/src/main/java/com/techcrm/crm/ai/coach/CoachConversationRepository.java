package com.techcrm.crm.ai.coach;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CoachConversationRepository extends JpaRepository<CoachConversation, Long> {

    List<CoachConversation> findByOrganizationIdAndUserIdOrderByUpdatedAtDesc(Long organizationId, Long userId,
                                                                               Pageable pageable);

    /** The only way to load a conversation by id: always together with its owner. */
    Optional<CoachConversation> findByIdAndOrganizationIdAndUserId(Long id, Long organizationId, Long userId);
}
