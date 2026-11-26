import { colors } from "../../tokens";
import type { KpiFormat } from "./api/types";

/* ---------------- Chart palette ----------------
   Categorical slots in fixed order (validated CVD-safe as an ordered set), an
   ordinal blue ramp for ordered categories (funnel steps, score bands), and
   the reserved status colours, which only ever mean good/bad and always ship
   with a text label beside them. A series keeps its slot whatever else is on
   screen, so "Qualified" is the same orange on every chart. */
export const series = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"] as const;

/** Lightest step first. Starts at step 250 so the lightest mark still clears
 *  2:1 against the white card surface. */
export const ordinalRamp = ["#86b6ef", "#5598e7", "#2a78d6", "#1c5cab", "#104281"] as const;

export const status = {
  good: "#0ca30c",
  warning: "#fab219",
  serious: "#ec835a",
  critical: "#d03b3b",
  neutral: "#b9b8b2",
} as const;

export const chrome = {
  grid: "#e1e0d9",
  axis: "#c3c2b7",
  tick: "#898781",
  surface: colors.bgPrimary,
} as const;

/* ---------------- Numbers ---------------- */

const toNumber = (v: number | string | null | undefined): number | null => {
  if (v === null || v === undefined || v === "") return null;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : null;
};

/** Full rupee amount with lakh/crore grouping: ₹12,34,567. */
export function inr(v: number | string | null | undefined): string {
  const n = toNumber(v);
  if (n === null) return "—";
  return `₹${n.toLocaleString("en-IN", { maximumFractionDigits: 0 })}`;
}

/** Indian compact notation: 950, 12.4K, 3.2L, 1.8Cr. */
export function compact(v: number | string | null | undefined): string {
  const n = toNumber(v);
  if (n === null) return "—";
  const abs = Math.abs(n);
  const trim = (x: number) => (x >= 100 ? x.toFixed(0) : x.toFixed(1).replace(/\.0$/, ""));
  if (abs >= 1e7) return `${n < 0 ? "-" : ""}${trim(abs / 1e7)}Cr`;
  if (abs >= 1e5) return `${n < 0 ? "-" : ""}${trim(abs / 1e5)}L`;
  if (abs >= 1e3) return `${n < 0 ? "-" : ""}${trim(abs / 1e3)}K`;
  return n.toLocaleString("en-IN", { maximumFractionDigits: 1 });
}

export const inrCompact = (v: number | string | null | undefined): string => {
  const c = compact(v);
  return c === "—" ? c : `₹${c}`;
};

export function count(v: number | string | null | undefined): string {
  const n = toNumber(v);
  if (n === null) return "—";
  return Math.abs(n) >= 1e5 ? compact(n) : n.toLocaleString("en-IN");
}

export function pct(v: number | string | null | undefined, digits = 1): string {
  const n = toNumber(v);
  return n === null ? "—" : `${n.toFixed(digits).replace(/\.0$/, "")}%`;
}

export function hours(v: number | string | null | undefined): string {
  const n = toNumber(v);
  if (n === null) return "—";
  if (n < 1) return `${Math.round(n * 60)} min`;
  if (n < 48) return `${n.toFixed(1).replace(/\.0$/, "")} h`;
  return `${(n / 24).toFixed(1).replace(/\.0$/, "")} days`;
}

export function formatKpi(format: KpiFormat, v: number | string | null | undefined): string {
  switch (format) {
    case "currency":
      return inrCompact(v);
    case "percent":
      return pct(v);
    case "score":
      return toNumber(v) === null ? "—" : (toNumber(v) as number).toFixed(1);
    case "hours":
      return hours(v);
    default:
      return count(v);
  }
}

export type Delta = { text: string; direction: "up" | "down" | "flat"; good: boolean | null };

/**
 * Change against the previous period. Percent figures change in points (a win
 * rate going 20% → 25% is +5 pts, not +25%); everything else in relative %.
 * A previous value of zero has no meaningful ratio, so it reads "New".
 */
export function delta(format: KpiFormat, value: number | string | null, previous: number | string | null, lowerIsBetter = false): Delta | null {
  const cur = toNumber(value);
  const prev = toNumber(previous);
  if (cur === null || prev === null) return null;
  const diff = format === "percent" || format === "score" ? cur - prev : prev === 0 ? null : ((cur - prev) / Math.abs(prev)) * 100;
  if (diff === null) {
    return cur === 0 ? { text: "No change", direction: "flat", good: null } : { text: "New this period", direction: "up", good: !lowerIsBetter };
  }
  if (Math.abs(diff) < 0.05) return { text: "No change", direction: "flat", good: null };
  const direction = diff > 0 ? "up" : "down";
  const unit = format === "percent" ? " pts" : format === "score" ? "" : "%";
  const magnitude = Math.abs(diff) >= 1000 ? compact(Math.abs(diff)) : Math.abs(diff).toFixed(1).replace(/\.0$/, "");
  return {
    text: `${diff > 0 ? "+" : "−"}${magnitude}${unit}`,
    direction,
    good: lowerIsBetter ? direction === "down" : direction === "up",
  };
}

/* ---------------- Dates ---------------- */

export type RangeKey = "7d" | "30d" | "90d" | "ytd" | "custom";
export type DateRange = { key: RangeKey; from: string; to: string };

export const isoDate = (d: Date): string => {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
};

export function presetRange(key: Exclude<RangeKey, "custom">): DateRange {
  const today = new Date();
  const from = new Date(today);
  if (key === "ytd") {
    from.setMonth(0, 1);
  } else {
    from.setDate(today.getDate() - (Number(key.replace("d", "")) - 1));
  }
  return { key, from: isoDate(from), to: isoDate(today) };
}

const parse = (d: string) => new Date(`${d}T00:00:00`);

export function shortDate(d: string): string {
  return parse(d).toLocaleDateString("en-IN", { day: "numeric", month: "short" });
}

export function longDate(d: string): string {
  return parse(d).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

/** Axis label for a bucket start, matched to the bucket size. */
export function bucketLabel(d: string, granularity: string): string {
  if (granularity === "month") return parse(d).toLocaleDateString("en-IN", { month: "short", year: "2-digit" });
  if (granularity === "week") return `Wk of ${shortDate(d)}`;
  return shortDate(d);
}

/* ---------------- Labels ---------------- */

export function humanize(key: string | null | undefined): string {
  if (!key) return "—";
  return key
    .toLowerCase()
    .split("_")
    .map((w) => (w ? w[0].toUpperCase() + w.slice(1) : w))
    .join(" ");
}
