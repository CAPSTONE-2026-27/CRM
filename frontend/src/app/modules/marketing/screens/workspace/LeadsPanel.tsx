import { useMemo, useState } from "react";
import { toast } from "sonner";
import {
  AlertTriangle,
  Ban,
  Check,
  CircleHelp,
  Eye,
  MinusCircle,
  PencilLine,
  RefreshCw,
  RotateCcw,
  Search,
  ShieldAlert,
  ThumbsDown,
  ThumbsUp,
  Users,
  X,
} from "lucide-react";
import type { CampaignDetail, CampaignLead, Eligibility, LeadAction } from "../../api/types";
import { useBulkLeadDecision, useCampaignLeads, useLeadDecision, useLeadOverride } from "../../api/hooks";
import {
  Badge,
  Btn,
  Callout,
  ConfirmDialog,
  Drawer,
  EmptyState,
  ErrorState,
  KeyValues,
  Menu,
  Panel,
  ScoreMeter,
  Segmented,
  Skeleton,
  TransparencyTag,
} from "../../components/ui";
import { fmtDateTime, fmtInr, fmtNum } from "../../theme";

type Filter = "REVIEW" | "ELIGIBLE" | "NOT_ELIGIBLE" | "APPROVED" | "REJECTED" | "ALL";

const errMsg = (e: unknown) => (e instanceof Error ? e.message : undefined);

function EligibilityBadge({ l }: { l: CampaignLead }) {
  const e = l.effectiveEligibility;
  if (!e) return <Badge tone="neutral">Not evaluated</Badge>;
  return (
    <span className="mk-row" style={{ gap: 4, flexWrap: "nowrap" }}>
      <Badge tone={e === "ELIGIBLE" ? "ok" : "neutral"}>{e === "ELIGIBLE" ? "Eligible" : "Not eligible"}</Badge>
      {l.overrideEligibility && <span className="mk-tag mk-tag--edited" title={`AI said ${l.aiEligibility === "ELIGIBLE" ? "eligible" : "not eligible"}; overridden by a person`}>Overridden</span>}
    </span>
  );
}

function ApprovalBadge({ l }: { l: CampaignLead }) {
  switch (l.approvalStatus) {
    case "APPROVED":
      return <Badge tone="ok">Approved</Badge>;
    case "REJECTED":
      return <Badge tone="bad">Rejected</Badge>;
    case "REMOVED":
      return <Badge tone="neutral">Removed</Badge>;
    default:
      return l.effectiveEligibility === "ELIGIBLE" ? <Badge tone="warn">Pending review</Badge> : <Badge tone="neutral" dot={false}>Not included</Badge>;
  }
}

