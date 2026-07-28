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
        OffsetDateTime createdAt
) {}