import type { CSSProperties, ReactNode } from "react";
import { AlertTriangle, ArrowDownRight, ArrowUpRight, Minus, RefreshCw } from "lucide-react";
import { colors } from "../../../tokens";
import { chrome, delta as computeDelta, formatKpi } from "../format";
import type { Kpi } from "../api/types";

/* ---------------- Layout ---------------- */

/** A responsive grid: as many columns of at least `min` px as fit. */
export function Grid({ min = 320, gap = 16, children }: { min?: number; gap?: number; children: ReactNode }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: `repeat(auto-fit, minmax(min(${min}px, 100%), 1fr))`, gap }}>
      {children}
    </div>
  );
}

export function Panel({
  title,
  subtitle,
  right,
  children,
  span,
  dimmed,
}: {
  title: string;
  subtitle?: string;
  right?: ReactNode;
  children: ReactNode;
  /** Take the full row of the parent grid. */
  span?: boolean;
  /** Refetching behind the current numbers. */
  dimmed?: boolean;
}) {
  return (
    <section
      style={{
        gridColumn: span ? "1 / -1" : undefined,
        background: colors.bgPrimary,
        border: `0.5px solid ${colors.border}`,
        borderRadius: 8,
        padding: 16,
        minWidth: 0,
        display: "flex",
        flexDirection: "column",
      }}
    >
      <header style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 12, marginBottom: 14 }}>
        <div style={{ minWidth: 0 }}>
          <h3 style={{ margin: 0, fontSize: 13, fontWeight: 600, color: colors.textPrimary }}>{title}</h3>
          {subtitle && <div style={{ fontSize: 11, color: colors.textSecondary, marginTop: 3 }}>{subtitle}</div>}
        </div>
        {right}
      </header>
      <div style={{ flex: 1, minWidth: 0, opacity: dimmed ? 0.55 : 1, transition: "opacity .15s" }}>{children}</div>
    </section>
  );
}

/* ---------------- States ---------------- */

const shimmer: CSSProperties = {
  background: `linear-gradient(90deg, ${colors.bgSecondary} 0%, #eceef1 50%, ${colors.bgSecondary} 100%)`,
  backgroundSize: "200% 100%",
  animation: "analytics-shimmer 1.2s ease-in-out infinite",
  borderRadius: 4,
};

export function ShimmerStyles() {
  return <style>{`@keyframes analytics-shimmer { 0% { background-position: 200% 0 } 100% { background-position: -200% 0 } }`}</style>;
}

export function Skeleton({ height = 220 }: { height?: number }) {
  return (
    <div aria-busy="true" aria-label="Loading" style={{ display: "flex", flexDirection: "column", gap: 10, height }}>
      <div style={{ ...shimmer, height: 12, width: "40%" }} />
      <div style={{ ...shimmer, flex: 1 }} />
    </div>
  );
}

export function EmptyState({ message, hint, height = 180 }: { message: string; hint?: string; height?: number }) {
  return (
    <div
      style={{
        minHeight: height,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        textAlign: "center",
        gap: 4,
        padding: 16,
        border: `1px dashed ${colors.border}`,
        borderRadius: 6,
        background: colors.bgSecondary,
      }}
    >
      <div style={{ fontSize: 12, fontWeight: 500, color: colors.textSecondary }}>{message}</div>
      {hint && <div style={{ fontSize: 11, color: colors.textTertiary, maxWidth: 360 }}>{hint}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry, height = 180 }: { error: unknown; onRetry?: () => void; height?: number }) {
  const message = error instanceof Error ? error.message : "Something went wrong";
  return (
    <div
      role="alert"
      style={{
        minHeight: height,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        gap: 8,
        padding: 16,
        borderRadius: 6,
        background: colors.dangerLight,
        textAlign: "center",
      }}
    >
      <AlertTriangle size={18} color={colors.danger} />
      <div style={{ fontSize: 12, color: colors.danger, fontWeight: 500 }}>Couldn't load this report</div>
      <div style={{ fontSize: 11, color: colors.textSecondary, maxWidth: 360 }}>{message}</div>
      {onRetry && (
        <button
          onClick={onRetry}
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 6,
            border: `0.5px solid ${colors.border}`,
            background: colors.bgPrimary,
            borderRadius: 6,
            padding: "5px 10px",
            fontSize: 12,
            cursor: "pointer",
            color: colors.textPrimary,
          }}
        >
          <RefreshCw size={12} /> Retry
        </button>
      )}
    </div>
  );
}

