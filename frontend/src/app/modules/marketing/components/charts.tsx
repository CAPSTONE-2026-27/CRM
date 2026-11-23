import { useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { BarChart3 } from "lucide-react";
import type { Funnel, Range, RangeKey, SeriesPoint } from "../api/types";
import { fmtNum, fmtPct, funnelRamp, seriesColors } from "../theme";
import { Segmented } from "./ui";

const SERIES: { key: keyof typeof seriesColors; label: string }[] = [
  { key: "sent", label: "Sent" },
  { key: "delivered", label: "Delivered" },
  { key: "opened", label: "Opened" },
  { key: "clicked", label: "Clicked" },
  { key: "converted", label: "Converted" },
];

const shortDate = (d: string) => new Date(`${d}T00:00:00`).toLocaleDateString("en-IN", { day: "numeric", month: "short" });

/* ---------------- Date range filter ---------------- */
export function RangeFilter({ value, onChange }: { value: Range; onChange: (r: Range) => void }) {
  const today = new Date().toISOString().slice(0, 10);
  return (
    <div className="mk-row">
      <Segmented<RangeKey>
        label="Date range"
        value={value.key}
        onChange={(key) => onChange(key === "custom" ? { key, from: value.from ?? today, to: value.to ?? today } : { key })}
        options={[
          { value: "7d", label: "Last 7 days" },
          { value: "30d", label: "Last 30 days" },
          { value: "90d", label: "Last 90 days" },
          { value: "custom", label: "Custom" },
        ]}
      />
      {value.key === "custom" && (
        <>
          <input type="date" className="mk-input" aria-label="From" value={value.from ?? ""} max={value.to} onChange={(e) => onChange({ ...value, from: e.target.value })} />
          <span className="mk-muted">to</span>
          <input type="date" className="mk-input" aria-label="To" value={value.to ?? ""} min={value.from} max={today} onChange={(e) => onChange({ ...value, to: e.target.value })} />
        </>
      )}
    </div>
  );
}

/* ---------------- Performance over time ---------------- */
function ChartTooltip({ active, payload, label }: { active?: boolean; payload?: { dataKey: string; value: number }[]; label?: string }) {
  if (!active || !payload?.length || !label) return null;
  return (
    <div className="mk-tooltip">
      <div style={{ fontWeight: 650, marginBottom: 4 }}>{shortDate(label)}</div>
      {SERIES.map((s) => {
        const p = payload.find((x) => x.dataKey === s.key);
        return (
          <div className="mk-tooltip__row" key={s.key}>
            <i style={{ background: seriesColors[s.key] }} />
            <b>{fmtNum(p?.value ?? 0)}</b>
            <span>{s.label}</span>
          </div>
        );
      })}
    </div>
  );
}

export function PerformanceChart({ series, loading, height = 260 }: { series: SeriesPoint[]; loading?: boolean; height?: number }) {
  const [view, setView] = useState<"chart" | "table">("chart");
  const empty = series.every((p) => p.sent + p.delivered + p.opened + p.clicked + p.converted === 0);
  return (
    <div style={{ opacity: loading ? 0.55 : 1, transition: "opacity .15s" }}>
      <div className="mk-row" style={{ justifyContent: "space-between", marginBottom: 10 }}>
        <div className="mk-legend" aria-label="Legend">
          {SERIES.map((s) => (
            <span key={s.key}>
              <i style={{ background: seriesColors[s.key] }} />
              {s.label}
            </span>
          ))}
        </div>
        <Segmented
          label="Chart or table"
          value={view}
          onChange={setView}
          options={[
            { value: "chart", label: "Chart" },
            { value: "table", label: "Table" },
          ]}
        />
      </div>
      {view === "chart" ? (
        <div style={{ height, position: "relative" }}>
          {empty && (
            <div className="mk-muted" style={{ position: "absolute", inset: 0, display: "grid", placeItems: "center", zIndex: 1, fontSize: 12.5, pointerEvents: "none" }}>
              <span style={{ background: "rgba(255,255,255,.9)", padding: "4px 10px", borderRadius: 6 }}>
                <BarChart3 size={14} style={{ verticalAlign: -2, marginRight: 6 }} aria-hidden />
                No delivery activity in this period
              </span>
            </div>
          )}
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={series} margin={{ top: 6, right: 12, bottom: 0, left: -8 }}>
              <CartesianGrid stroke="#EEEFF1" vertical={false} />
              <XAxis dataKey="date" tickFormatter={shortDate} tick={{ fontSize: 11, fill: "#85878C" }} axisLine={{ stroke: "#E2E3E5" }} tickLine={false} minTickGap={24} />
              <YAxis tick={{ fontSize: 11, fill: "#85878C" }} axisLine={false} tickLine={false} allowDecimals={false} width={44} tickFormatter={(v: number) => fmtNum(v)} />
              <Tooltip content={<ChartTooltip />} cursor={{ stroke: "#9EA1A6", strokeWidth: 1 }} />
              {SERIES.map((s) => (
                <Line
                  key={s.key}
                  type="monotone"
                  dataKey={s.key}
                  stroke={seriesColors[s.key]}
                  strokeWidth={2}
                  dot={false}
                  activeDot={{ r: 4, strokeWidth: 2, stroke: "#FFFFFF" }}
                  isAnimationActive={false}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </div>
      ) : (
        <div className="mk-table-wrap" style={{ maxHeight: height, overflowY: "auto" }}>
          <table className="mk-table">
            <caption className="mk-sr">Daily campaign delivery activity</caption>
            <thead>
              <tr>
                <th>Date</th>
                {SERIES.map((s) => (
                  <th key={s.key} className="mk-num">
                    {s.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {series.map((p) => (
                <tr key={p.date}>
                  <td>{shortDate(p.date)}</td>
                  {SERIES.map((s) => (
                    <td key={s.key} className="mk-num">
                      {fmtNum(p[s.key])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/* ---------------- Funnel ---------------- */
export function FunnelChart({ funnel }: { funnel: Funnel }) {
  const stages = [
    { label: "Sent", value: funnel.sent },
    { label: "Delivered", value: funnel.delivered },
    { label: "Opened", value: funnel.opened },
    { label: "Clicked", value: funnel.clicked },
    { label: "Converted", value: funnel.converted },
  ];
  const max = Math.max(1, ...stages.map((s) => s.value));
  return (
    <div className="mk-funnel" role="list" aria-label="Delivery funnel">
      {stages.map((s, i) => {
        const prev = i > 0 ? stages[i - 1].value : null;
        const step = prev ? `${((100 * s.value) / prev).toFixed(1)}% of ${stages[i - 1].label.toLowerCase()}` : null;
        return (
          <div key={s.label} role="listitem">
            {i > 0 && <div className="mk-funnel__step">↓ {step ?? "—"}</div>}
            <div className="mk-funnel__row">
              <span style={{ fontWeight: 560 }}>{s.label}</span>
              <div className="mk-funnel__track">
                <div
                  className="mk-funnel__bar"
                  tabIndex={0}
                  title={`${s.label}: ${fmtNum(s.value)}${step ? ` (${step})` : ""}`}
                  aria-label={`${s.label}: ${fmtNum(s.value)}`}
                  style={{ width: `${(100 * s.value) / max}%`, background: funnelRamp[i] }}
                />
              </div>
              <span className="mk-num" style={{ fontWeight: 650, textAlign: "right" }}>
                {fmtNum(s.value)}
              </span>
            </div>
          </div>
        );
      })}
    </div>
  );
}

/* ---------------- Rate tiles ---------------- */
export function RateTiles({ funnel }: { funnel: Funnel }) {
  const tiles = [
    {
      label: "Delivery rate",
      value: funnel.deliveryRate,
      empty: funnel.sent > 0 ? "Not reported" : undefined,
      sub: `${fmtNum(funnel.delivered)} confirmed of ${fmtNum(funnel.sent)} sent · ${fmtNum(funnel.bounced)} bounced`,
    },
    { label: "Open rate (estimate)", value: funnel.openRate, sub: `${fmtNum(funnel.opened)} opened` },
    { label: "Click-through rate", value: funnel.clickRate, sub: `${fmtNum(funnel.clicked)} clicked` },
    { label: "Conversion rate", value: funnel.conversionRate, sub: `${fmtNum(funnel.converted)} converted · ${fmtNum(funnel.unsubscribed)} unsubscribed` },
  ];
  return (
    <div className="mk-kpis">
      {tiles.map((t) => (
        <div key={t.label} className="mk-kpi" style={{ gridTemplateColumns: "1fr" }}>
          <span className="mk-kpi__label">{t.label}</span>
          <span className="mk-kpi__value" style={{ fontSize: 22 }}>
            {t.value == null && "empty" in t && t.empty ? t.empty : fmtPct(t.value)}
          </span>
          <span className="mk-kpi__foot">{t.sub}</span>
        </div>
      ))}
    </div>
  );
}
