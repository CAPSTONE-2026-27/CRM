import { colors } from "../../../tokens";
import { useLeadsAnalytics } from "../api/hooks";
import type { AnalyticsFilters } from "../api/types";
import { BarList, Grid, Panel, QueryBody, SegmentBar, Stat, StatRow } from "../components/ui";
import { ColumnChart, TrendChart } from "../components/charts";
import { count, ordinalRamp, pct, series, status } from "../format";

const TIER_COLORS = [ordinalRamp[4], ordinalRamp[2], ordinalRamp[0], status.neutral];

export function LeadTrendPanel({ filters, span }: { filters: AnalyticsFilters; span?: boolean }) {
  const q = useLeadsAnalytics(filters);
  return (
    <Panel title="Leads created over time" subtitle="Of the leads created in each period, how many are now qualified or converted" span={span} dimmed={q.isFetching && !!q.data}>
      <QueryBody query={q} isEmpty={(d) => d.total === 0} empty="No leads were created in this period" emptyHint="Leads appear here as they are captured, imported or created.">
        {(d) => (
          <TrendChart
            data={d.trend}
            granularity={d.window.granularity}
            format={count}
            series={[
              { key: "created", label: "Created", color: series[0] },
              { key: "qualified", label: "Qualified", color: series[1] },
              { key: "converted", label: "Converted", color: series[2] },
            ]}
          />
        )}
      </QueryBody>
    </Panel>
  );
}

export function LeadsTab({ filters }: { filters: AnalyticsFilters }) {
  const q = useLeadsAnalytics(filters);
  const dimmed = q.isFetching && !!q.data;
  const noLeads = (d: { total: number }) => d.total === 0;

  return (
    <Grid min={380}>
      <Panel title="Lead progression" subtitle="Leads created in the period and how far each has got" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={noLeads} empty="No leads were created in this period">
          {(d) => {
            const created = d.funnel[0]?.count ?? 0;
            return (
              <>
                <StatRow>
                  <Stat label="Leads created" value={count(d.total)} />
                  <Stat label="Avg AI score" value={d.averageScore == null ? "—" : d.averageScore.toFixed(1)} sub="Scored leads only" />
                  <Stat label="Lead → deal" value={pct(created ? (100 * (d.funnel[5]?.count ?? 0)) / created : null)} sub="Conversion rate" />
                </StatRow>
                <BarList
                  items={d.funnel.map((s) => ({
                    label: s.label,
                    value: s.count,
                    display: count(s.count),
                    sub: created ? `${Math.round((100 * s.count) / created)}%` : undefined,
                  }))}
                />
              </>
            );
          }}
        </QueryBody>
      </Panel>

      <Panel title="AI lead score distribution" subtitle="Current AI score of leads created in the period" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={noLeads} empty="No leads were created in this period">
          {(d) => (
            <>
              <ColumnChart data={d.scoreTiers.map((t, i) => ({ label: t.key, value: t.count, color: TIER_COLORS[i] ?? status.neutral }))} valueLabel="Leads" />
              <div style={{ fontSize: 11, color: colors.textTertiary, marginTop: 8 }}>
                Tiers match the lead-meeting labels: Hot 75+, Warm 45–74, Cold below 45.
              </div>
            </>
          )}
        </QueryBody>
      </Panel>

      <Panel title="Leads by source" subtitle="Volume, with the share that converted to a deal" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.bySource.length === 0} empty="No leads were created in this period">
          {(d) => (
            <BarList
              items={d.bySource.map((s) => ({
                label: s.source,
                value: s.leads,
                display: count(s.leads),
                sub: s.conversionRate == null ? undefined : `${pct(s.conversionRate)} converted`,
                title: `${s.source}: ${s.leads} leads, ${s.qualified} qualified, ${s.converted} converted`,
              }))}
            />
          )}
        </QueryBody>
      </Panel>

      <Panel title="Qualification status" subtitle="The verdict a person recorded on each lead (AI only advises)" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={noLeads} empty="No leads were created in this period" height={140}>
          {(d) => {
            const get = (k: string) => d.byQualification.find((r) => r.key === k)?.count ?? 0;
            const temp = (k: string) => d.byStatus.find((r) => r.key === k)?.count ?? 0;
            return (
              <>
                <SegmentBar
                  parts={[
                    { label: "Qualified", value: get("QUALIFIED"), color: status.good },
                    { label: "Pending review", value: get("PENDING"), color: status.neutral },
                    { label: "Unqualified", value: get("UNQUALIFIED"), color: status.critical },
                  ]}
                />
                <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, margin: "18px 0 8px" }}>Lead temperature</div>
                <SegmentBar
                  parts={[
                    { label: "Hot", value: temp("HOT"), color: ordinalRamp[4] },
                    { label: "Warm", value: temp("WARM"), color: ordinalRamp[2] },
                    { label: "Cold", value: temp("COLD"), color: ordinalRamp[0] },
                    { label: "New", value: temp("NEW"), color: status.neutral },
                  ]}
                />
              </>
            );
          }}
        </QueryBody>
      </Panel>

      <LeadTrendPanel filters={filters} span />
    </Grid>
  );
}
