package com.techcrm.crm.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /** Runs in its own transaction so an audit-write failure can never roll
     *  back the real business transaction it's instrumenting — an audit
     *  outage shouldn't take down lead/user management. Failures are
     *  caught and logged, never rethrown. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long organizationId, Long actorUserId, String action, String entityType, String entityId, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setOrganizationId(organizationId);
            entry.setActorUserId(actorUserId);
            entry.setAction(action);
            entry.setEntityType(entityType);
            entry.setEntityId(entityId);
            entry.setDetail(detail);
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to record audit log entry: action={}, entityType={}, entityId={}", action, entityType, entityId, e);
        }
    }
}
