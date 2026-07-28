package com.techcrm.crm.audit;

import java.time.OffsetDateTime;

public record AuditLogResponse(
        String id,
        String actorUserId,
        String action,
        String entityType,
        String entityId,
        String detail,
        OffsetDateTime occurredAt
) {
}
