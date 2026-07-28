package com.techcrm.crm.workflow;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    public record WorkflowRequest(
            @NotBlank String name,
            String triggerEvent,
            String scope,
            String runMode,
            Boolean isActive,
            List<Map<String, Object>> nodes
    ) {
    }

    public record WorkflowResponse(
            String id,
            String name,
            String triggerEvent,
            String scope,
            String runMode,
            boolean isActive,
            List<Map<String, Object>> nodes,
            String createdById,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static WorkflowResponse from(WorkflowDefinition w) {
            return new WorkflowResponse(
                    String.valueOf(w.getId()),
                    w.getName(),
                    w.getTriggerEvent(),
                    w.getScope(),
                    w.getRunMode(),
                    w.isActive(),
                    w.getNodes(),
                    w.getCreatedById() == null ? null : String.valueOf(w.getCreatedById()),
                    w.getCreatedAt(),
                    w.getUpdatedAt()
            );
        }
    }
}