/**
 * Renders the right state for a query-backed widget: skeleton on first load,
 * error with retry, the empty message when `isEmpty` says there is nothing to
 * draw, otherwise the content.
 */
export function QueryBody<T>({
  query,
  isEmpty,
  empty,
  emptyHint,
  height = 220,
  children,
}: {
  query: { data: T | undefined; isLoading: boolean; error: unknown; refetch: () => unknown };
  isEmpty?: (data: T) => boolean;
  empty?: string;
  emptyHint?: string;
  height?: number;
  children: (data: T) => ReactNode;
}) {
  if (query.isLoading && !query.data) return <Skeleton height={height} />;
  if (query.error && !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} height={height} />;
  if (!query.data) return <Skeleton height={height} />;
  if (isEmpty?.(query.data)) return <EmptyState message={empty ?? "No data in this period"} hint={emptyHint} height={height} />;
  return <>{children(query.data)}</>;
}

/* ---------------- KPI card ---------------- */

const LOWER_IS_BETTER = new Set(["avgResolution", "openTickets"]);

export function KpiCard({ kpi, compareLabel }: { kpi: Kpi; compareLabel: string }) {
  const d = computeDelta(kpi.format, kpi.value, kpi.previous, LOWER_IS_BETTER.has(kpi.key));
  const tone = d?.good == null ? colors.textTertiary : d.good ? colors.success : colors.danger;
  const Icon = !d || d.direction === "flat" ? Minus : d.direction === "up" ? ArrowUpRight : ArrowDownRight;
  return (
    <div
      title={kpi.hint ?? undefined}
      style={{
        background: colors.bgPrimary,
        border: `0.5px solid ${colors.border}`,
        borderRadius: 8,
        padding: "12px 14px",
        minWidth: 0,
      }}
    >
      <div style={{ fontSize: 11, color: colors.textSecondary, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{kpi.label}</div>
      <div style={{ fontSize: 22, fontWeight: 600, color: colors.textPrimary, margin: "6px 0 4px", letterSpacing: -0.2 }}>
        {formatKpi(kpi.format, kpi.value)}
      </div>
      {kpi.previous === null ? (
        <div style={{ fontSize: 11, color: colors.textTertiary }}>{kpi.hint ?? "Current snapshot"}</div>
      ) : d ? (
        <div style={{ display: "flex", alignItems: "center", gap: 4, fontSize: 11, flexWrap: "wrap" }}>
          <span style={{ display: "inline-flex", alignItems: "center", gap: 2, color: tone, fontWeight: 600 }}>
            <Icon size={12} aria-hidden />
            {d.text}
          </span>
          <span style={{ color: colors.textTertiary }}>vs {compareLabel}</span>
        </div>
      ) : (
        <div style={{ fontSize: 11, color: colors.textTertiary }}>No comparison available</div>
      )}
    </div>
  );
}

export function KpiSkeleton() {
  return (
    <div style={{ background: colors.bgPrimary, border: `0.5px solid ${colors.border}`, borderRadius: 8, padding: "12px 14px" }}>
      <div style={{ ...shimmer, height: 10, width: "55%" }} />
      <div style={{ ...shimmer, height: 22, width: "70%", margin: "8px 0 6px" }} />
      <div style={{ ...shimmer, height: 10, width: "45%" }} />
    </div>
  );
}

/* ---------------- Stat tile (inline headline figure) ---------------- */

export function Stat({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: string }) {
  return (
    <div style={{ minWidth: 0 }}>
      <div style={{ fontSize: 11, color: colors.textSecondary }}>{label}</div>
      <div style={{ fontSize: 18, fontWeight: 600, color: tone ?? colors.textPrimary, marginTop: 2 }}>{value}</div>
      {sub && <div style={{ fontSize: 11, color: colors.textTertiary, marginTop: 1 }}>{sub}</div>}
    </div>
  );
}

export function StatRow({ children }: { children: ReactNode }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(110px, 1fr))", gap: 12, marginBottom: 14 }}>
      {children}
    </div>
  );
}

