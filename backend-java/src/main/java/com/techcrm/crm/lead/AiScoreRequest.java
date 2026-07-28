package com.techcrm.crm.lead;

import java.math.BigDecimal;

record AiScoreRequest(
        String fullName,
        String company,
        String industry,
        String employeeCount,
        String product,
        BigDecimal estimatedDealValue,
        String sourceChannel,
        String notes
) {}
