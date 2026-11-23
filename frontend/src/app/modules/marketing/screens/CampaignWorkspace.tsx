import { useMemo } from "react";
import { toast } from "sonner";
import {
  Activity,
  ArrowLeft,
  BarChart3,
  Copy,
  FileText,
  LayoutDashboard,
  PencilLine,
  RefreshCw,
  Rocket,
  Sparkles,
  Split,
  Users,
} from "lucide-react";
import type { CampaignDetail, CampaignLead } from "../api/types";
import { useCampaign, useCampaignCommand, useCampaignLeads, useGenerateContent } from "../api/hooks";
import { Badge, Btn, Callout, ErrorState, KeyValues, Menu, Panel, Skeleton, StatusBadge, Tabs, TransparencyTag } from "../components/ui";
import { Nav, useCampaignActions } from "../components/campaignActions";
import { campaignTypeLabel, fmtDate, fmtDateTime, fmtInr, fmtNum } from "../theme";
import { PipelineView } from "./workspace/PipelineView";
import { LeadsPanel } from "./workspace/LeadsPanel";
import { ContentPanel } from "./workspace/ContentPanel";
import { AbTestPanel } from "./workspace/AbTestPanel";
import { LaunchPanel } from "./workspace/LaunchPanel";
import { CampaignAnalyticsPanel } from "./workspace/CampaignAnalyticsPanel";
import { ActivityPanel } from "./workspace/ActivityPanel";

export type WorkspaceTab = "overview" | "leads" | "content" | "abtest" | "launch" | "analytics" | "activity";

const errMsg = (e: unknown) => (e instanceof Error ? e.message : undefined);

