package com.techcrm.crm.lead;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record LeadResponse(
        String id,
        String fullName,
        String company,
        String industry,
        String employeeCount,
        String email,
        String phone,
        String product,
        BigDecimal estimatedDealValue,
        String sourceChannel,
        String captureMethod,
        String notes,
        Integer aiScore,
        String aiScoreLabel,
        String aiScoreReason,
        String status,
        String assignedToId,

        String qualificationStatus,
        Double qualificationProbability,
        String qualificationReasoning,

        OffsetDateTime assignedAt,
        String assignmentStatus,

        String contactStatus,
        OffsetDateTime contactStatusUpdatedAt,
        String contactNotes,

        String convertedDealId,
        OffsetDateTime convertedAt,

        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