/* ---------------- Legend ---------------- */

export function Legend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <div role="list" aria-label="Legend" style={{ display: "flex", flexWrap: "wrap", gap: "4px 14px", fontSize: 11, color: colors.textSecondary }}>
      {items.map((i) => (
        <span role="listitem" key={i.label} style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
          <i aria-hidden style={{ width: 10, height: 10, borderRadius: 2, background: i.color, display: "inline-block" }} />
          {i.label}
        </span>
      ))}
    </div>
  );
}

/* ---------------- Tooltip (Recharts content) ---------------- */

export function TooltipBox({ title, rows }: { title: string; rows: { label: string; value: string; color?: string }[] }) {
  return (
    <div
      style={{
        background: colors.bgPrimary,
        border: `0.5px solid ${colors.border}`,
        borderRadius: 6,
        boxShadow: "0 4px 14px rgba(0,0,0,0.08)",
        padding: "8px 10px",
        fontSize: 11,
        minWidth: 140,
      }}
    >
      <div style={{ fontWeight: 600, color: colors.textPrimary, marginBottom: 4 }}>{title}</div>
      {rows.map((r) => (
        <div key={r.label} style={{ display: "flex", alignItems: "center", gap: 6, padding: "1px 0" }}>
          {r.color && <i aria-hidden style={{ width: 8, height: 8, borderRadius: 2, background: r.color, display: "inline-block" }} />}
          <span style={{ color: colors.textSecondary, flex: 1 }}>{r.label}</span>
          <b style={{ color: colors.textPrimary, fontVariantNumeric: "tabular-nums" }}>{r.value}</b>
        </div>
      ))}
    </div>
  );
}

/* ---------------- Horizontal bar list ----------------
   For ranked or ordered categories: label, a thin bar scaled to the largest
   value, and the figure beside it. Hover shows the full breakdown. */

export type BarItem = { label: string; value: number; display: string; sub?: string; color?: string; title?: string };

export function BarList({ items, color = "#2a78d6" }: { items: BarItem[]; color?: string }) {
  const max = Math.max(1, ...items.map((i) => i.value));
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      {items.map((i) => (
        <div key={i.label} title={i.title ?? `${i.label}: ${i.display}${i.sub ? ` · ${i.sub}` : ""}`}>
          <div style={{ display: "flex", justifyContent: "space-between", gap: 8, fontSize: 12, marginBottom: 4 }}>
            <span style={{ color: colors.textPrimary, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{i.label}</span>
            <span style={{ color: colors.textPrimary, fontWeight: 600, fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}>
              {i.display}
              {i.sub && <span style={{ color: colors.textTertiary, fontWeight: 400, marginLeft: 6 }}>{i.sub}</span>}
            </span>
          </div>
          <div style={{ height: 8, background: colors.bgSecondary, borderRadius: 4 }}>
            <div
              style={{
                width: `${i.value > 0 ? Math.max(2, (i.value / max) * 100) : 0}%`,
                height: "100%",
                background: i.color ?? color,
                borderRadius: 4,
                transition: "width .3s",
              }}
            />
          </div>
        </div>
      ))}
    </div>
  );
}

/* ---------------- Segmented proportion bar ----------------
   Part-to-whole for a handful of categories, with a labelled legend so no
   segment relies on colour alone. 2px surface gaps between segments. */

export function SegmentBar({ parts, format = (n) => n.toLocaleString("en-IN") }: { parts: { label: string; value: number; color: string }[]; format?: (n: number) => string }) {
  const total = parts.reduce((s, p) => s + p.value, 0);
  return (
    <div>
      <div style={{ display: "flex", height: 12, gap: 2, borderRadius: 4, overflow: "hidden", background: total ? undefined : colors.bgSecondary }}>
        {parts.filter((p) => p.value > 0).map((p) => (
          <div key={p.label} title={`${p.label}: ${format(p.value)} (${Math.round((p.value / total) * 100)}%)`} style={{ flex: p.value, background: p.color }} />
        ))}
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: "6px 16px", marginTop: 10 }}>
        {parts.map((p) => (
          <div key={p.label} style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 11 }}>
            <i aria-hidden style={{ width: 10, height: 10, borderRadius: 2, background: p.color, display: "inline-block" }} />
            <span style={{ color: colors.textSecondary }}>{p.label}</span>
            <b style={{ color: colors.textPrimary, fontVariantNumeric: "tabular-nums" }}>{format(p.value)}</b>
            {total > 0 && <span style={{ color: colors.textTertiary }}>{Math.round((p.value / total) * 100)}%</span>}
          </div>
        ))}
      </div>
    </div>
  );
}

