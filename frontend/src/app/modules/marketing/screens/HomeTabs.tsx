import { useMemo, useState } from "react";
import {
  ArrowDownRight,
  ArrowRight,
  ArrowUpRight,
  ClipboardCheck,
  FileText,
  Megaphone,
  Minus,
  Plus,
  Radio,
  Send,
  Users,
} from "lucide-react";
import type { ApprovalItem, CampaignSummary, Funnel, Range } from "../api/types";
import { useAnalytics, useApprovalQueue, useMarketingDashboard } from "../api/hooks";
import { Badge, Btn, EmptyState, ErrorState, Menu, Panel, Skeleton, StatusBadge, TransparencyTag } from "../components/ui";
import { FunnelChart, PerformanceChart, RangeFilter, RateTiles } from "../components/charts";
import { Nav, useCampaignActions } from "../components/campaignActions";
import { campaignTypeLabel, fmtAgo, fmtDate, fmtDateTime, fmtNum, fmtPct } from "../theme";
import { ActivityList } from "./workspace/ActivityPanel";

/* ================================================================
 * KPI strip
 * ================================================================ */

function Delta({ now, prev, suffix }: { now: number; prev: number; suffix: string }) {
  if (prev === 0 && now === 0) return <span className="mk-delta mk-delta--flat"><Minus size={11} aria-hidden /> no change</span>;
  if (prev === 0) return <span className="mk-delta mk-delta--up"><ArrowUpRight size={11} aria-hidden /> new {suffix}</span>;
  const pct = Math.round((100 * (now - prev)) / prev);
  const cls = pct > 0 ? "up" : pct < 0 ? "down" : "flat";
  const Icon = pct > 0 ? ArrowUpRight : pct < 0 ? ArrowDownRight : Minus;
  return (
    <span className={`mk-delta mk-delta--${cls}`}>
      <Icon size={11} aria-hidden />
      {pct > 0 ? "+" : ""}
      {pct}% {suffix}
    </span>
  );
}

export function KpiStrip({ onReview }: { onReview: () => void }) {
  const { data, isLoading, error, refetch } = useMarketingDashboard();
  if (error) return <ErrorState error={error} onRetry={() => refetch()} />;
  const v = (n?: number) => (isLoading || n == null ? "—" : fmtNum(n));
  return (
    <div className="mk-kpis" aria-label="Summary">
      <div className="mk-kpi">
        <span className="mk-kpi__label">Total campaigns</span>
        <span className="mk-kpi__value">{v(data?.totalCampaigns)}</span>
        <span className="mk-kpi__icon"><Megaphone size={16} aria-hidden /></span>
        <span className="mk-kpi__foot">{data ? `${fmtNum(data.campaignsCreatedLast30d)} created in the last 30 days` : " "}</span>
      </div>
      <div className="mk-kpi">
        <span className="mk-kpi__label">Active campaigns</span>
        <span className="mk-kpi__value">{v(data?.activeCampaigns)}</span>
        <span className="mk-kpi__icon"><Radio size={16} aria-hidden /></span>
        <span className="mk-kpi__foot">{data ? `${fmtNum(data.scheduledCampaigns)} scheduled to start` : " "}</span>
      </div>
      <div className="mk-kpi">
        <span className="mk-kpi__label">Leads reached</span>
        <span className="mk-kpi__value">{v(data?.leadsReached)}</span>
        <span className="mk-kpi__icon"><Users size={16} aria-hidden /></span>
        <span className="mk-kpi__foot">
          {data && <Delta now={data.leadsReachedLast30d} prev={data.leadsReachedPrev30d} suffix="vs prior 30 days" />}
        </span>
      </div>
      <div className={`mk-kpi ${data && data.approvalsWaiting > 0 ? "mk-kpi--alert" : ""}`}>
        <span className="mk-kpi__label">Approvals waiting</span>
        <span className="mk-kpi__value">{v(data?.approvalsWaiting)}</span>
        <span className="mk-kpi__icon"><ClipboardCheck size={16} aria-hidden /></span>
        <span className="mk-kpi__foot">
          {data && data.leadDecisionsWaiting > 0 ? `${fmtNum(data.leadDecisionsWaiting)} lead decisions pending · ` : ""}
          {data && data.approvalsWaiting > 0 ? (
            <button type="button" className="mk-link" style={{ fontSize: 11.5, color: "var(--mk-warn)" }} onClick={onReview}>
              Open queue →
            </button>
          ) : (
            data && "Nothing waiting"
          )}
        </span>
      </div>
    </div>
  );
}

