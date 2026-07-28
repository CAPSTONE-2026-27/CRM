package com.techcrm.crm.analytics;

public record DashboardMetrics(
        long totalLeads,
        int pipelineValue,
        int openCases,
        int rpaBotsActive,
        int rpaBotsTotal
) {
}
