package com.techcrm.crm.lead;

/** Org+role scoped identically to the list, but ignoring current filters —
 *  MetricGrid needs true totals, not counts of whatever page is loaded. */
public record LeadStatsResponse(
        long totalLeads,
        long aiScored,
        long csvImported,
        long botImported
) {
}
