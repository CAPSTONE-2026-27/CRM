import { useOverview } from "../api/hooks";
import type { AnalyticsFilters, Section } from "../api/types";
import { EmptyState, ErrorState, Grid, KpiCard, KpiSkeleton } from "../components/ui";
import { shortDate } from "../format";
import { LeadTrendPanel } from "./LeadsTab";
import { PipelinePanel } from "./DealsTab";
import { CampaignTrendPanel } from "./CampaignsTab";

// Cases & tickets are no longer part of the frontend; the server still sends
// their headline figures, so they are dropped here.
const HIDDEN_KPIS = new Set(["openTickets", "ticketsCreated", "ticketsResolved", "avgResolution"]);

export function OverviewTab({ filters, sections }: { filters: AnalyticsFilters; sections: Section[] }) {
  const q = useOverview(filters);
  const kpis = q.data?.kpis.filter((k) => !HIDDEN_KPIS.has(k.key)) ?? [];
  const has = (s: Section) => sections.includes(s);
  const w = q.data?.window;
  const compareLabel = w ? `${shortDate(w.previousFrom)} – ${shortDate(w.previousTo)}` : "previous period";

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <div style={{ opacity: q.isFetching && q.data ? 0.55 : 1, transition: "opacity .15s" }}>
        {q.error && !q.data ? (
          <ErrorState error={q.error} onRetry={() => q.refetch()} height={120} />
        ) : (
          <Grid min={190} gap={12}>
            {q.data
              ? kpis.map((k) => <KpiCard key={k.key} kpi={k} compareLabel={compareLabel} />)
              : Array.from({ length: 8 }, (_, i) => <KpiSkeleton key={i} />)}
          </Grid>
        )}
        {q.data && kpis.length === 0 && <EmptyState message="No headline figures are available for your role" height={100} />}
      </div>

      <Grid min={380}>
        {has("LEADS") && <LeadTrendPanel filters={filters} />}
        {has("DEALS") && <PipelinePanel filters={filters} />}
        {has("CAMPAIGNS") && !has("DEALS") && <CampaignTrendPanel filters={filters} />}
      </Grid>
    </div>
  );
}
