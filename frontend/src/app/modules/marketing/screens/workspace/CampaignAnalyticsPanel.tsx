import { useState } from "react";
import { BarChart3 } from "lucide-react";
import type { CampaignDetail, Range } from "../../api/types";
import { useAnalytics } from "../../api/hooks";
import { EmptyState, ErrorState, Panel, Skeleton } from "../../components/ui";
import { FunnelChart, PerformanceChart, RangeFilter, RateTiles } from "../../components/charts";
import { fmtDate, fmtNum, fmtPct } from "../../theme";
import { FunnelNotes, MetricDefinitions } from "../HomeTabs";

export function CampaignAnalyticsPanel({ c }: { c: CampaignDetail }) {
  const [range, setRange] = useState<Range>({ key: "30d" });
  const launched = !!c.launchedAt;
  const { data, error, isFetching, refetch } = useAnalytics(c.id, range, launched);

  if (!launched) {
    return (
      <Panel>
        <EmptyState icon={BarChart3} title="No results yet">
          Performance appears here once the campaign launches: emails sent, delivered, bounced, opened, clicked, converted and unsubscribed, counted per recipient.
        </EmptyState>
      </Panel>
    );
  }
  if (error) return <ErrorState error={error} onRetry={() => refetch()} />;
  if (!data) return <Skeleton rows={8} height={20} />;

  return (
    <div className="mk-stack">
      <div className="mk-filters" style={{ marginBottom: 0 }}>
        <RangeFilter value={range} onChange={setRange} />
        {(data.queued > 0 || data.held > 0) && (
          <span className="mk-sub">
            {fmtNum(data.queued)} queued{data.sending > 0 ? ` · ${fmtNum(data.sending)} sending` : ""}{data.held > 0 ? ` · ${fmtNum(data.held)} held for the A/B decision` : ""}
          </span>
        )}
      </div>
      <RateTiles funnel={data.funnel} />
      <div className="mk-grid-main">
        <Panel title="Performance over time" subtitle={`${fmtDate(data.from)} – ${fmtDate(data.to)}`}>
          <PerformanceChart series={data.series} loading={isFetching} height={280} />
        </Panel>
        <Panel title="Funnel" subtitle="All time for this campaign">
          <FunnelChart funnel={data.funnel} />
        </Panel>
      </div>
      {data.variants.length > 1 && (
        <Panel title="By variant" subtitle="Measured counts; see the A/B testing tab to decide" flush>
          <div className="mk-table-wrap">
            <table className="mk-table">
              <thead>
                <tr>
                  <th>Variant</th>
                  <th className="mk-num">Emails sent</th>
                  <th className="mk-num">Delivery rate</th>
                  <th className="mk-num">Open rate (est.)</th>
                  <th className="mk-num">Click-through rate</th>
                  <th className="mk-num">Conversion rate</th>
                </tr>
              </thead>
              <tbody>
                {data.variants.map((v) => (
                  <tr key={v.variant}>
                    <td style={{ fontWeight: 600 }}>Variant {v.variant}</td>
                    <td className="mk-num">{fmtNum(v.funnel.sent)}</td>
                    <td className="mk-num">{v.funnel.deliveryRate == null ? "Not reported" : fmtPct(v.funnel.deliveryRate)}</td>
                    <td className="mk-num">{fmtPct(v.funnel.openRate)}</td>
                    <td className="mk-num">{fmtPct(v.funnel.clickRate)}</td>
                    <td className="mk-num">{fmtPct(v.funnel.conversionRate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}
      <FunnelNotes funnel={data.funnel} />
      <MetricDefinitions />
    </div>
  );
}
