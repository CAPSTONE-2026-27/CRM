package com.techcrm.crm.analytics;

import com.techcrm.crm.lead.LeadResponse;

import java.util.List;

public record DashboardSummaryResponse(
        DashboardMetrics metrics,
        List<LeadResponse> recentLeads,
        // Pipeline and RPA modules don't exist yet — always empty for now,
        // matching the frontend's tolerant "—" rendering for absent data.
        List<Object> pipelineByStage,
        List<Object> recentBotRuns
) {
}
