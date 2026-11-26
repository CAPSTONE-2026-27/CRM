package com.techcrm.crm.analytics;

import com.techcrm.crm.lead.LeadResponse;
import com.techcrm.crm.rpa.RpaDtos.RpaBotRunResponse;

import java.util.List;

public record DashboardSummaryResponse(
        DashboardMetrics metrics,
        List<LeadResponse> recentLeads,
        List<PipelineStageSummary> pipelineByStage,
        List<RpaBotRunResponse> recentBotRuns
) {
}
