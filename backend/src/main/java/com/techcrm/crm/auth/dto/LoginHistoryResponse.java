package com.techcrm.crm.auth.dto;

import java.time.OffsetDateTime;

public record LoginHistoryResponse(
        String id,
        boolean success,
        String failureReason,
        String ipAddress,
        OffsetDateTime occurredAt
) {
}
