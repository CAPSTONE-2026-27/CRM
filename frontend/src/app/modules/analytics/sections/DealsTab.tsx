import { colors } from "../../../tokens";
import { useDealsAnalytics } from "../api/hooks";
import type { AnalyticsFilters, AtRiskDeal } from "../api/types";
import { BarList, DataTable, Grid, Panel, Pill, QueryBody, SegmentBar, Stat, StatRow } from "../components/ui";
import { ColumnChart, TrendChart } from "../components/charts";
import { count, humanize, inr, inrCompact, longDate, ordinalRamp, pct, series, status } from "../format";

const CLOSED = new Set(["CLOSED_WON", "CLOSED_LOST"]);

export function PipelinePanel({ filters, span }: { filters: AnalyticsFilters; span?: boolean }) {
  const q = useDealsAnalytics(filters);
  return (
    <Panel title="Open pipeline by stage" subtitle="Value of open deals created in the period" span={span} dimmed={q.isFetching && !!q.data}>
      <QueryBody
        query={q}
        isEmpty={(d) => d.byStage.every((s) => CLOSED.has(s.key) || s.count === 0)}
        empty="No open deals were created in this period"
        emptyHint="Deals appear here once a lead is converted or an opportunity is created."
      >
        {(d) => (
          <BarList
            items={d.byStage
              .filter((s) => !CLOSED.has(s.key))
              .map((s) => ({
                label: humanize(s.key),
                value: s.value,
                display: inrCompact(s.value),
                sub: `${s.count} deal${s.count === 1 ? "" : "s"}`,
                title: `${humanize(s.key)}: ${inr(s.value)} across ${s.count} deal(s)`,
              }))}
          />
        )}
      </QueryBody>
    </Panel>
  );
}

const riskPill = (d: AtRiskDeal) =>
  d.riskLevel === "HIGH" ? (
    <Pill label="High risk" color={colors.danger} bg={colors.dangerLight} />
  ) : (
    <Pill label="Low score" color={colors.warning} bg={colors.warningLight} />
  );

export function DealsTab({ filters }: { filters: AnalyticsFilters }) {
  const q = useDealsAnalytics(filters);
  const dimmed = q.isFetching && !!q.data;

  return (
    <Grid min={380}>
      <PipelinePanel filters={filters} />

      <Panel title="Won vs lost" subtitle="Deals closed in the period" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.wonCount + d.lostCount === 0} empty="No deals closed in this period">
          {(d) => (
            <>
              <StatRow>
                <Stat label="Won" value={inrCompact(d.wonValue)} sub={`${d.wonCount} deal${d.wonCount === 1 ? "" : "s"}`} />
                <Stat label="Lost" value={inrCompact(d.lostValue)} sub={`${d.lostCount} deal${d.lostCount === 1 ? "" : "s"}`} />
                <Stat label="Win rate" value={pct(d.winRate)} sub="By deal count" />
                <Stat label="Avg won deal" value={inrCompact(d.avgWonDealSize)} />
              </StatRow>
              <TrendChart
                kind="bar"
                height={180}
                data={d.wonLostTrend}
                granularity={d.window.granularity}
                format={count}
                series={[
                  { key: "won", label: "Won", color: status.good },
                  { key: "lost", label: "Lost", color: status.critical },
                ]}
              />
            </>
          )}
        </QueryBody>
      </Panel>

      <Panel title="Deal score distribution" subtitle="XGBoost deal score (0–100) of deals scored in the period" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.scoreDistribution.every((b) => b.count === 0)} empty="No deals were scored in this period" emptyHint="Scores come from the deal-scoring form or a submitted meeting output.">
          {(d) => <ColumnChart valueLabel="Deals" data={d.scoreDistribution.map((b, i) => ({ label: b.key, value: b.count, color: ordinalRamp[i] }))} />}
        </QueryBody>
      </Panel>

      <Panel title="Win probability distribution" subtitle="Model win probability (%) of deals scored in the period" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.winProbabilityDistribution.every((b) => b.count === 0)} empty="No deals were scored in this period">
          {(d) => <ColumnChart valueLabel="Deals" data={d.winProbabilityDistribution.map((b, i) => ({ label: `${b.key}%`, value: b.count, color: ordinalRamp[i] }))} />}
        </QueryBody>
      </Panel>

      <Panel title="Deal score trend" subtitle="Average of every deal-flow prediction made in each period" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.scoreTrend.every((p) => p.predictions === 0)} empty="No deal-flow predictions in this period" emptyHint="A prediction is recorded each time a meeting output is analysed and scored.">
          {(d) => (
            <TrendChart
              data={d.scoreTrend}
              granularity={d.window.granularity}
              domain={[0, 100]}
              format={(n) => n.toFixed(1)}
              series={[
                { key: "avgDealScore", label: "Avg deal score", color: series[0] },
                { key: "avgWinProbability", label: "Avg win probability (%)", color: series[1] },
              ]}
            />
          )}
        </QueryBody>
      </Panel>

      <Panel title="Open deals by AI risk" subtitle="All open deals as they stand today" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.openByRisk.every((r) => r.count === 0)} empty="There are no open deals" height={120}>
          {(d) => {
            const get = (k: string) => d.openByRisk.find((r) => r.key === k)?.count ?? 0;
            return (
              <SegmentBar
                parts={[
                  { label: "High risk", value: get("HIGH"), color: status.critical },
                  { label: "Medium risk", value: get("MEDIUM"), color: status.warning },
                  { label: "Low risk", value: get("LOW"), color: status.good },
                  { label: "Not assessed", value: get("UNASSESSED"), color: status.neutral },
                ]}
              />
            );
          }}
        </QueryBody>
      </Panel>

      <Panel title="Top at-risk deals" subtitle="Open deals the AI rates high-risk, or scored below 45 — largest first. Current state, not limited to the period." span dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.atRisk.length === 0} empty="No open deals are flagged as at risk" height={120}>
          {(d) => (
            <DataTable
              rows={d.atRisk}
              rowKey={(r) => r.id}
              columns={[
                { header: "Deal", width: "1.6fr", render: (r) => (<span><b style={{ fontWeight: 600 }}>{r.name}</b>{r.opportunityId && <span style={{ color: colors.textTertiary }}> · {r.opportunityId}</span>}</span>) },
                { header: "Account", width: "1.1fr", render: (r) => r.accountName ?? "—" },
                { header: "Owner", width: "1fr", render: (r) => r.ownerName ?? "Unassigned" },
                { header: "Stage", width: "1fr", render: (r) => humanize(r.stage) },
                { header: "Value", align: "right", width: "0.9fr", render: (r) => inrCompact(r.value) },
                { header: "Score", align: "right", width: "0.6fr", render: (r) => (r.dealScore == null ? "—" : r.dealScore.toFixed(0)) },
                { header: "Win prob.", align: "right", width: "0.7fr", render: (r) => pct(r.winProbability, 0) },
                { header: "Close date", width: "0.9fr", render: (r) => (r.expectedCloseDate ? longDate(r.expectedCloseDate) : "—") },
                { header: "Flag", width: "0.8fr", render: riskPill },
              ]}
            />
          )}
        </QueryBody>
      </Panel>
    </Grid>
  );
}
