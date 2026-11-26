import { colors } from "../../../tokens";
import { useAiAnalytics } from "../api/hooks";
import type { AnalyticsFilters } from "../api/types";
import { DataTable, Grid, Panel, QueryBody, SegmentBar, Stat, StatRow } from "../components/ui";
import { TrendChart } from "../components/charts";
import { count, humanize, pct, series, status } from "../format";

export function AiTab({ filters }: { filters: AnalyticsFilters }) {
  const q = useAiAnalytics(filters);
  const dimmed = q.isFetching && !!q.data;
  const get = (rows: { key: string; count: number }[], k: string) => rows.find((r) => r.key === k)?.count ?? 0;

  return (
    <Grid min={380}>
      <Panel title="Meetings held" subtitle="Lead qualification meetings and deal meetings, by meeting date" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.leadMeetings + d.dealMeetings === 0} empty="No meetings were held in this period">
          {(d) => (
            <>
              <StatRow>
                <Stat label="Total meetings" value={count(d.leadMeetings + d.dealMeetings)} />
                <Stat label="Lead meetings" value={count(d.leadMeetings)} />
                <Stat label="Deal meetings" value={count(d.dealMeetings)} />
              </StatRow>
              <TrendChart
                kind="bar"
                height={200}
                data={d.meetingsTrend}
                granularity={d.window.granularity}
                format={count}
                series={[
                  { key: "leadMeetings", label: "Lead meetings", color: series[0] },
                  { key: "dealMeetings", label: "Deal meetings", color: series[1] },
                ]}
              />
            </>
          )}
        </QueryBody>
      </Panel>

      <Panel title="AI scoring usage" subtitle="Every AI reading the CRM recorded in the period" dimmed={dimmed}>
        <QueryBody
          query={q}
          isEmpty={(d) => d.aiUsageTrend.every((p) => p.leadAnalyses + p.dealAnalyses + p.dealPredictions === 0) && d.leadsAiScored === 0}
          empty="No AI scoring ran in this period"
        >
          {(d) => (
            <>
              <StatRow>
                <Stat label="Leads AI-scored" value={count(d.leadsAiScored)} sub={d.leadsCreated ? `${pct((100 * d.leadsAiScored) / d.leadsCreated, 0)} of new leads` : undefined} />
                <Stat label="Deal predictions" value={count(d.dealPredictions)} />
                <Stat label="Avg analysis time" value={d.avgDealAnalysisLatencyMs == null ? "—" : `${(d.avgDealAnalysisLatencyMs / 1000).toFixed(1)} s`} sub="Deal meeting analysis" />
              </StatRow>
              <TrendChart
                height={200}
                data={d.aiUsageTrend}
                granularity={d.window.granularity}
                format={count}
                series={[
                  { key: "leadAnalyses", label: "Lead meeting analyses", color: series[0] },
                  { key: "dealAnalyses", label: "Deal meeting analyses", color: series[1] },
                  { key: "dealPredictions", label: "Deal predictions", color: series[2] },
                ]}
              />
            </>
          )}
        </QueryBody>
      </Panel>

      <Panel title="AI reliability" subtitle="How often the model answered versus a fallback being used" dimmed={dimmed}>
        <QueryBody
          query={q}
          isEmpty={(d) => d.leadAnalysesBySource.every((r) => r.count === 0) && d.dealAnalysesByStatus.every((r) => r.count === 0)}
          empty="No AI analyses ran in this period"
          height={160}
        >
          {(d) => (
            <>
              <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, marginBottom: 8 }}>Lead meeting analyses</div>
              <SegmentBar
                parts={[
                  { label: "Model", value: get(d.leadAnalysesBySource, "MODEL"), color: status.good },
                  { label: "Model, repaired", value: get(d.leadAnalysesBySource, "REPAIRED"), color: status.warning },
                  { label: "Heuristic fallback", value: get(d.leadAnalysesBySource, "HEURISTIC"), color: status.serious },
                ]}
              />
              <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, margin: "18px 0 8px" }}>Deal meeting analyses</div>
              <SegmentBar
                parts={[
                  { label: "Succeeded", value: get(d.dealAnalysesByStatus, "SUCCEEDED"), color: status.good },
                  { label: "Degraded (fallback)", value: get(d.dealAnalysesByStatus, "DEGRADED"), color: status.warning },
                  { label: "Failed", value: get(d.dealAnalysesByStatus, "FAILED"), color: status.critical },
                ]}
              />
            </>
          )}
        </QueryBody>
      </Panel>

      <Panel title="Manager reviews vs AI predictions" subtitle="What managers decided about the AI's recommendation, by the risk the AI predicted" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => d.reviewDecisions.every((r) => r.count === 0)} empty="No manager reviews in this period" emptyHint="Reviews are recorded when a manager approves, rejects or overrides a deal prediction.">
          {(d) => (
            <>
              <StatRow>
                <Stat label="Reviews" value={count(d.reviewDecisions.reduce((s, r) => s + r.count, 0))} />
                <Stat label="Agreed with AI" value={count(get(d.reviewDecisions, "APPROVED"))} />
                <Stat label="Overruled AI" value={pct(d.overrideRate)} sub="Rejected or overridden" />
              </StatRow>
              <SegmentBar
                parts={[
                  { label: "Approved", value: get(d.reviewDecisions, "APPROVED"), color: series[0] },
                  { label: "Rejected", value: get(d.reviewDecisions, "REJECTED"), color: series[1] },
                  { label: "Overridden", value: get(d.reviewDecisions, "OVERRIDDEN"), color: series[6] },
                ]}
              />
              <div style={{ marginTop: 14 }}>
                <DataTable
                  rows={d.reviewsByRisk}
                  rowKey={(r) => r.riskLevel}
                  columns={[
                    { header: "AI risk level", width: "1.2fr", render: (r) => (r.riskLevel === "UNKNOWN" ? "Not recorded" : humanize(r.riskLevel)) },
                    { header: "Approved", align: "right", render: (r) => count(r.approved) },
                    { header: "Rejected", align: "right", render: (r) => count(r.rejected) },
                    { header: "Overridden", align: "right", render: (r) => count(r.overridden) },
                  ]}
                />
              </div>
            </>
          )}
        </QueryBody>
      </Panel>
    </Grid>
  );
}
