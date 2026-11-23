package com.techcrm.crm.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** The most recent entry of one kind for one record — e.g. whether a
     *  contract has already been emailed. */
    Optional<AuditLog> findFirstByOrganizationIdAndEntityTypeAndEntityIdAndActionOrderByOccurredAtDesc(
            Long organizationId, String entityType, String entityId, String action);

    Page<AuditLog> findByOrganizationIdOrderByOccurredAtDesc(Long organizationId, Pageable pageable);

    Page<AuditLog> findByOrganizationIdAndActorUserIdOrderByOccurredAtDesc(Long organizationId, Long actorUserId, Pageable pageable);
}