/* ---------------- Table ---------------- */

export type Column<T> = { header: string; align?: "left" | "right"; width?: string; render: (row: T) => ReactNode };

export function DataTable<T>({ columns, rows, rowKey }: { columns: Column<T>[]; rows: T[]; rowKey: (row: T) => string }) {
  const template = columns.map((c) => c.width ?? "1fr").join(" ");
  const cell = (align?: "left" | "right"): CSSProperties => ({
    textAlign: align ?? "left",
    padding: "8px 10px",
    minWidth: 0,
    overflow: "hidden",
    textOverflow: "ellipsis",
    whiteSpace: "nowrap",
    fontVariantNumeric: align === "right" ? "tabular-nums" : undefined,
  });
  return (
    <div style={{ overflowX: "auto" }}>
      <div role="table" style={{ minWidth: columns.length * 72, fontSize: 12 }}>
        <div role="row" style={{ display: "grid", gridTemplateColumns: template, borderBottom: `0.5px solid ${colors.border}`, background: colors.bgSecondary, borderRadius: "6px 6px 0 0" }}>
          {columns.map((c) => (
            <div role="columnheader" key={c.header} style={{ ...cell(c.align), fontSize: 11, fontWeight: 600, color: colors.textSecondary }}>
              {c.header}
            </div>
          ))}
        </div>
        {rows.map((r) => (
          <div role="row" key={rowKey(r)} style={{ display: "grid", gridTemplateColumns: template, borderBottom: `0.5px solid ${colors.border}` }}>
            {columns.map((c) => (
              <div role="cell" key={c.header} style={{ ...cell(c.align), color: colors.textPrimary }}>
                {c.render(r)}
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

/* ---------------- Status pill (label + colour, never colour alone) ---------------- */

export function Pill({ label, color, bg }: { label: string; color: string; bg: string }) {
  return (
    <span style={{ display: "inline-block", padding: "2px 8px", borderRadius: 10, fontSize: 11, fontWeight: 500, color, background: bg }}>
      {label}
    </span>
  );
}

/* ---------------- Controls ---------------- */

export function Segmented<T extends string>({ value, options, onChange, label }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; label: string }) {
  return (
    <div role="radiogroup" aria-label={label} style={{ display: "inline-flex", border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", background: colors.bgPrimary }}>
      {options.map((o, i) => {
        const active = o.value === value;
        return (
          <button
            key={o.value}
            role="radio"
            aria-checked={active}
            onClick={() => onChange(o.value)}
            style={{
              border: "none",
              borderLeft: i ? `0.5px solid ${colors.border}` : "none",
              background: active ? colors.primaryLight : "transparent",
              color: active ? colors.primary : colors.textSecondary,
              fontWeight: active ? 600 : 500,
              fontSize: 12,
              padding: "6px 11px",
              cursor: "pointer",
              whiteSpace: "nowrap",
            }}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

export const controlStyle: CSSProperties = {
  border: `0.5px solid ${colors.border}`,
  borderRadius: 6,
  padding: "6px 8px",
  fontSize: 12,
  color: colors.textPrimary,
  background: colors.bgPrimary,
  minWidth: 0,
};

export function Select({ label, value, onChange, options, allLabel }: { label: string; value: string; onChange: (v: string) => void; options: { value: string; label: string }[]; allLabel: string }) {
  return (
    <label style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 11, color: colors.textSecondary }}>
      {label}
      <select value={value} onChange={(e) => onChange(e.target.value)} style={{ ...controlStyle, maxWidth: 200 }}>
        <option value="">{allLabel}</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
}

export const axisProps = {
  tick: { fontSize: 10, fill: chrome.tick },
  axisLine: { stroke: chrome.axis },
  tickLine: false,
} as const;