export function CampaignWorkspace({ id, tab, onTab, nav }: { id: string; tab: WorkspaceTab; onTab: (t: WorkspaceTab) => void; nav: Nav }) {
  const { data: c, isLoading, error, refetch } = useCampaign(id);
  const actions = useCampaignActions(nav, !!c?.viewerIsApprover);
  const command = useCampaignCommand();
  const generate = useGenerateContent();

  if (isLoading) return <Skeleton rows={12} height={20} />;
  if (error || !c) return <ErrorState error={error ?? new Error("Campaign not found")} onRetry={() => refetch()} />;

  const editable = ["DRAFT", "PENDING_LEAD_APPROVAL", "PENDING_CONTENT_APPROVAL", "READY_TO_LAUNCH"].includes(c.status);
  const running = c.pipeline.leadStatus === "RUNNING" || c.pipeline.contentStatus === "RUNNING";
  const contentPending = c.readiness.requiredContent.length - c.readiness.approvedContent.length;

  const rerunLeads = () =>
    command.mutate(
      { id, command: "leads/analyze" },
      { onSuccess: () => toast.success("Lead evaluation restarted"), onError: (e) => toast.error("Couldn't restart lead evaluation", { description: errMsg(e) }) }
    );
  const rerunContent = () =>
    generate.mutate(
      { id },
      { onSuccess: () => toast.success("Content generation restarted"), onError: (e) => toast.error("Couldn't regenerate content", { description: errMsg(e) }) }
    );
  const submit = () =>
    command.mutate(
      { id, command: "submit" },
      { onSuccess: () => toast.success("AI processing started"), onError: (e) => toast.error("Couldn't start AI processing", { description: errMsg(e) }) }
    );

  return (
    <div>
      <div className="mk-header">
        <div style={{ minWidth: 0 }}>
          <div className="mk-eyebrow">
            <button type="button" className="mk-link" style={{ fontSize: 11, color: "inherit", fontWeight: 600 }} onClick={() => nav.home("campaigns")}>
              <ArrowLeft size={12} aria-hidden style={{ verticalAlign: -2 }} /> Campaigns
            </button>
          </div>
          <div className="mk-row" style={{ gap: 10 }}>
            <h2 className="mk-title">{c.name}</h2>
            <StatusBadge status={c.status} />
          </div>
          <p className="mk-subtitle">
            {campaignTypeLabel(c.campaignType)} · {fmtDate(c.startDate)} – {fmtDate(c.endDate)} · Email campaign
            {c.createdBy && ` · created by ${c.createdBy}`}
          </p>
        </div>
        <div className="mk-row">
          {c.status === "DRAFT" && (
            <Btn variant="primary" icon={Sparkles} busy={command.isPending} onClick={submit}>Start AI processing</Btn>
          )}
          {editable && <Btn icon={PencilLine} disabled={running} onClick={() => nav.editCampaign(id)}>Edit</Btn>}
          <Btn icon={Copy} disabled={actions.busy} onClick={() => actions.duplicate(id, c.name)}>Duplicate</Btn>
          <Menu label="More campaign actions" items={actions.menuFor(c).filter((m) => m === "divider" || !["View", "Edit", "Duplicate"].includes(m.label))} />
        </div>
      </div>

      {!c.viewerIsApprover && c.status !== "DRAFT" && (
        <div style={{ marginBottom: 12 }}>
          <Callout tone="info">You can review and edit this campaign. Approvals, launch and lifecycle changes need the Marketing Head (marketing approver) permission.</Callout>
        </div>
      )}
      {c.pipeline.leadStatus === "FAILED" && (
        <div style={{ marginBottom: 12 }}>
          <Callout tone="bad" title="Lead evaluation failed">
            {c.pipeline.leadError}
            <div style={{ marginTop: 8 }}>
              <Btn size="sm" icon={RefreshCw} busy={command.isPending} onClick={rerunLeads}>Run lead evaluation again</Btn>
            </div>
          </Callout>
        </div>
      )}
      {c.pipeline.contentStatus === "FAILED" && (
        <div style={{ marginBottom: 12 }}>
          <Callout tone="bad" title="Content generation failed">
            {c.pipeline.contentError}
            <div style={{ marginTop: 8 }}>
              <Btn size="sm" icon={RefreshCw} busy={generate.isPending} onClick={rerunContent}>Generate content again</Btn>
            </div>
          </Callout>
        </div>
      )}

      <Tabs<WorkspaceTab>
        label="Campaign sections"
        value={tab}
        onChange={onTab}
        tabs={[
          { id: "overview", label: "Overview", icon: LayoutDashboard },
          { id: "leads", label: "Leads", icon: Users, count: c.readiness.eligiblePending, warn: true },
          { id: "content", label: "Content", icon: FileText, count: c.pipeline.contentStatus === "COMPLETED" ? contentPending : 0, warn: true },
          { id: "abtest", label: "A/B testing", icon: Split },
          { id: "launch", label: "Strategy & launch", icon: Rocket },
          { id: "analytics", label: "Analytics", icon: BarChart3 },
          { id: "activity", label: "Activity", icon: Activity },
        ]}
      />

      {tab === "overview" && <OverviewPanel c={c} onTab={onTab} />}
      {tab === "leads" && <LeadsPanel c={c} onRerun={rerunLeads} rerunBusy={command.isPending} />}
      {tab === "content" && <ContentPanel c={c} />}
      {tab === "abtest" && <AbTestPanel c={c} />}
      {tab === "launch" && <LaunchPanel c={c} onTab={onTab} />}
      {tab === "analytics" && <CampaignAnalyticsPanel c={c} />}
      {tab === "activity" && <ActivityPanel campaignId={id} approvals={c.approvals} />}
      {actions.dialog}
    </div>
  );
}

/* ================================================================
 * Overview tab
 * ================================================================ */

