package com.techcrm.crm.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByOrganizationIdOrderByOccurredAtDesc(Long organizationId, Pageable pageable);

    Page<AuditLog> findByOrganizationIdAndActorUserIdOrderByOccurredAtDesc(Long organizationId, Long actorUserId, Pageable pageable);
}
