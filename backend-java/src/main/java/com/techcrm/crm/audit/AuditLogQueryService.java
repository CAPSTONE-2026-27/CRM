package com.techcrm.crm.audit;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.user.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogQueryService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogQueryService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /** ADMIN sees every event in the org; everyone else sees only rows
     *  where they're the actor — same idiom as LeadService's scoping. */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(AuthenticatedUser caller, Pageable pageable) {
        Page<AuditLog> page = caller.role() == Role.ADMIN
                ? auditLogRepository.findByOrganizationIdOrderByOccurredAtDesc(caller.organizationId(), pageable)
                : auditLogRepository.findByOrganizationIdAndActorUserIdOrderByOccurredAtDesc(caller.organizationId(), caller.userId(), pageable);

        return page.map(this::toResponse);
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(
                String.valueOf(log.getId()),
                log.getActorUserId() != null ? String.valueOf(log.getActorUserId()) : null,
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getDetail(),
                log.getOccurredAt()
        );
    }
}