function OverviewPanel({ c, onTab }: { c: CampaignDetail; onTab: (t: WorkspaceTab) => void }) {
  const leads = useCampaignLeads(c.id, c.pipeline.leadStatus === "RUNNING");
  const r = c.readiness;
  const inReview = ["PENDING_LEAD_APPROVAL", "PENDING_CONTENT_APPROVAL", "READY_TO_LAUNCH"].includes(c.status);

  return (
    <div className="mk-stack">
      <Panel title="Workflow" subtitle="AI results only move forward when you approve them">
        <PipelineView c={c} />
      </Panel>

      {inReview && (
        r.canLaunch ? (
          <Callout tone="ok" title="Leads and content are approved">
            Review the strategy and launch or schedule the campaign.
            <div style={{ marginTop: 8 }}><Btn size="sm" variant="primary" icon={Rocket} onClick={() => onTab("launch")}>Go to strategy & launch</Btn></div>
          </Callout>
        ) : (
          <Callout tone="warn" title="Before this campaign can launch">
            <ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>
              {r.blockers.map((b) => <li key={b}>{b}</li>)}
            </ul>
            <div className="mk-row" style={{ marginTop: 8 }}>
              {!r.leadsReady && c.pipeline.leadStatus === "COMPLETED" && <Btn size="sm" onClick={() => onTab("leads")}>Review leads</Btn>}
              {!r.contentReady && c.pipeline.contentStatus === "COMPLETED" && <Btn size="sm" onClick={() => onTab("content")}>Review content</Btn>}
            </div>
          </Callout>
        )
      )}

      <div className="mk-grid-main">
        <Panel title="Campaign overview">
          <KeyValues
            rows={[
              ["Objective", c.objective],
              ["Description", c.description],
              ["Type", campaignTypeLabel(c.campaignType)],
              ["Dates", `${fmtDate(c.startDate)} – ${fmtDate(c.endDate)}`],
              ["Product", c.productName],
              ["Offer", [c.offer, c.discount].filter(Boolean).join(" · ")],
              ["Call-to-action", c.contentConfig.cta || c.callToAction],
              ["Channel", "Email"],
              ["Sender", [c.contentConfig.senderName, c.contentConfig.senderEmail].filter(Boolean).join(" · ")],
              ["Landing page", c.landingPageUrl],
              ["Tone / language", [c.contentConfig.tone, c.contentConfig.language].filter(Boolean).join(" · ")],
            ]}
          />
          <hr className="mk-divider" />
          <h4 className="mk-h">Target audience</h4>
          <KeyValues
            rows={[
              ["Industries", c.audience.industries.join(", ")],
              ["Company size", c.audience.companySize],
              ["Locations", c.audience.locations.join(", ")],
              ["Customer types", c.audience.customerTypes.join(", ")],
              ["Customer status", c.audience.customerStatus === "ANY" ? "Existing customers or new leads" : c.audience.customerStatus === "EXISTING_CUSTOMERS" ? "Existing customers" : "New leads"],
              ["Deal value", c.audience.minDealValue != null || c.audience.maxDealValue != null ? `${fmtInr(c.audience.minDealValue)} – ${fmtInr(c.audience.maxDealValue)}` : null],
              ["Purchase timeline", c.audience.purchaseTimeline],
              ["Segment", c.audience.customerSegment],
            ]}
          />
          <hr className="mk-divider" />
          <h4 className="mk-h">Eligibility rules · lead must meet {c.ruleMatch === "ALL" ? "all" : "any"}</h4>
          {c.criteria.length === 0 ? (
            <div className="mk-sub">No hard rules. Eligibility is decided by the AI from the audience description.</div>
          ) : (
            <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12.5 }}>
              {c.criteria.map((k, i) => (
                <li key={i}>
                  <code style={{ fontSize: 12 }}>{k.field.toLowerCase().replace(/_/g, " ")}</code> {k.operator.toLowerCase().replace("_", " ")} <strong>{k.value}</strong>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <div className="mk-stack">
          <Panel title="AI evaluation summary" actions={<TransparencyTag kind="AI_RECOMMENDED" />}>
            {leads.isLoading ? <Skeleton rows={4} /> : <EvaluationSummary leads={leads.data ?? []} c={c} />}
          </Panel>
          <Panel title="Key dates">
            <KeyValues
              rows={[
                ["Created", fmtDateTime(c.createdAt)],
                ["Last updated", fmtDateTime(c.updatedAt)],
                ["Scheduled for", c.scheduledAt ? fmtDateTime(c.scheduledAt) : null],
                ["Launched", c.launchedAt ? fmtDateTime(c.launchedAt) : null],
                ["Completed", c.completedAt ? fmtDateTime(c.completedAt) : null],
              ]}
            />
          </Panel>
        </div>
      </div>
    </div>
  );
}

function EvaluationSummary({ leads, c }: { leads: CampaignLead[]; c: CampaignDetail }) {
  const stats = useMemo(() => {
    const scored = leads.filter((l) => l.aiScore != null);
    const avg = scored.length ? Math.round(scored.reduce((s, l) => s + (l.aiScore ?? 0), 0) / scored.length) : null;
    const bands = [
      { label: "80–100", n: scored.filter((l) => l.aiScore! >= 80).length },
      { label: "60–79", n: scored.filter((l) => l.aiScore! >= 60 && l.aiScore! < 80).length },
      { label: "40–59", n: scored.filter((l) => l.aiScore! >= 40 && l.aiScore! < 60).length },
      { label: "0–39", n: scored.filter((l) => l.aiScore! < 40).length },
    ];
    return {
      avg,
      bands,
      byModel: leads.filter((l) => l.evaluationSource === "AI_MODEL").length,
      byRules: leads.filter((l) => l.evaluationSource === "RULES_ENGINE").length,
      guarded: leads.filter((l) => l.ruleGuardApplied).length,
      overridden: leads.filter((l) => l.overrideEligibility).length,
      model: leads.find((l) => l.modelName)?.modelName,
    };
  }, [leads]);

  if (leads.length === 0) {
    return <div className="mk-sub">{c.pipeline.leadStatus === "RUNNING" ? "Scoring in progress…" : "No leads have been evaluated yet."}</div>;
  }
  const max = Math.max(1, ...stats.bands.map((b) => b.n));
  return (
    <div className="mk-stack" style={{ gap: 12 }}>
      <KeyValues
        rows={[
          ["Leads evaluated", fmtNum(leads.length)],
          ["AI eligible", fmtNum(c.readiness.eligibleLeads)],
          ["Human approved", fmtNum(c.readiness.approvedLeads)],
          ["Average score", stats.avg == null ? null : `${stats.avg} / 100`],
          ["Scored by", `${fmtNum(stats.byModel)} AI model${stats.byRules ? ` · ${fmtNum(stats.byRules)} rules engine` : ""}`],
          ["Model", stats.model],
        ]}
      />
      <div>
        <h4 className="mk-h">Score distribution</h4>
        <div className="mk-stack" style={{ gap: 6 }}>
          {stats.bands.map((b) => (
            <div key={b.label} className="mk-row" style={{ flexWrap: "nowrap", gap: 8 }} title={`${b.label}: ${b.n} leads`}>
              <span className="mk-sub" style={{ width: 48 }}>{b.label}</span>
              <div style={{ flex: 1, height: 10 }}>
                <div style={{ width: `${(100 * b.n) / max}%`, height: "100%", minWidth: b.n ? 2 : 0, background: "#2a78d6", borderRadius: "0 4px 4px 0" }} />
              </div>
              <span style={{ width: 36, textAlign: "right", fontWeight: 600 }}>{fmtNum(b.n)}</span>
            </div>
          ))}
        </div>
      </div>
      {(stats.guarded > 0 || stats.overridden > 0 || stats.byRules > 0) && (
        <div className="mk-row" style={{ gap: 6 }}>
          {stats.guarded > 0 && <Badge tone="warn" dot={false}>{stats.guarded} overruled by rule check</Badge>}
          {stats.overridden > 0 && <Badge tone="info" dot={false}>{stats.overridden} human overrides</Badge>}
          {stats.byRules > 0 && <Badge tone="neutral" dot={false}>{stats.byRules} scored without AI</Badge>}
        </div>
      )}
    </div>
  );
}
