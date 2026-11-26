import { useState } from "react";
import { Bar, BarChart, CartesianGrid, Cell, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { colors } from "../../../tokens";
import { bucketLabel, chrome, compact, longDate } from "../format";
import { DataTable, Legend, Segmented, TooltipBox, axisProps } from "./ui";

/* ---------------- Time series ----------------
   Lines for change over time, one shared y-axis (every series here is the
   same unit), a legend always, a crosshair tooltip, and a table view for
   readers who want the exact numbers. */

export type SeriesDef<K extends string> = { key: K; label: string; color: string };

type Point = { bucket: string } & Record<string, number | string | null>;

export function TrendChart<K extends string>({
  data,
  series,
  granularity,
  format = compact,
  height = 240,
  kind = "line",
  domain,
}: {
  data: Point[];
  series: SeriesDef<K>[];
  granularity: string;
  format?: (n: number) => string;
  height?: number;
  kind?: "line" | "bar";
  domain?: [number, number];
}) {
  const [view, setView] = useState<"chart" | "table">("chart");
  const renderTooltip = ({ active, label, payload }: { active?: boolean; label?: string; payload?: { dataKey: string; value: number | null }[] }) => {
    if (!active || !label || !payload?.length) return null;
    return (
      <TooltipBox
        title={granularity === "day" ? longDate(label) : bucketLabel(label, granularity)}
        rows={series.map((s) => {
          const v = payload.find((p) => p.dataKey === s.key)?.value;
          return { label: s.label, value: v == null ? "—" : format(Number(v)), color: s.color };
        })}
      />
    );
  };

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8, marginBottom: 10, flexWrap: "wrap" }}>
        {series.length > 1 ? <Legend items={series.map((s) => ({ label: s.label, color: s.color }))} /> : <span />}
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
      {view === "table" ? (
        <div style={{ maxHeight: height, overflowY: "auto" }}>
          <DataTable
            rows={data}
            rowKey={(r) => r.bucket}
            columns={[
              { header: "Period", render: (r) => bucketLabel(r.bucket, granularity), width: "1.3fr" },
              ...series.map((s) => ({
                header: s.label,
                align: "right" as const,
                render: (r: Point) => (r[s.key] == null ? "—" : format(Number(r[s.key]))),
              })),
            ]}
          />
        </div>
      ) : (
        <div style={{ width: "100%", height }}>
          <ResponsiveContainer>
            {kind === "bar" ? (
              <BarChart data={data} margin={{ top: 4, right: 4, bottom: 0, left: -12 }} barCategoryGap="20%">
                <CartesianGrid vertical={false} stroke={chrome.grid} />
                <XAxis dataKey="bucket" {...axisProps} tickFormatter={(d: string) => bucketLabel(d, granularity)} minTickGap={16} />
                <YAxis {...axisProps} axisLine={false} tickFormatter={(n: number) => format(n)} allowDecimals={false} width={48} domain={domain} />
                <Tooltip content={renderTooltip as never} cursor={{ fill: colors.bgSecondary }} />
                {series.map((s, i) => (
                  <Bar
                    key={s.key}
                    dataKey={s.key}
                    stackId="stack"
                    fill={s.color}
                    maxBarSize={24}
                    stroke={chrome.surface}
                    strokeWidth={1}
                    radius={i === series.length - 1 ? [4, 4, 0, 0] : [0, 0, 0, 0]}
                  />
                ))}
              </BarChart>
            ) : (
              <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -12 }}>
                <CartesianGrid vertical={false} stroke={chrome.grid} />
                <XAxis dataKey="bucket" {...axisProps} tickFormatter={(d: string) => bucketLabel(d, granularity)} minTickGap={16} />
                <YAxis {...axisProps} axisLine={false} tickFormatter={(n: number) => format(n)} allowDecimals={false} width={48} domain={domain} />
                <Tooltip content={renderTooltip as never} cursor={{ stroke: chrome.axis }} />
                {series.map((s) => (
                  <Line
                    key={s.key}
                    type="linear"
                    dataKey={s.key}
                    stroke={s.color}
                    strokeWidth={2}
                    dot={data.length <= 12 ? { r: 3, strokeWidth: 2, stroke: chrome.surface, fill: s.color } : false}
                    activeDot={{ r: 5, strokeWidth: 2, stroke: chrome.surface }}
                    connectNulls
                    isAnimationActive={false}
                  />
                ))}
              </LineChart>
            )}
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

/* ---------------- Distribution columns ----------------
   Ordered buckets (score bands, tiers): one column per bucket, coloured by an
   ordinal ramp or per-bucket colour, value labels on hover. */

export function ColumnChart({
  data,
  height = 220,
  valueLabel = "Count",
  format = (n: number) => n.toLocaleString("en-IN"),
}: {
  data: { label: string; value: number; color: string }[];
  height?: number;
  valueLabel?: string;
  format?: (n: number) => string;
}) {
  const total = data.reduce((s, d) => s + d.value, 0);
  return (
    <div style={{ width: "100%", height }}>
      <ResponsiveContainer>
        <BarChart data={data} margin={{ top: 16, right: 4, bottom: 0, left: -12 }} barCategoryGap="22%">
          <CartesianGrid vertical={false} stroke={chrome.grid} />
          <XAxis dataKey="label" {...axisProps} interval={0} />
          <YAxis {...axisProps} axisLine={false} allowDecimals={false} width={40} />
          <Tooltip
            cursor={{ fill: colors.bgSecondary }}
            content={
              (({ active, payload }: { active?: boolean; payload?: { payload: { label: string; value: number; color: string } }[] }) => {
                const p = payload?.[0]?.payload;
                if (!active || !p) return null;
                return (
                  <TooltipBox
                    title={p.label}
                    rows={[
                      { label: valueLabel, value: format(p.value), color: p.color },
                      { label: "Share", value: total ? `${Math.round((p.value / total) * 100)}%` : "—" },
                    ]}
                  />
                );
              }) as never
            }
          />
          <Bar dataKey="value" maxBarSize={40} radius={[4, 4, 0, 0]} isAnimationActive={false} label={{ position: "top", fontSize: 10, fill: colors.textSecondary }}>
            {data.map((d) => (
              <Cell key={d.label} fill={d.color} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