/* ================================================================
 * Overview
 * ================================================================ */

export function OverviewTab({ nav, campaigns, loading }: { nav: Nav; campaigns: CampaignSummary[]; loading: boolean }) {
  const [range, setRange] = useState<Range>({ key: "30d" });
  const analytics = useAnalytics(null, range);
  const dash = useMarketingDashboard();
  const queue = useApprovalQueue();
  const recent = campaigns.slice(0, 6);

  return (
    <div className="mk-grid-main">
      <div className="mk-stack">
        <Panel
          title="Campaign performance"
          subtitle="Counted from email provider and tracking events across all campaigns"
          actions={<RangeFilter value={range} onChange={setRange} />}
        >
          {analytics.error ? (
            <ErrorState error={analytics.error} onRetry={() => analytics.refetch()} />
          ) : analytics.data ? (
            <PerformanceChart series={analytics.data.series} loading={analytics.isFetching} />
          ) : (
            <Skeleton rows={6} height={20} />
          )}
        </Panel>

        <Panel
          title="Recent campaigns"
          actions={
            <Btn variant="ghost" size="sm" onClick={() => nav.home("campaigns")}>
              All campaigns <ArrowRight size={13} aria-hidden />
            </Btn>
          }
          flush
        >
          {loading ? (
            <div style={{ padding: 16 }}><Skeleton rows={4} /></div>
          ) : recent.length === 0 ? (
            <EmptyState
              icon={Megaphone}
              title="No campaigns yet"
              action={<Btn variant="primary" icon={Plus} onClick={nav.create}>Create campaign</Btn>}
            >
              Create an email campaign and the AI will score your leads for it and draft the email, ready for your review.
            </EmptyState>
          ) : (
            <div className="mk-table-wrap">
              <table className="mk-table">
                <thead>
                  <tr>
                    <th>Campaign</th>
                    <th>Status</th>
                    <th className="mk-num">Approved leads</th>
                    <th className="mk-num">Emails sent</th>
                    <th className="mk-num">Open rate (est.)</th>
                  </tr>
                </thead>
                <tbody>
                  {recent.map((c) => (
                    <tr key={c.id}>
                      <td>
                        <button type="button" className="mk-link" onClick={() => nav.openCampaign(c.id)}>{c.name}</button>
                        <div className="mk-sub">{campaignTypeLabel(c.campaignType)}</div>
                      </td>
                      <td><StatusBadge status={c.status} /></td>
                      <td className="mk-num">{fmtNum(c.approvedLeads)}</td>
                      <td className="mk-num">{fmtNum(c.sent)}</td>
                      <td className="mk-num">{c.sent - c.bounced > 0 ? fmtPct((100 * c.opened) / (c.sent - c.bounced)) : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
      </div>

      <div className="mk-stack">
        <Panel
          title="Needs your attention"
          subtitle="Nothing reaches a customer until it's approved here"
          actions={
            queue.data && queue.data.length > 0 ? (
              <Btn variant="ghost" size="sm" onClick={() => nav.home("approvals")}>
                View all
              </Btn>
            ) : undefined
          }
        >
          {queue.isLoading ? (
            <Skeleton rows={3} />
          ) : queue.error ? (
            <ErrorState error={queue.error} onRetry={() => queue.refetch()} />
          ) : !queue.data?.length ? (
            <div className="mk-row mk-sub" style={{ gap: 8 }}>
              <ClipboardCheck size={16} aria-hidden /> All caught up. No approvals are waiting.
            </div>
          ) : (
            <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
              {queue.data.slice(0, 5).map((a) => (
                <li key={a.id} style={{ display: "flex", gap: 10, alignItems: "center", padding: "8px 0", borderBottom: "1px solid #EEEFF1" }}>
                  <ApprovalKindIcon kind={a.kind} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="mk-trunc" style={{ fontWeight: 600 }}>{a.campaignName}</div>
                    <div className="mk-sub">{approvalKindText(a)} · waiting {fmtAgo(a.submittedAt).replace(" ago", "")}</div>
                  </div>
                  <Btn size="sm" onClick={() => nav.openCampaign(a.campaignId, reviewTab(a.kind))}>Review</Btn>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel title="Funnel" subtitle={analytics.data ? `${fmtDate(analytics.data.from)} – ${fmtDate(analytics.data.to)}` : undefined}>
          {analytics.data ? <FunnelChart funnel={analytics.data.funnel} /> : <Skeleton rows={5} height={18} />}
        </Panel>

        <Panel title="Recent activity" subtitle="AI results, decisions and system events">
          {dash.isLoading ? (
            <Skeleton rows={4} />
          ) : dash.data?.recentActivity.length ? (
            <ActivityList items={dash.data.recentActivity} showCampaign onOpen={(id) => nav.openCampaign(id, "activity")} compact />
          ) : (
            <div className="mk-sub">No activity yet.</div>
          )}
        </Panel>
      </div>
    </div>
  );
}

/* ================================================================
 * Campaigns table
 * ================================================================ */

export function CampaignsTab({
  nav,
  campaigns,
  loading,
  error,
  onRetry,
  isApprover,
  filtered,
}: {
  nav: Nav;
  campaigns: CampaignSummary[];
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  isApprover: boolean;
  filtered: boolean;
}) {
  const actions = useCampaignActions(nav, isApprover);
  const [sort, setSort] = useState<{ key: keyof CampaignSummary; dir: 1 | -1 }>({ key: "createdAt", dir: -1 });
  const rows = useMemo(() => {
    const copy = [...campaigns];
    copy.sort((a, b) => {
      const x = a[sort.key] ?? "";
      const y = b[sort.key] ?? "";
      return (x > y ? 1 : x < y ? -1 : 0) * sort.dir;
    });
    return copy;
  }, [campaigns, sort]);

  const th = (key: keyof CampaignSummary, label: string, num?: boolean) => (
    <th className={num ? "mk-num" : undefined} aria-sort={sort.key === key ? (sort.dir === 1 ? "ascending" : "descending") : "none"}>
      <button
        type="button"
        className="mk-link"
        style={{ fontWeight: 600, fontSize: 11.5, color: "inherit" }}
        onClick={() => setSort((s) => ({ key, dir: s.key === key ? ((-s.dir) as 1 | -1) : -1 }))}
      >
        {label}
        {sort.key === key ? (sort.dir === 1 ? " ↑" : " ↓") : ""}
      </button>
    </th>
  );

  if (error) return <ErrorState error={error} onRetry={onRetry} />;
  return (
    <Panel flush>
      {loading ? (
        <div style={{ padding: 16 }}><Skeleton rows={6} /></div>
      ) : rows.length === 0 ? (
        filtered ? (
          <EmptyState icon={FileText} title="No campaigns match these filters">Clear the search or filters to see every campaign.</EmptyState>
        ) : (
          <EmptyState icon={Megaphone} title="No campaigns yet" action={<Btn variant="primary" icon={Plus} onClick={nav.create}>Create campaign</Btn>}>
            Campaigns you create appear here with their approval progress and results.
          </EmptyState>
        )
      ) : (
        <div className="mk-table-wrap" style={{ borderTop: 0 }}>
          <table className="mk-table">
            <caption className="mk-sr">Campaigns</caption>
            <thead>
              <tr>
                {th("name", "Campaign name")}
                {th("campaignType", "Type")}
                {th("status", "Status")}
                {th("targetLeads", "Target leads", true)}
                {th("approvedLeads", "Approved", true)}
                {th("sent", "Emails sent", true)}
                {th("delivered", "Delivered", true)}
                {th("opened", "Opened", true)}
                {th("clicked", "Clicked", true)}
                {th("converted", "Converted", true)}
                {th("startDate", "Start date")}
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <td style={{ minWidth: 200 }}>
                    <button type="button" className="mk-link" onClick={() => nav.openCampaign(c.id)}>{c.name}</button>
                    <div className="mk-sub">Email{c.bounced || c.unsubscribed ? ` · ${fmtNum(c.bounced)} bounced · ${fmtNum(c.unsubscribed)} unsubscribed` : ""}</div>
                  </td>
                  <td className="mk-sub" style={{ whiteSpace: "nowrap" }}>{campaignTypeLabel(c.campaignType)}</td>
                  <td>
                    <StatusBadge status={c.status} />
                    {(c.leadPipelineStatus === "FAILED" || c.contentPipelineStatus === "FAILED") && (
                      <div style={{ marginTop: 4 }}><Badge tone="bad" dot={false}>AI step failed</Badge></div>
                    )}
                  </td>
                  <td className="mk-num">{fmtNum(c.targetLeads)}</td>
                  <td className="mk-num">{fmtNum(c.approvedLeads)}</td>
                  <td className="mk-num">{fmtNum(c.sent)}</td>
                  <td className="mk-num">{fmtNum(c.delivered)}</td>
                  <td className="mk-num">{fmtNum(c.opened)}</td>
                  <td className="mk-num">{fmtNum(c.clicked)}</td>
                  <td className="mk-num">{fmtNum(c.converted)}</td>
                  <td className="mk-sub" style={{ whiteSpace: "nowrap" }}>{fmtDate(c.startDate)}</td>
                  <td style={{ textAlign: "right" }}>
                    <Menu label={`Actions for ${c.name}`} items={actions.menuFor(c)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {actions.dialog}
    </Panel>
  );
}

/* ================================================================
 * Approval queue
 * ================================================================ */

function ApprovalKindIcon({ kind }: { kind: ApprovalItem["kind"] }) {
  const Icon = kind === "LEADS" ? Users : kind === "CONTENT" ? FileText : Send;
  return (
    <span className="mk-actor" style={{ background: "#FAEEDA", color: "#854F0B", flexShrink: 0 }} aria-hidden>
      <Icon size={14} />
    </span>
  );
}

const approvalKindText = (a: ApprovalItem) =>
  a.kind === "LEADS"
    ? `Lead approval${a.pendingCount ? ` · ${fmtNum(a.pendingCount)} eligible leads to review` : ""}`
    : a.kind === "CONTENT"
      ? "Content approval"
      : "Campaign approval · ready to schedule or launch";

const reviewTab = (kind: ApprovalItem["kind"]) => (kind === "LEADS" ? "leads" : kind === "CONTENT" ? "content" : "launch");

export function ApprovalsTab({ nav, isApprover }: { nav: Nav; isApprover: boolean }) {
  const { data, isLoading, error, refetch } = useApprovalQueue();
  const [kind, setKind] = useState<"ALL" | ApprovalItem["kind"]>("ALL");
  const items = (data ?? []).filter((a) => kind === "ALL" || a.kind === kind);
  const count = (k: ApprovalItem["kind"]) => (data ?? []).filter((a) => a.kind === k).length;

  return (
    <div className="mk-stack">
      {!isApprover && (
        <div className="mk-callout mk-callout--info">
          You can view the queue. Approving leads, content and launches needs the Marketing Head (marketing approver) permission.
        </div>
      )}
      <div className="mk-row">
        {(
          [
            ["ALL", "All", data?.length ?? 0],
            ["LEADS", "Lead approvals", count("LEADS")],
            ["CONTENT", "Content approvals", count("CONTENT")],
            ["CAMPAIGN", "Campaign approvals", count("CAMPAIGN")],
          ] as const
        ).map(([k, label, n]) => (
          <button key={k} type="button" className="mk-chip" aria-pressed={kind === k} onClick={() => setKind(k)}>
            {label} <span className={`mk-count ${n > 0 && k !== "ALL" ? "mk-count--warn" : ""}`}>{n}</span>
          </button>
        ))}
      </div>
      <Panel flush>
        {error ? (
          <div style={{ padding: 16 }}><ErrorState error={error} onRetry={() => refetch()} /></div>
        ) : isLoading ? (
          <div style={{ padding: 16 }}><Skeleton rows={4} /></div>
        ) : items.length === 0 ? (
          <EmptyState icon={ClipboardCheck} title="Nothing waiting">
            When the AI finishes evaluating leads or drafting content for a campaign, the review lands here.
          </EmptyState>
        ) : (
          <div className="mk-table-wrap" style={{ borderTop: 0 }}>
            <table className="mk-table">
              <caption className="mk-sr">Pending approvals</caption>
              <thead>
                <tr>
                  <th>Campaign</th>
                  <th>Type</th>
                  <th>Submitted by</th>
                  <th>Date</th>
                  <th>Pending since</th>
                  <th>Status</th>
                  <th aria-label="Action" />
                </tr>
              </thead>
              <tbody>
                {items.map((a) => (
                  <tr key={a.id}>
                    <td>
                      <button type="button" className="mk-link" onClick={() => nav.openCampaign(a.campaignId)}>{a.campaignName}</button>
                      <div className="mk-sub">{campaignTypeLabel(a.campaignType)}</div>
                    </td>
                    <td>
                      <div className="mk-row" style={{ gap: 8, flexWrap: "nowrap" }}>
                        <ApprovalKindIcon kind={a.kind} />
                        <span>{approvalKindText(a)}</span>
                      </div>
                    </td>
                    <td className="mk-sub">{a.submittedBy ?? "—"}</td>
                    <td className="mk-sub" style={{ whiteSpace: "nowrap" }}>{fmtDateTime(a.submittedAt)}</td>
                    <td className="mk-sub" style={{ whiteSpace: "nowrap" }}>{fmtAgo(a.submittedAt)}</td>
                    <td><Badge tone="warn">Pending</Badge></td>
                    <td style={{ textAlign: "right" }}>
                      <Btn size="sm" variant="primary" onClick={() => nav.openCampaign(a.campaignId, reviewTab(a.kind))}>Review</Btn>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
      <div className="mk-row mk-sub" style={{ gap: 6 }}>
        <TransparencyTag kind="AI_RECOMMENDED" /> AI output in this queue is a recommendation only; approving it is a human decision recorded in each campaign's audit trail.
      </div>
    </div>
  );
}

/* ================================================================
 * Performance
 * ================================================================ */

export function PerformanceTab({ campaigns }: { campaigns: CampaignSummary[] }) {
  const [range, setRange] = useState<Range>({ key: "30d" });
  const [campaignId, setCampaignId] = useState<string>("");
  const analytics = useAnalytics(campaignId || null, range);
  const launched = campaigns.filter((c) => ["ACTIVE", "PAUSED", "COMPLETED", "SCHEDULED", "CANCELLED"].includes(c.status));

  return (
    <div className="mk-stack">
      <div className="mk-filters" style={{ marginBottom: 0 }}>
        <RangeFilter value={range} onChange={setRange} />
        <select className="mk-select" aria-label="Campaign" value={campaignId} onChange={(e) => setCampaignId(e.target.value)}>
          <option value="">All campaigns</option>
          {launched.map((c) => (
            <option key={c.id} value={c.id}>{c.name}</option>
          ))}
        </select>
      </div>
      {analytics.error ? (
        <ErrorState error={analytics.error} onRetry={() => analytics.refetch()} />
      ) : !analytics.data ? (
        <Skeleton rows={8} height={20} />
      ) : (
        <>
          <RateTiles funnel={analytics.data.funnel} />
          <div className="mk-grid-main">
            <Panel title="Sent, delivered, opened, clicked, converted" subtitle={`${fmtDate(analytics.data.from)} – ${fmtDate(analytics.data.to)}`}>
              <PerformanceChart series={analytics.data.series} loading={analytics.isFetching} height={300} />
            </Panel>
            <Panel title="Funnel" subtitle="Share of the previous stage at each step">
              <FunnelChart funnel={analytics.data.funnel} />
              <FunnelNotes funnel={analytics.data.funnel} />
            </Panel>
          </div>
          <MetricDefinitions />
        </>
      )}
    </div>
  );
}

/* ================================================================
 * Shared metric notes
 * ================================================================ */

export function FunnelNotes({ funnel }: { funnel: Funnel }) {
  const parts = [
    funnel.bounced > 0 && `${fmtNum(funnel.bounced)} bounced`,
    funnel.unsubscribed > 0 && `${fmtNum(funnel.unsubscribed)} unsubscribed`,
    funnel.failed > 0 && `${fmtNum(funnel.failed)} not accepted by the provider`,
    funnel.suppressed > 0 && `${fmtNum(funnel.suppressed)} not sent (unsubscribed, bounced earlier, or no/duplicate address)`,
  ].filter(Boolean);
  if (parts.length === 0) return null;
  return <p className="mk-sub" style={{ marginTop: 12 }}>{parts.join(" · ")}.</p>;
}

export function MetricDefinitions() {
  return (
    <details className="mk-sub">
      <summary style={{ cursor: "pointer" }}>How these numbers are counted</summary>
      <ul style={{ margin: "6px 0 0", paddingLeft: 18 }}>
        <li><strong>Emails sent</strong>: accepted by the email provider. Acceptance is not delivery.</li>
        <li><strong>Delivered</strong>: confirmed by a delivery event from the provider. If the provider doesn&apos;t report deliveries, the delivery rate shows “Not reported”.</li>
        <li><strong>Opened</strong> is an estimate: it counts loads of a tracking image, which privacy features and security scanners can trigger or block.</li>
        <li><strong>Clicked</strong>: the call-to-action link was followed (unique per recipient). <strong>Converted</strong>: the lead became a deal in the CRM after the email.</li>
        <li>Open, click-through, conversion and unsubscribe rates divide by emails sent minus bounces. Bounce rate divides by emails sent.</li>
      </ul>
    </details>
  );
}
