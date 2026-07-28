package com.techcrm.crm.rpa;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

public final class RpaDtos {

    private RpaDtos() {
    }

    public record RpaBotRequest(
            @NotBlank String name,
            String platform,
            String botType,
            String triggerSource,
            String credentialVaultRef,
            String environment,
            String region,
            String version,
            String status
    ) {
    }

    public record RpaBotResponse(
            String id,
            String name,
            String platform,
            String botType,
            String triggerSource,
            String credentialVaultRef,
            String environment,
            String region,
            String version,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static RpaBotResponse from(RpaBot b) {
            return new RpaBotResponse(
                    String.valueOf(b.getId()), b.getName(), b.getPlatform(), b.getBotType(),
                    b.getTriggerSource(), b.getCredentialVaultRef(), b.getEnvironment(),
                    b.getRegion(), b.getVersion(), b.getStatus(), b.getCreatedAt(), b.getUpdatedAt());
        }
    }

    /** Nested bot summary so the control room can label a run without a second
     *  request, matching what the runs table renders. */
    public record BotSummary(String name, String platform) {
    }

    public record RpaBotRunResponse(
            String id,
            String botId,
            BotSummary bot,
            String workflowDefinitionId,
            String status,
            Integer tasksCompleted,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String logs,
            String triggeredBy,
            String errorMessage,
            String aiModelVersion,
            Double aiConfidence
    ) {
        public static RpaBotRunResponse from(RpaBotRun r, RpaBot bot) {
            return new RpaBotRunResponse(
                    String.valueOf(r.getId()),
                    String.valueOf(r.getBotId()),
                    bot == null ? null : new BotSummary(bot.getName(), bot.getPlatform()),
                    r.getWorkflowDefinitionId() == null ? null : String.valueOf(r.getWorkflowDefinitionId()),
                    r.getStatus(), r.getTasksCompleted(), r.getStartedAt(), r.getFinishedAt(),
                    r.getLogs(), r.getTriggeredBy(), r.getErrorMessage(),
                    r.getAiModelVersion(), r.getAiConfidence());
        }
    }

    public record QueuedResponse(boolean queued) {
    }
}
