package com.techcrm.crm.support;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

public final class CaseDtos {

    private CaseDtos() {
    }

    public record CaseRequest(
            @NotBlank String subject,
            String source,
            String priority,
            OffsetDateTime slaDeadline,
            String status,
            Long accountId,
            Long assignedToId
    ) {
    }

    public record CaseResponse(
            String id,
            Integer caseNumber,
            String subject,
            String source,
            String priority,
            OffsetDateTime slaDeadline,
            String status,
            String accountId,
            String assignedToId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static CaseResponse from(CaseRecord c) {
            return new CaseResponse(
                    String.valueOf(c.getId()),
                    c.getCaseNumber(),
                    c.getSubject(),
                    c.getSource(),
                    c.getPriority(),
                    c.getSlaDeadline(),
                    c.getStatus(),
                    c.getAccountId() == null ? null : String.valueOf(c.getAccountId()),
                    c.getAssignedToId() == null ? null : String.valueOf(c.getAssignedToId()),
                    c.getCreatedAt(),
                    c.getUpdatedAt()
            );
        }
    }
}
