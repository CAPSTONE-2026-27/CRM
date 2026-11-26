import { useEffect, useMemo, useState } from "react";
import { BarChart3, Bot, LayoutDashboard, Megaphone, Target, Users } from "lucide-react";
import { colors } from "../../tokens";
import { useAnalyticsMeta } from "./api/hooks";
import type { AnalyticsFilters, Section } from "./api/types";
import { ErrorState, Segmented, Select, ShimmerStyles, Skeleton, controlStyle } from "./components/ui";
import { humanize, isoDate, longDate, presetRange, type DateRange, type RangeKey } from "./format";
import { OverviewTab } from "./sections/OverviewTab";
import { LeadsTab } from "./sections/LeadsTab";
import { DealsTab } from "./sections/DealsTab";
import { TeamTab } from "./sections/TeamTab";
import { AiTab } from "./sections/AiTab";
import { CampaignsTab } from "./sections/CampaignsTab";

const TABS: { id: Section; label: string; icon: typeof BarChart3 }[] = [
  { id: "OVERVIEW", label: "Overview", icon: LayoutDashboard },
  { id: "LEADS", label: "Leads", icon: Target },
  { id: "DEALS", label: "Deals", icon: BarChart3 },
  { id: "TEAM", label: "Team", icon: Users },
  { id: "AI", label: "Meetings & AI", icon: Bot },
  { id: "CAMPAIGNS", label: "Campaigns", icon: Megaphone },
];

const RANGE_OPTIONS: { value: RangeKey; label: string }[] = [
  { value: "7d", label: "7 days" },
  { value: "30d", label: "30 days" },
  { value: "90d", label: "90 days" },
  { value: "ytd", label: "This year" },
  { value: "custom", label: "Custom" },
];

const TAB_STORAGE_KEY = "techcrm.analytics.tab";

function readStoredTab(): Section | null {
  try {
    return (window.localStorage.getItem(TAB_STORAGE_KEY) as Section | null) ?? null;
  } catch {
    return null;
  }
}

/**
 * Analytics & reporting. Every figure comes from /api/analytics/*, which
 * aggregates in the database and scopes by role; the tabs shown are the
 * sections the server says this user may see.
 */
export function AnalyticsDashboard() {
  const meta = useAnalyticsMeta();
  const [range, setRange] = useState<DateRange>(() => presetRange("30d"));
  const [ownerId, setOwnerId] = useState("");
  const [source, setSource] = useState("");
  const [stage, setStage] = useState("");
  const [tab, setTab] = useState<Section>(() => readStoredTab() ?? "OVERVIEW");

  const sections = meta.data?.sections ?? [];
  const visibleTabs = TABS.filter((t) => sections.includes(t.id));
  const activeTab: Section = visibleTabs.some((t) => t.id === tab) ? tab : visibleTabs[0]?.id ?? "OVERVIEW";

  useEffect(() => {
    try {
      window.localStorage.setItem(TAB_STORAGE_KEY, activeTab);
    } catch {
      /* storage unavailable — the tab simply isn't remembered */
    }
  }, [activeTab]);

  const filters: AnalyticsFilters = useMemo(
    () => ({ from: range.from, to: range.to, ownerId: ownerId || undefined, source: source || undefined, stage: stage || undefined }),
    [range.from, range.to, ownerId, source, stage]
  );

  const onRange = (key: RangeKey) => {
    if (key === "custom") setRange((r) => ({ ...r, key }));
    else setRange(presetRange(key));
  };

  if (meta.isLoading) {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        <ShimmerStyles />
        <Skeleton height={40} />
        <Skeleton height={320} />
      </div>
    );
  }
  if (meta.error || !meta.data) {
    return <ErrorState error={meta.error} onRetry={() => meta.refetch()} height={240} />;
  }

  const showOwner = !meta.data.scopedToSelf && meta.data.owners.length > 0 && ["OVERVIEW", "LEADS", "DEALS", "AI"].includes(activeTab);
  const today = isoDate(new Date());

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <ShimmerStyles />

      {/* Tabs */}
      <nav role="tablist" aria-label="Analytics sections" style={{ display: "flex", gap: 4, borderBottom: `0.5px solid ${colors.border}`, overflowX: "auto", overflowY: "hidden" }}>
        {visibleTabs.map((t) => {
          const active = t.id === activeTab;
          const Icon = t.icon;
          return (
            <button
              key={t.id}
              role="tab"
              aria-selected={active}
              onClick={() => setTab(t.id)}
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 6,
                border: "none",
                background: "transparent",
                padding: "8px 12px",
                fontSize: 13,
                fontWeight: active ? 600 : 500,
                color: active ? colors.primary : colors.textSecondary,
                borderBottom: `2px solid ${active ? colors.primary : "transparent"}`,
                marginBottom: -0.5,
                cursor: "pointer",
                whiteSpace: "nowrap",
              }}
            >
              <Icon size={14} />
              {t.label}
            </button>
          );
        })}
      </nav>

      {/* Filters — one row, driving every widget below */}
      <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 10 }}>
        <Segmented label="Date range" value={range.key} onChange={onRange} options={RANGE_OPTIONS} />
        {range.key === "custom" && (
          <div style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
            <input type="date" aria-label="From" style={controlStyle} value={range.from} max={range.to} onChange={(e) => e.target.value && setRange((r) => ({ ...r, from: e.target.value }))} />
            <span style={{ fontSize: 12, color: colors.textTertiary }}>to</span>
            <input type="date" aria-label="To" style={controlStyle} value={range.to} min={range.from} max={today} onChange={(e) => e.target.value && setRange((r) => ({ ...r, to: e.target.value }))} />
          </div>
        )}
        {showOwner && (
          <Select label="Owner" value={ownerId} onChange={setOwnerId} allLabel="All owners" options={meta.data.owners} />
        )}
        {activeTab === "LEADS" && meta.data.sources.length > 0 && (
          <Select label="Source" value={source} onChange={setSource} allLabel="All sources" options={meta.data.sources.map((s) => ({ value: s, label: s }))} />
        )}
        {activeTab === "DEALS" && (
          <Select label="Stage" value={stage} onChange={setStage} allLabel="All stages" options={meta.data.stages.map((s) => ({ value: s, label: humanize(s) }))} />
        )}
        <span style={{ marginLeft: "auto", fontSize: 11, color: colors.textTertiary }}>
          {longDate(range.from)} – {longDate(range.to)}
          {meta.data.scopedToSelf ? " · Your deals and leads only" : ""}
        </span>
      </div>

      <div role="tabpanel">
        {activeTab === "OVERVIEW" && <OverviewTab filters={filters} sections={sections} />}
        {activeTab === "LEADS" && <LeadsTab filters={filters} />}
        {activeTab === "DEALS" && <DealsTab filters={filters} />}
        {activeTab === "TEAM" && <TeamTab filters={filters} />}
        {activeTab === "AI" && <AiTab filters={filters} />}
        {activeTab === "CAMPAIGNS" && <CampaignsTab filters={filters} />}
      </div>
    </div>
  );
}
