package com.techcrm.crm.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * One pipeline stage on the home dashboard. The nested _sum/_count shape is
 * the contract the Dashboard screen already renders (DashboardSummary in
 * frontend/src/app/lib/types.ts), kept as-is rather than reshaping the client.
 */
public record PipelineStageSummary(
        String stage,
        @JsonProperty("_sum") Sum sum,
        @JsonProperty("_count") Count count
) {
    public record Sum(BigDecimal value) {
    }

    public record Count(@JsonProperty("_all") long all) {
    }
}
