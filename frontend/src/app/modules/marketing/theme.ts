import type { AbElement, AbMetric, CampaignStatus, ContentChannel, Provenance } from "./api/types";

export type Tone = "neutral" | "info" | "ai" | "ok" | "warn" | "bad";

export const toneColors: Record<Tone, { fg: string; bg: string; line: string }> = {
  neutral: { fg: "#4B5563", bg: "#F0F1F3", line: "#DADCE0" },
  info: { fg: "#185FA5", bg: "#E6F1FB", line: "#C5DAEE" },
  ai: { fg: "#534AB7", bg: "#EEEDFE", line: "#C9C4F2" },
  ok: { fg: "#3B6D11", bg: "#EAF3DE", line: "#CFE3B9" },
  warn: { fg: "#854F0B", bg: "#FAEEDA", line: "#EBC98F" },
  bad: { fg: "#A32D2D", bg: "#FCEBEB", line: "#EFC2C2" },
};

export const campaignStatusMeta: Record<CampaignStatus, { label: string; tone: Tone }> = {
  DRAFT: { label: "Draft", tone: "neutral" },
  AI_PROCESSING: { label: "AI processing", tone: "ai" },
  PENDING_LEAD_APPROVAL: { label: "Pending lead approval", tone: "warn" },
  PENDING_CONTENT_APPROVAL: { label: "Pending content approval", tone: "warn" },
  READY_TO_LAUNCH: { label: "Ready to launch", tone: "info" },
  SCHEDULED: { label: "Scheduled", tone: "info" },
  ACTIVE: { label: "Active", tone: "ok" },
  PAUSED: { label: "Paused", tone: "warn" },
  COMPLETED: { label: "Completed", tone: "neutral" },
  CANCELLED: { label: "Cancelled", tone: "bad" },
};

/** Grouping used by the status filter: the two pending states read as one to most people. */
export const statusFilterOptions: { value: string; label: string; match: CampaignStatus[] }[] = [
  { value: "ALL", label: "All statuses", match: [] },
  { value: "DRAFT", label: "Draft", match: ["DRAFT"] },
  { value: "PROCESSING", label: "AI processing", match: ["AI_PROCESSING"] },
  { value: "PENDING", label: "Pending approval", match: ["PENDING_LEAD_APPROVAL", "PENDING_CONTENT_APPROVAL", "READY_TO_LAUNCH"] },
  { value: "SCHEDULED", label: "Scheduled", match: ["SCHEDULED"] },
  { value: "ACTIVE", label: "Active", match: ["ACTIVE"] },
  { value: "PAUSED", label: "Paused", match: ["PAUSED"] },
  { value: "COMPLETED", label: "Completed", match: ["COMPLETED"] },
  { value: "CANCELLED", label: "Cancelled", match: ["CANCELLED"] },
];

export const campaignTypeLabel = (t: string) =>
  ({
    PRODUCT_LAUNCH: "Product launch",
    PRODUCT_PROMOTION: "Product promotion",
    LEAD_NURTURING: "Lead nurturing",
    RE_ENGAGEMENT: "Re-engagement",
    SEASONAL: "Seasonal campaign",
    EVENT_PROMOTION: "Event promotion",
    CUSTOMER_RETENTION: "Customer retention",
    CROSS_SELL: "Cross-sell",
    UPSELL: "Upsell",
    CUSTOM: "Custom",
  })[t] ?? t;

export const channelLabel = (c: ContentChannel | string) => (c === "EMAIL" ? "Email" : c);

export const provenanceMeta: Record<Provenance, { label: string; cls: string; hint: string }> = {
  AI_GENERATED: { label: "AI generated", cls: "mk-tag--ai", hint: "Written by the AI model from your campaign inputs and checked for unsupported claims." },
  HUMAN_EDITED: { label: "Human edited", cls: "mk-tag--edited", hint: "AI or template output that a person has changed." },
  SYSTEM_GENERATED: { label: "System generated", cls: "mk-tag--system", hint: "Assembled by a template from your inputs because the AI output couldn't be used." },
  HUMAN_AUTHORED: { label: "Human authored", cls: "mk-tag--human", hint: "Written by a person." },
};

export const abElementLabel: Record<AbElement, string> = {
  EMAIL_SUBJECT: "Subject line",
  PREVIEW_TEXT: "Preview text",
  EMAIL_BODY: "Email body",
  CTA: "Call-to-action wording",
  EMAIL_STRUCTURE: "Email structure",
};

export const abMetricLabel: Record<AbMetric, string> = {
  OPEN_RATE: "Open rate",
  CLICK_RATE: "Click rate",
  CONVERSION_RATE: "Conversion rate",
  RESPONSE_RATE: "Response rate",
};

/** Email fields, in display order, with labels and whether multi-line. Mirrors ContentChannel.EMAIL. */
export const channelFields: Record<ContentChannel, { key: string; label: string; multiline?: boolean; limit: number; help?: string }[]> = {
  EMAIL: [
    { key: "subject", label: "Subject", limit: 120 },
    { key: "preheader", label: "Preview text", limit: 150, help: "Shown after the subject in most inboxes" },
    { key: "greeting", label: "Greeting", limit: 120, help: "Defaults to “Hello {{first_name}},”" },
    { key: "body", label: "Body", multiline: true, limit: 4000, help: "Blank line = new paragraph; lines starting with “- ” become a list" },
    { key: "offerDetails", label: "Product or offer details", multiline: true, limit: 800, help: "Shown highlighted below the body" },
    { key: "cta", label: "Call-to-action", limit: 40 },
    { key: "ctaUrl", label: "CTA link", limit: 500, help: "Defaults to the campaign's landing page" },
    { key: "signOff", label: "Sign-off", multiline: true, limit: 300, help: "Defaults to “Best regards,” and the sender name" },
  ],
};

/* Chart series. Fixed order from the validated categorical palette (dataviz
 * reference, light mode): colour follows the funnel stage, never its rank. */
export const seriesColors = {
  sent: "#2a78d6",
  delivered: "#eb6834",
  opened: "#1baf7a",
  clicked: "#eda100",
  converted: "#e87ba4",
} as const;

/* Ordinal single-hue ramp for the funnel bars, validated (--ordinal). */
export const funnelRamp = ["#104281", "#1c5cab", "#2a78d6", "#5598e7", "#86b6ef"];

/* ---------------- formatters ---------------- */

const nf = new Intl.NumberFormat("en-IN");
export const fmtNum = (n: number | null | undefined) => (n == null ? "—" : nf.format(n));
export const fmtPct = (n: number | null | undefined) => (n == null ? "—" : `${n.toFixed(1)}%`);
export const fmtInr = (n: number | null | undefined) =>
  n == null ? "—" : new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(n);

export const fmtDate = (s: string | null | undefined) =>
  s ? new Date(s.length === 10 ? `${s}T00:00:00` : s).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" }) : "—";

export const fmtDateTime = (s: string | null | undefined) =>
  s ? new Date(s).toLocaleString("en-IN", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "—";

export function fmtAgo(s: string | null | undefined): string {
  if (!s) return "—";
  const mins = Math.round((Date.now() - new Date(s).getTime()) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins} min ago`;
  const h = Math.round(mins / 60);
  if (h < 24) return `${h} h ago`;
  const d = Math.round(h / 24);
  return d === 1 ? "yesterday" : `${d} days ago`;
}

export const scoreTone = (s: number | null): Tone => (s == null ? "neutral" : s >= 75 ? "ok" : s >= 55 ? "info" : s >= 40 ? "warn" : "bad");
