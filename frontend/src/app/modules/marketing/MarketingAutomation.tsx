import { useMemo, useState } from "react";
import { BarChart3, ClipboardCheck, LayoutGrid, ListChecks, Plus, Search, ShieldAlert } from "lucide-react";
import { MK_CSS } from "./styles";
import { useApprovalQueue, useCampaignList, useMarketingMeta } from "./api/hooks";
import type { CampaignSummary } from "./api/types";
import { Btn, Callout, Tabs } from "./components/ui";
import type { Nav } from "./components/campaignActions";
import { campaignTypeLabel, statusFilterOptions } from "./theme";
import { ApprovalsTab, CampaignsTab, KpiStrip, OverviewTab, PerformanceTab } from "./screens/HomeTabs";
import { CampaignForm } from "./screens/CampaignForm";
import { CampaignWorkspace, WorkspaceTab } from "./screens/CampaignWorkspace";

type HomeTab = "overview" | "campaigns" | "approvals" | "performance";
type View =
  | { kind: "home"; tab: HomeTab }
  | { kind: "create" }
  | { kind: "edit"; id: string }
  | { kind: "campaign"; id: string; tab: WorkspaceTab };

type DateFilter = "ANY" | "RUNNING" | "UPCOMING" | "LAST_90";

const today = () => new Date().toISOString().slice(0, 10);
const plusDays = (n: number) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);

function matchesDate(c: CampaignSummary, f: DateFilter): boolean {
  if (f === "ANY") return true;
  const t = today();
  if (f === "RUNNING") return !!c.startDate && c.startDate <= t && (!c.endDate || c.endDate >= t);
  if (f === "UPCOMING") return !!c.startDate && c.startDate > t && c.startDate <= plusDays(30);
  return c.createdAt.slice(0, 10) >= plusDays(-90);
}

/**
 * Marketing Automation (Module 11): the module's single entry point. Holds the
 * in-module navigation (dashboard tabs → campaign workspace → form) so the
 * host app only needs to render this one component.
 */
export function MarketingAutomation() {
  const [view, setView] = useState<View>({ kind: "home", tab: "overview" });
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("ALL");
  const [type, setType] = useState("ALL");
  const [dateFilter, setDateFilter] = useState<DateFilter>("ANY");

  const meta = useMarketingMeta();
  const list = useCampaignList();
  const queue = useApprovalQueue();
  const isApprover = !!meta.data?.viewerIsApprover;

  const nav: Nav = {
    openCampaign: (id, tab) => setView({ kind: "campaign", id, tab: (tab as WorkspaceTab) ?? "overview" }),
    editCampaign: (id) => setView({ kind: "edit", id }),
    create: () => setView({ kind: "create" }),
    home: (tab) => setView({ kind: "home", tab: (tab as HomeTab) ?? "overview" }),
  };

  const campaigns = list.data ?? [];
  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const statusMatch = statusFilterOptions.find((o) => o.value === status)?.match ?? [];
    return campaigns.filter(
      (c) =>
        (!needle || c.name.toLowerCase().includes(needle) || campaignTypeLabel(c.campaignType).toLowerCase().includes(needle)) &&
        (statusMatch.length === 0 || statusMatch.includes(c.status)) &&
        (type === "ALL" || c.campaignType === type) &&
        matchesDate(c, dateFilter)
    );
  }, [campaigns, q, status, type, dateFilter]);
  const isFiltered = !!q.trim() || status !== "ALL" || type !== "ALL" || dateFilter !== "ANY";

  const aiWarning =
    meta.data && !meta.data.ai.selfHosted && !meta.data.ai.externalProviderAllowed
      ? "The configured AI endpoint is an external provider that isn't authorised to receive campaign data, so leads are scored by the rules engine and content is drafted from templates."
      : meta.data?.ai.lastCallSucceeded === false
        ? `The AI model (${meta.data.ai.model}) didn't respond on its last call.${meta.data.ai.fallbackEnabled ? " Results produced meanwhile are labelled as rules-engine or system-generated." : ""}`
        : null;

  return (
    <div className="mk-root">
      <style>{MK_CSS}</style>

      {view.kind === "create" && <CampaignForm nav={nav} />}
      {view.kind === "edit" && <CampaignForm nav={nav} editId={view.id} />}
      {view.kind === "campaign" && (
        <CampaignWorkspace id={view.id} tab={view.tab} onTab={(tab) => setView({ ...view, tab })} nav={nav} />
      )}

      {view.kind === "home" && (
        <>
          <header className="mk-header">
            <div>
              <h1 className="mk-title">Marketing Automation</h1>
              <p className="mk-subtitle">
                Manage campaigns, AI-assisted lead targeting, content generation, approvals and campaign performance.
              </p>
            </div>
            <Btn variant="primary" size="lg" icon={Plus} onClick={nav.create}>
              Create Campaign
            </Btn>
          </header>

          {aiWarning && (
            <div style={{ marginBottom: 14 }}>
              <Callout tone="warn" icon={ShieldAlert} title="AI model unavailable">{aiWarning}</Callout>
            </div>
          )}

          <div style={{ marginBottom: 18 }}>
            <KpiStrip onReview={() => setView({ kind: "home", tab: "approvals" })} />
          </div>

          <div className="mk-filters">
            <div className="mk-search">
              <Search size={14} aria-hidden />
              <input className="mk-input" placeholder="Search campaigns" aria-label="Search campaigns" value={q} onChange={(e) => setQ(e.target.value)} />
            </div>
            <select className="mk-select" aria-label="Date filter" value={dateFilter} onChange={(e) => setDateFilter(e.target.value as DateFilter)}>
              <option value="ANY">Any date</option>
              <option value="RUNNING">Running today</option>
              <option value="UPCOMING">Starting in the next 30 days</option>
              <option value="LAST_90">Created in the last 90 days</option>
            </select>
            <select className="mk-select" aria-label="Campaign status" value={status} onChange={(e) => setStatus(e.target.value)}>
              {statusFilterOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
            <select className="mk-select" aria-label="Campaign type" value={type} onChange={(e) => setType(e.target.value)}>
              <option value="ALL">All types</option>
              {(meta.data?.campaignTypes ?? []).map((t) => <option key={t} value={t}>{campaignTypeLabel(t)}</option>)}
            </select>
            {isFiltered && (
              <Btn variant="ghost" size="sm" onClick={() => { setQ(""); setStatus("ALL"); setType("ALL"); setDateFilter("ANY"); }}>
                Clear filters
              </Btn>
            )}
          </div>

          <Tabs<HomeTab>
            label="Marketing automation sections"
            value={view.tab}
            onChange={(tab) => setView({ kind: "home", tab })}
            tabs={[
              { id: "overview", label: "Overview", icon: LayoutGrid },
              { id: "campaigns", label: "Campaigns", icon: ListChecks, count: filtered.length },
              { id: "approvals", label: "Approvals", icon: ClipboardCheck, count: queue.data?.length ?? 0, warn: true },
              { id: "performance", label: "Performance", icon: BarChart3 },
            ]}
          />

          {view.tab === "overview" && <OverviewTab nav={nav} campaigns={filtered} loading={list.isLoading} />}
          {view.tab === "campaigns" && (
            <CampaignsTab nav={nav} campaigns={filtered} loading={list.isLoading} error={list.error} onRetry={() => list.refetch()} isApprover={isApprover} filtered={isFiltered} />
          )}
          {view.tab === "approvals" && <ApprovalsTab nav={nav} isApprover={isApprover} />}
          {view.tab === "performance" && <PerformanceTab campaigns={filtered} />}
        </>
      )}
    </div>
  );
}
