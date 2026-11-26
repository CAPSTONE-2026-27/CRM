package com.techcrm.crm.analytics;

import java.math.BigDecimal;

/**
 * The home dashboard's headline figures. pipelineValue is the value of every
 * open deal visible to the caller; openCases and the RPA counts are
 * organization-wide, matching the Cases and RPA screens.
 */
public record DashboardMetrics(
        long totalLeads,
        BigDecimal pipelineValue,
        long openCases,
        long rpaBotsActive,
        long rpaBotsTotal
) {
}
