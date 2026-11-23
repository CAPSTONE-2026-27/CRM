import { colors } from "../../../tokens";
import { useTeamAnalytics } from "../api/hooks";
import type { AnalyticsFilters, RepRow } from "../api/types";
import { BarList, DataTable, Grid, Panel, QueryBody } from "../components/ui";
import { count, humanize, inr, inrCompact, pct, series } from "../format";

const hasActivity = (r: RepRow) => r.leadsAssigned + r.dealsWon + r.dealsLost + r.meetings > 0 || r.openPipeline > 0;

export function TeamTab({ filters }: { filters: AnalyticsFilters }) {
  const q = useTeamAnalytics(filters);
  const dimmed = q.isFetching && !!q.data;

  return (
    <Grid min={380}>
      <Panel title="Rep leaderboard" subtitle="Ranked by revenue won in the period. Open pipeline is as of today." span dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => !d.reps.some(hasActivity)} empty="No sales activity in this period" emptyHint="Reps appear once they own leads or deals, or record meetings.">
          {(d) => (
            <DataTable
              rows={d.reps}
              rowKey={(r) => r.userId}
              columns={[
                { header: "#", width: "0.3fr", render: (r) => <span style={{ color: colors.textTertiary }}>{d.reps.indexOf(r) + 1}</span> },
                { header: "Rep", width: "1.5fr", render: (r) => (<span><b style={{ fontWeight: 600 }}>{r.name}</b><span style={{ color: colors.textTertiary }}> · {humanize(r.role)}</span></span>) },
                { header: "Revenue won", align: "right", width: "1fr", render: (r) => <span title={inr(r.revenueWon)}>{inrCompact(r.revenueWon)}</span> },
                { header: "Won", align: "right", width: "0.5fr", render: (r) => count(r.dealsWon) },
                { header: "Lost", align: "right", width: "0.5fr", render: (r) => count(r.dealsLost) },
                { header: "Win rate", align: "right", width: "0.7fr", render: (r) => pct(r.winRate) },
                { header: "Leads", align: "right", width: "0.6fr", render: (r) => count(r.leadsAssigned) },
                { header: "Converted", align: "right", width: "0.7fr", render: (r) => count(r.leadsConverted) },
                { header: "Conversion", align: "right", width: "0.8fr", render: (r) => pct(r.conversionRate) },
                { header: "Meetings", align: "right", width: "0.7fr", render: (r) => count(r.meetings) },
                { header: "Open pipeline", align: "right", width: "1fr", render: (r) => inrCompact(r.openPipeline) },
              ]}
            />
          )}
        </QueryBody>
      </Panel>

      <Panel title="Revenue won by rep" subtitle="Closed-won value in the period" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => !d.reps.some((r) => r.revenueWon > 0)} empty="No deals were won in this period">
          {(d) => (
            <BarList
              color={series[0]}
              items={d.reps.filter((r) => r.revenueWon > 0).map((r) => ({ label: r.name, value: r.revenueWon, display: inrCompact(r.revenueWon), sub: `${r.dealsWon} won` }))}
            />
          )}
        </QueryBody>
      </Panel>

      <Panel title="Meetings by rep" subtitle="Lead and deal meetings each person recorded" dimmed={dimmed}>
        <QueryBody query={q} isEmpty={(d) => !d.reps.some((r) => r.meetings > 0)} empty="No meetings were recorded in this period">
          {(d) => (
            <BarList
              color={series[1]}
              items={[...d.reps].filter((r) => r.meetings > 0).sort((a, b) => b.meetings - a.meetings).map((r) => ({ label: r.name, value: r.meetings, display: count(r.meetings) }))}
            />
          )}
        </QueryBody>
      </Panel>
    </Grid>
  );
}
