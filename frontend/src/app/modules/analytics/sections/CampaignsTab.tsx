import { colors } from "../../../tokens";
import { useCampaignAnalytics } from "../api/hooks";
import type { AnalyticsFilters } from "../api/types";
import { BarList, DataTable, Grid, Panel, QueryBody, Stat, StatRow } from "../components/ui";
import { TrendChart } from "../components/charts";
import { count, humanize, pct, series } from "../format";

export function CampaignTrendPanel({ filters, span }: { filters: AnalyticsFilters; span?: boolean }) {
  const q = useCampaignAnalytics(filters);
  return (
    <Panel title="Campaign engagement over time" subtitle="Each event counted on the day it happened" span={span} dimmed={q.isFetching && !!q.data}>
      <QueryBody query={q} isEmpty={(d) => d.trend.every((p) => p.sent + p.opened + p.clicked + p.converted === 0)} empty="No campaign messages were sent in this period">
        {(d) => (
          <TrendChart
            data={d.trend}
            granularity={d.window.granularity}
            format={count}
            series={[
              { key: "sent", label: "Sent", color: series[0] },
              { key: "opened", label: "Opened", color: series[1] },
              { key: "clicked", label: "Clicked", color: series[2] },
              { key: "converted", label: "Converted", color: series[3] },
            ]}
          />
        )}
      </QueryBody>
    </Panel>
  );
}

export function CampaignsTab({ filters }: { filters: AnalyticsFilters }) {
  const q = useCampaignAnalytics(filters);
  const dimmed = q.isFetching && !!q.data;

  return (
    <Grid min={380}>
      <Panel title="Message funnel" subtitle="Messages sent in the period and what recipients did" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.funnel.sent === 0} empty="No campaign messages were sent in this period" emptyHint="Figures appear once an approved campaign is launched.">
          {(d) => {
            const f = d.funnel;
            return (
              <>
                <StatRow>
                  <Stat label="Open rate" value={pct(f.openRate)} />
                  <Stat label="Click rate" value={pct(f.clickRate)} />
                  <Stat label="Conversion" value={pct(f.conversionRate)} />
                  <Stat label="Failed" value={count(f.failed)} tone={f.failed ? colors.danger : undefined} />
                </StatRow>
                <BarList
                  items={[
                    ["Sent", f.sent],
                    ["Delivered", f.delivered],
                    ["Opened", f.opened],
                    ["Clicked", f.clicked],
                    ["Responded", f.responded],
                    ["Converted", f.converted],
                  ].map(([label, value]) => ({
                    label: label as string,
                    value: value as number,
                    display: count(value as number),
                    sub: f.sent ? `${Math.round((100 * (value as number)) / f.sent)}%` : undefined,
                  }))}
                />
                <div style={{ fontSize: 11, color: colors.textTertiary, marginTop: 10 }}>Rates use delivered messages as the base once delivery is reported, otherwise sent.</div>
              </>
            );
          }}
        </QueryBody>
      </Panel>

      <Panel title="Campaigns by status" subtitle="All campaigns in the organization, as they stand today" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.totalCampaigns === 0} empty="No campaigns have been created yet">
          {(d) => <BarList items={d.byStatus.map((r) => ({ label: humanize(r.key), value: r.count, display: count(r.count) }))} />}
        </QueryBody>
      </Panel>

      <CampaignTrendPanel filters={filters} span />

      <Panel title="Campaign performance" subtitle="Campaigns that sent messages in the period" span dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.campaigns.length === 0} empty="No campaign sent messages in this period" height={120}>
          {(d) => (
            <DataTable
              rows={d.campaigns}
              rowKey={(r) => r.id}
              columns={[
                { header: "Campaign", width: "1.8fr", render: (r) => <b style={{ fontWeight: 600 }}>{r.name}</b> },
                { header: "Type", width: "1fr", render: (r) => humanize(r.type) },
                { header: "Status", width: "0.9fr", render: (r) => humanize(r.status) },
                { header: "Sent", align: "right", width: "0.7fr", render: (r) => count(r.sent) },
                { header: "Opened", align: "right", width: "0.7fr", render: (r) => count(r.opened) },
                { header: "Clicked", align: "right", width: "0.7fr", render: (r) => count(r.clicked) },
                { header: "Converted", align: "right", width: "0.8fr", render: (r) => count(r.converted) },
                { header: "Open rate", align: "right", width: "0.8fr", render: (r) => pct(r.openRate) },
                { header: "Click rate", align: "right", width: "0.8fr", render: (r) => pct(r.clickRate) },
                { header: "Conv. rate", align: "right", width: "0.8fr", render: (r) => pct(r.conversionRate) },
              ]}
            />
          )}
        </QueryBody>
      </Panel>
    </Grid>
  );
}