export function LeadsPanel({ c, onRerun, rerunBusy }: { c: CampaignDetail; onRerun: () => void; rerunBusy: boolean }) {
  const running = c.pipeline.leadStatus === "RUNNING";
  const { data, isLoading, error, refetch } = useCampaignLeads(c.id, running);
  const decide = useLeadDecision();
  const bulk = useBulkLeadDecision();
  const [filter, setFilter] = useState<Filter>("REVIEW");
  const [q, setQ] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [open, setOpen] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<null | { action: LeadAction; ids: string[]; label: string }>(null);
  const [rerunAsk, setRerunAsk] = useState(false);

  const locked = !["PENDING_LEAD_APPROVAL", "PENDING_CONTENT_APPROVAL", "READY_TO_LAUNCH", "AI_PROCESSING"].includes(c.status) || running;
  const canDecide = c.viewerIsApprover && !locked;
  const leads = data ?? [];

  const counts = useMemo(
    () => ({
      REVIEW: leads.filter((l) => l.approvalStatus === "PENDING" && l.effectiveEligibility === "ELIGIBLE").length,
      ELIGIBLE: leads.filter((l) => l.effectiveEligibility === "ELIGIBLE").length,
      NOT_ELIGIBLE: leads.filter((l) => l.effectiveEligibility === "NOT_ELIGIBLE").length,
      APPROVED: leads.filter((l) => l.approvalStatus === "APPROVED").length,
      REJECTED: leads.filter((l) => l.approvalStatus === "REJECTED" || l.approvalStatus === "REMOVED").length,
      ALL: leads.length,
    }),
    [leads]
  );

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return leads.filter((l) => {
      const f =
        filter === "ALL" ||
        (filter === "REVIEW" && l.approvalStatus === "PENDING" && l.effectiveEligibility === "ELIGIBLE") ||
        (filter === "ELIGIBLE" && l.effectiveEligibility === "ELIGIBLE") ||
        (filter === "NOT_ELIGIBLE" && l.effectiveEligibility === "NOT_ELIGIBLE") ||
        (filter === "APPROVED" && l.approvalStatus === "APPROVED") ||
        (filter === "REJECTED" && (l.approvalStatus === "REJECTED" || l.approvalStatus === "REMOVED"));
      return f && (!needle || [l.leadName, l.company, l.industry].some((v) => v?.toLowerCase().includes(needle)));
    });
  }, [leads, filter, q]);

  const allSelected = rows.length > 0 && rows.every((r) => selected.has(r.id));
  const toggleAll = () => setSelected(allSelected ? new Set() : new Set(rows.map((r) => r.id)));
  const toggle = (id: string) =>
    setSelected((s) => {
      const n = new Set(s);
      n.has(id) ? n.delete(id) : n.add(id);
      return n;
    });

  const single = (l: CampaignLead, action: LeadAction, note?: string) =>
    decide.mutate(
      { id: c.id, leadRowId: l.id, action, note },
      {
        onSuccess: () => toast.success(`${l.leadName}: ${action === "APPROVE" ? "approved" : action === "REJECT" ? "rejected" : action === "REMOVE" ? "removed from campaign" : "back to pending"}`),
        onError: (e) => toast.error("Decision not saved", { description: errMsg(e) }),
      }
    );

  const runBulk = (note: string) => {
    if (!confirm) return;
    bulk.mutate(
      { id: c.id, ids: confirm.ids, action: confirm.action, note: note || undefined },
      {
        onSuccess: (r) => {
          toast.success(`${confirm.label}: ${fmtNum(r.updated)} leads`);
          setSelected(new Set());
          setConfirm(null);
        },
        onError: (e) => toast.error("Bulk decision not saved", { description: errMsg(e) }),
      }
    );
  };

  const current = leads.find((l) => l.id === open) ?? null;

  if (error) return <ErrorState error={error} onRetry={() => refetch()} />;

  return (
    <div className="mk-stack">
      <Callout tone="ai" title="AI recommendations, your decision">
        Each lead is scored individually against this campaign. Eligibility is a recommendation: no lead is contacted unless you approve it.
        {c.readiness.eligiblePending > 0 && ` ${fmtNum(c.readiness.eligiblePending)} eligible leads still need a decision before the campaign can proceed.`}
      </Callout>

      {running && (
        <Callout tone="info" title={`Scoring leads… ${fmtNum(c.pipeline.leadDone)} of ${fmtNum(c.pipeline.leadTotal)}`}>
          Results appear below as each lead is evaluated. Decisions open once scoring finishes.
        </Callout>
      )}

      <div className="mk-filters" style={{ marginBottom: 0 }}>
        <Segmented<Filter>
          label="Lead filter"
          value={filter}
          onChange={(f) => {
            setFilter(f);
            setSelected(new Set());
          }}
          options={[
            { value: "REVIEW", label: `To review (${counts.REVIEW})` },
            { value: "ELIGIBLE", label: `Eligible (${counts.ELIGIBLE})` },
            { value: "NOT_ELIGIBLE", label: `Not eligible (${counts.NOT_ELIGIBLE})` },
            { value: "APPROVED", label: `Approved (${counts.APPROVED})` },
            { value: "REJECTED", label: `Rejected / removed (${counts.REJECTED})` },
            { value: "ALL", label: `All (${counts.ALL})` },
          ]}
        />
        <div className="mk-search">
          <Search size={14} aria-hidden />
          <input className="mk-input" placeholder="Search lead, company, industry" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search leads" />
        </div>
        <span className="mk-spacer" />
        <Btn icon={RefreshCw} disabled={locked} busy={rerunBusy} onClick={() => setRerunAsk(true)}>
          Re-run AI evaluation
        </Btn>
      </div>

      {selected.size > 0 && (
        <div className="mk-callout mk-callout--info" style={{ alignItems: "center" }}>
          <strong>{selected.size} selected</strong>
          <span className="mk-spacer" />
          <Btn size="sm" variant="ok" icon={ThumbsUp} disabled={!canDecide} onClick={() => setConfirm({ action: "APPROVE", ids: [...selected], label: "Approved" })}>Approve</Btn>
          <Btn size="sm" variant="danger" icon={ThumbsDown} disabled={!canDecide} onClick={() => setConfirm({ action: "REJECT", ids: [...selected], label: "Rejected" })}>Reject</Btn>
          <Btn size="sm" icon={MinusCircle} disabled={!canDecide} onClick={() => setConfirm({ action: "REMOVE", ids: [...selected], label: "Removed" })}>Remove</Btn>
          <Btn size="sm" variant="ghost" icon={X} onClick={() => setSelected(new Set())}>Clear</Btn>
        </div>
      )}

      <Panel flush>
        {isLoading ? (
          <div style={{ padding: 16 }}><Skeleton rows={8} /></div>
        ) : leads.length === 0 ? (
          <EmptyState icon={Users} title={running ? "Scoring has started" : "No evaluated leads"}>
            {running ? "The first results will appear in a moment." : c.pipeline.leadStatus === "IDLE" ? "Start AI processing to evaluate your CRM leads for this campaign." : "The CRM had no leads to evaluate."}
          </EmptyState>
        ) : rows.length === 0 ? (
          <EmptyState icon={Check} title={filter === "REVIEW" ? "No leads waiting for review" : "No leads match"}>
            {filter === "REVIEW" ? "Every eligible lead has a decision." : "Try another filter or clear the search."}
          </EmptyState>
        ) : (
          <div className="mk-table-wrap" style={{ borderTop: 0, maxHeight: 620, overflowY: "auto" }}>
            <table className="mk-table">
              <caption className="mk-sr">Lead evaluations</caption>
              <thead>
                <tr>
                  <th style={{ width: 32 }}>
                    <input type="checkbox" className="mk-checkbox" aria-label="Select all shown leads" checked={allSelected} onChange={toggleAll} />
                  </th>
                  <th>Lead name</th>
                  <th>Industry</th>
                  <th>AI score</th>
                  <th>Eligibility</th>
                  <th>Timeline</th>
                  <th className="mk-num">Deal value</th>
                  <th>Interest</th>
                  <th style={{ minWidth: 220 }}>AI reason</th>
                  <th>Approval</th>
                  <th style={{ textAlign: "right" }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((l) => (
                  <tr key={l.id} className={selected.has(l.id) ? "mk-row--selected" : undefined}>
                    <td>
                      <input type="checkbox" className="mk-checkbox" aria-label={`Select ${l.leadName}`} checked={selected.has(l.id)} onChange={() => toggle(l.id)} />
                    </td>
                    <td style={{ minWidth: 160 }}>
                      <button type="button" className="mk-link" onClick={() => setOpen(l.id)}>{l.leadName}</button>
                      <div className="mk-sub">{l.company ?? "—"}</div>
                    </td>
                    <td className="mk-sub">{l.industry ?? "—"}</td>
                    <td>
                      <ScoreMeter score={l.aiScore} />
                      {l.evaluationSource === "RULES_ENGINE" && <div><span className="mk-tag mk-tag--system" title="Scored by the rules engine because the AI model was unavailable">Rules engine</span></div>}
                      {l.evaluationSource === "EMAIL_CHECK" && <div><span className="mk-tag mk-tag--system" title={l.aiReason ?? undefined}>Can't be emailed</span></div>}
                    </td>
                    <td>
                      <EligibilityBadge l={l} />
                      {l.ruleGuardApplied && !l.overrideEligibility && (
                        <div style={{ marginTop: 3 }}><span className="mk-tag mk-tag--system" title="The AI recommended this lead, but it fails one of your required rules">Rule check</span></div>
                      )}
                    </td>
                    <td className="mk-sub" style={{ whiteSpace: "nowrap" }}>{l.purchaseTimeline ?? "—"}</td>
                    <td className="mk-num">{fmtInr(l.estimatedDealValue)}</td>
                    <td className="mk-sub">
                      {l.interestLevel ?? "—"}
                      {l.productInterest && <div className="mk-trunc" style={{ maxWidth: 140 }} title={l.productInterest}>{l.productInterest}</div>}
                    </td>
                    <td>
                      <div className="mk-sub" style={{ display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden", maxWidth: 320 }} title={l.aiReason ?? undefined}>
                        {l.aiReason ?? "—"}
                      </div>
                    </td>
                    <td><ApprovalBadge l={l} /></td>
                    <td style={{ textAlign: "right", whiteSpace: "nowrap" }}>
                      {l.approvalStatus === "PENDING" && l.effectiveEligibility === "ELIGIBLE" && (
                        <>
                          <Btn size="sm" variant="ok" className="mk-icon-btn" aria-label={`Approve ${l.leadName}`} title="Approve" disabled={!canDecide || decide.isPending} onClick={() => single(l, "APPROVE")}>
                            <ThumbsUp size={13} aria-hidden />
                          </Btn>{" "}
                          <Btn size="sm" variant="danger" className="mk-icon-btn" aria-label={`Reject ${l.leadName}`} title="Reject" disabled={!canDecide || decide.isPending} onClick={() => single(l, "REJECT")}>
                            <ThumbsDown size={13} aria-hidden />
                          </Btn>{" "}
                        </>
                      )}
                      <Menu
                        label={`More actions for ${l.leadName}`}
                        items={[
                          { label: "View details", icon: Eye, onSelect: () => setOpen(l.id) },
                          { label: "Approve", icon: ThumbsUp, onSelect: () => single(l, "APPROVE"), disabled: !canDecide, hidden: l.approvalStatus === "APPROVED" || l.evaluationSource === "EMAIL_CHECK" },
                          { label: "Reject", icon: ThumbsDown, onSelect: () => single(l, "REJECT"), disabled: !canDecide, hidden: l.approvalStatus === "REJECTED" },
                          { label: "Remove from campaign", icon: Ban, onSelect: () => single(l, "REMOVE"), disabled: !canDecide, hidden: l.approvalStatus === "REMOVED" },
                          { label: "Back to pending", icon: RotateCcw, onSelect: () => single(l, "RESTORE"), disabled: !canDecide, hidden: l.approvalStatus === "PENDING" },
                          { label: "Edit / override AI…", icon: PencilLine, onSelect: () => setOpen(l.id), disabled: !canDecide },
                        ]}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      {current && <LeadDrawer c={c} lead={current} canDecide={canDecide} onClose={() => setOpen(null)} onDecide={(a, note) => single(current, a, note)} />}

      {confirm && (
        <ConfirmDialog
          title={`${confirm.label === "Approved" ? "Approve" : confirm.label === "Rejected" ? "Reject" : "Remove"} ${confirm.ids.length} leads?`}
          body={confirm.action === "APPROVE" ? "Approved leads will receive this campaign once it launches." : "They won't receive this campaign unless you restore them."}
          confirmLabel={confirm.label === "Approved" ? "Approve leads" : confirm.label === "Rejected" ? "Reject leads" : "Remove leads"}
          tone={confirm.action === "APPROVE" ? "ok" : "danger"}
          note="Note (optional)"
          busy={bulk.isPending}
          onConfirm={runBulk}
          onCancel={() => setConfirm(null)}
        />
      )}

      {rerunAsk && (
        <ConfirmDialog
          title="Re-run AI lead evaluation?"
          body="Every lead is scored again from current CRM data. Existing evaluations and every approval decision on them are discarded (the audit trail keeps the record)."
          confirmLabel="Re-run evaluation"
          tone="danger"
          busy={rerunBusy}
          onConfirm={() => {
            onRerun();
            setRerunAsk(false);
          }}
          onCancel={() => setRerunAsk(false)}
        />
      )}
    </div>
  );
}

/* ================================================================
 * Lead drawer: AI explanation, lead profile, decision, override
 * ================================================================ */

function LeadDrawer({
  c,
  lead: l,
  canDecide,
  onClose,
  onDecide,
}: {
  c: CampaignDetail;
  lead: CampaignLead;
  canDecide: boolean;
  onClose: () => void;
  onDecide: (a: LeadAction, note?: string) => void;
}) {
  const override = useLeadOverride();
  const [ov, setOv] = useState<null | Eligibility>(null);

  const doOverride = (note: string) => {
    if (!ov) return;
    override.mutate(
      { id: c.id, leadRowId: l.id, eligibility: ov, note },
      {
        onSuccess: () => {
          toast.success("AI decision overridden", { description: "Recorded with your reason in the audit trail." });
          setOv(null);
        },
        onError: (e) => toast.error("Override not saved", { description: errMsg(e) }),
      }
    );
  };

  const outcomeIcon = (o: string) =>
    o === "PASS" ? <Check size={14} color="#3B6D11" aria-label="Pass" /> : o === "FAIL" ? <X size={14} color="#A32D2D" aria-label="Fail" /> : <CircleHelp size={14} color="#85878C" aria-label="Unknown" />;

  return (
    <Drawer
      title={l.leadName}
      subtitle={[l.company, l.industry].filter(Boolean).join(" · ")}
      onClose={onClose}
      footer={
        <>
          <span className="mk-row" style={{ marginRight: "auto" }}><ApprovalBadge l={l} /></span>
          {l.approvalStatus !== "PENDING" && <Btn icon={RotateCcw} disabled={!canDecide} onClick={() => onDecide("RESTORE")}>Back to pending</Btn>}
          {l.approvalStatus !== "REMOVED" && <Btn icon={Ban} disabled={!canDecide} onClick={() => onDecide("REMOVE")}>Remove</Btn>}
          {l.approvalStatus !== "REJECTED" && <Btn variant="danger" icon={ThumbsDown} disabled={!canDecide} onClick={() => onDecide("REJECT")}>Reject</Btn>}
          {l.approvalStatus !== "APPROVED" && (
            <Btn
              variant="ok"
              icon={ThumbsUp}
              disabled={!canDecide || l.evaluationSource === "EMAIL_CHECK"}
              title={l.evaluationSource === "EMAIL_CHECK" ? "This lead can't be emailed. Fix its email address and run lead evaluation again." : undefined}
              onClick={() => onDecide("APPROVE")}
            >
              Approve
            </Btn>
          )}
        </>
      }
    >
      <div className="mk-stack">
        <section className="mk-panel" style={{ borderColor: "var(--mk-ai-line)" }}>
          <div className="mk-panel__head">
            <h3 className="mk-panel__title">AI evaluation</h3>
            {l.evaluationSource === "RULES_ENGINE" ? (
              <TransparencyTag kind="SYSTEM" title="Scored by the rules engine because the AI model was unavailable" />
            ) : l.evaluationSource === "EMAIL_CHECK" ? (
              <TransparencyTag kind="SYSTEM" title="Excluded before the AI: the lead can't be emailed" />
            ) : (
              <TransparencyTag kind="AI_RECOMMENDED" />
            )}
          </div>
          <div className="mk-panel__body">
            <div className="mk-row" style={{ gap: 24, marginBottom: 12 }}>
              <div>
                <div className="mk-sub">Score</div>
                <div style={{ fontSize: 26, fontWeight: 680 }}>{l.aiScore ?? "—"}<span className="mk-muted" style={{ fontSize: 14 }}>/100</span></div>
              </div>
              <div>
                <div className="mk-sub">AI eligibility</div>
                <div style={{ marginTop: 4 }}><Badge tone={l.aiEligibility === "ELIGIBLE" ? "ok" : "neutral"}>{l.aiEligibility === "ELIGIBLE" ? "Eligible" : "Not eligible"}</Badge></div>
              </div>
              <div>
                <div className="mk-sub">Confidence</div>
                <div style={{ fontWeight: 650, marginTop: 4 }}>{l.aiConfidence == null ? "—" : `${Math.round(l.aiConfidence * 100)}%`}</div>
              </div>
            </div>
            {l.evaluationSource === "EMAIL_CHECK" && (
              <div style={{ marginBottom: 12 }}>
                <Callout tone="warn" icon={ShieldAlert} title="Mandatory requirement not met">
                  An email campaign can only include leads that can be emailed. This lead was not sent to the AI and can't be approved. Fix the
                  lead's email address in the CRM, then run lead evaluation again.
                </Callout>
              </div>
            )}
            {l.ruleGuardApplied && (
              <div style={{ marginBottom: 12 }}>
                <Callout tone="warn" icon={ShieldAlert} title="Changed by the rule check">
                  The AI recommended this lead, but it definitively fails a required campaign rule, so the system marked it not eligible. The AI's score and reasoning are shown unchanged.
                </Callout>
              </div>
            )}
            <h4 className="mk-h">Positive factors</h4>
            {l.positiveFactors.length ? l.positiveFactors.map((f) => (
              <div key={f} className="mk-factor"><Check size={14} color="#3B6D11" aria-hidden style={{ marginTop: 2 }} />{f}</div>
            )) : <div className="mk-sub">None given</div>}
            <h4 className="mk-h" style={{ marginTop: 12 }}>Negative factors</h4>
            {l.negativeFactors.length ? l.negativeFactors.map((f) => (
              <div key={f} className="mk-factor"><AlertTriangle size={14} color="#854F0B" aria-hidden style={{ marginTop: 2 }} />{f}</div>
            )) : <div className="mk-sub">None given</div>}
            <h4 className="mk-h" style={{ marginTop: 12 }}>Final AI reason</h4>
            <p style={{ margin: 0 }}>{l.aiReason ?? "—"}</p>
            <p className="mk-sub" style={{ margin: "10px 0 0" }}>
              {l.modelName ? `Model ${l.modelName}` : l.evaluationSource === "EMAIL_CHECK" ? "Email check" : "Rules engine"} · evaluated {fmtDateTime(l.evaluatedAt)}. This is a recommendation; it doesn't send anything.
            </p>
          </div>
        </section>

        {l.ruleResults.length > 0 && (
          <Panel title="Campaign rule check" subtitle={`Checked by the CRM against recorded data · lead must meet ${c.ruleMatch === "ALL" ? "all" : "any"}`}>
            <table className="mk-table" style={{ fontSize: 12 }}>
              <thead>
                <tr><th>Rule</th><th>Lead value</th><th>Result</th></tr>
              </thead>
              <tbody>
                {l.ruleResults.map((r, i) => (
                  <tr key={i}>
                    <td>{r.label}</td>
                    <td className="mk-sub">{r.actual ?? "not recorded"}</td>
                    <td><span className="mk-row" style={{ gap: 4, flexWrap: "nowrap" }}>{outcomeIcon(r.outcome)}{r.outcome === "UNKNOWN" ? "Can't tell" : r.outcome === "PASS" ? "Meets" : "Fails"}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Panel>
        )}

        <Panel title="Lead profile" subtitle="The business attributes the AI saw. Name and contact details are never sent to the model.">
          <KeyValues
            rows={[
              ["Company", l.company],
              ["Industry", l.industry],
              ["Company size", l.companySize],
              ["Location", l.location],
              ["Customer type", l.customerType],
              ["Lead source", l.leadSource],
              ["Product interest", l.productInterest],
              ["Interest level", l.interestLevel],
              ["Purchase timeline", l.purchaseTimeline],
              ["Estimated deal value", fmtInr(l.estimatedDealValue)],
            ]}
          />
        </Panel>

        <Panel title="Human decision">
          <KeyValues
            rows={[
              ["Approval status", <ApprovalBadge l={l} />],
              ["Override", l.overrideEligibility ? `${l.overrideEligibility === "ELIGIBLE" ? "Eligible" : "Not eligible"} (AI said ${l.aiEligibility === "ELIGIBLE" ? "eligible" : "not eligible"})` : null],
              ["Note", l.reviewNote],
              ["Decided by", l.reviewedBy],
              ["Decided at", l.reviewedAt ? fmtDateTime(l.reviewedAt) : null],
            ]}
          />
          <div className="mk-row" style={{ marginTop: 12 }}>
            <Btn icon={PencilLine} disabled={!canDecide} onClick={() => setOv(l.effectiveEligibility === "ELIGIBLE" ? "NOT_ELIGIBLE" : "ELIGIBLE")}>
              Override to {l.effectiveEligibility === "ELIGIBLE" ? "not eligible" : "eligible"}
            </Btn>
          </div>
        </Panel>
      </div>

      {ov && (
        <ConfirmDialog
          title={`Override the AI: mark as ${ov === "ELIGIBLE" ? "eligible" : "not eligible"}?`}
          body={ov === "ELIGIBLE" ? "The lead is approved for this campaign. The AI's original evaluation stays visible." : "The lead is rejected for this campaign. The AI's original evaluation stays visible."}
          confirmLabel="Override"
          note="Why are you overriding the AI?"
          noteRequired
          busy={override.isPending}
          onConfirm={doOverride}
          onCancel={() => setOv(null)}
        />
      )}
    </Drawer>
  );
}
