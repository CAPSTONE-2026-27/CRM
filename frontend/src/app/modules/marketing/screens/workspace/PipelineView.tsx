import { ReactNode } from "react";
import { Check, Loader2, X } from "lucide-react";
import type { CampaignDetail } from "../../api/types";
import { fmtNum } from "../../theme";

type State = "done" | "run" | "fail" | "wait" | "todo";

function Step({ state, children, sub }: { state: State; children: ReactNode; sub?: ReactNode }) {
  const cls = state === "done" ? "mk-state--done" : state === "run" ? "mk-state--run" : state === "fail" ? "mk-state--fail" : state === "wait" ? "mk-state--wait" : "";
  return (
    <div className="mk-track__step">
      <span className={`mk-state ${cls}`} aria-hidden>
        {state === "done" ? <Check size={12} /> : state === "run" ? <Loader2 size={12} className="mk-spin" /> : state === "fail" ? <X size={12} /> : null}
      </span>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: state === "todo" ? 450 : 580, color: state === "todo" ? "var(--mk-ink-3)" : undefined }}>{children}</div>
        {sub && <div className="mk-sub" style={{ marginTop: 2 }}>{sub}</div>}
      </div>
    </div>
  );
}

/**
 * The campaign's path through the two AI pipelines and the human checkpoints
 * that follow them. Everything is derived from the server's pipeline and
 * readiness state, so it can't show a step as done that isn't.
 */
export function PipelineView({ c }: { c: CampaignDetail }) {
  const p = c.pipeline;
  const r = c.readiness;
  const submitted = c.status !== "DRAFT" || p.leadStatus !== "IDLE" || p.contentStatus !== "IDLE";
  const launched = ["SCHEDULED", "ACTIVE", "PAUSED", "COMPLETED"].includes(c.status);
  const pct = p.leadTotal > 0 ? Math.round((100 * p.leadDone) / p.leadTotal) : 0;
  const contentDone = r.approvedContent.length;

  const leadState: State = p.leadStatus === "COMPLETED" ? "done" : p.leadStatus === "RUNNING" ? "run" : p.leadStatus === "FAILED" ? "fail" : "todo";
  const contentState: State = p.contentStatus === "COMPLETED" ? "done" : p.contentStatus === "RUNNING" ? "run" : p.contentStatus === "FAILED" ? "fail" : "todo";

  return (
    <div className="mk-stack" style={{ gap: 12 }}>
      <div className="mk-track" style={{ display: "flex", gap: 24, flexWrap: "wrap", padding: "10px 14px" }}>
        <Step state={submitted ? "done" : "todo"}>Campaign created</Step>
        <Step state={submitted ? "done" : "todo"}>Campaign data validated</Step>
        <Step state={p.leadStatus === "RUNNING" || p.contentStatus === "RUNNING" ? "run" : submitted ? "done" : "todo"}>AI engine</Step>
      </div>

      <div className="mk-flow">
        <div className="mk-track" aria-label="Pipeline A: lead evaluation">
          <div className="mk-h">Pipeline A · Lead evaluation</div>
          <Step
            state={leadState}
            sub={
              p.leadStatus === "RUNNING"
                ? `${fmtNum(p.leadDone)} of ${fmtNum(p.leadTotal)} leads scored individually`
                : p.leadStatus === "COMPLETED"
                  ? `${fmtNum(r.evaluatedLeads)} leads scored · ${fmtNum(r.eligibleLeads)} eligible`
                  : p.leadStatus === "FAILED"
                    ? p.leadError
                    : "Scores every CRM lead against this campaign"
            }
          >
            {p.leadStatus === "RUNNING" ? "Analyzing leads…" : p.leadStatus === "COMPLETED" ? "Lead scoring completed" : p.leadStatus === "FAILED" ? "Lead scoring failed" : "Analyze leads"}
          </Step>
          {p.leadStatus === "RUNNING" && (
            <div className="mk-progress" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label="Lead evaluation progress" style={{ margin: "2px 0 6px 30px" }}>
              <span style={{ width: `${pct}%` }} />
            </div>
          )}
          <Step
            state={r.leadsReady ? "done" : p.leadStatus === "COMPLETED" ? "wait" : "todo"}
            sub={p.leadStatus === "COMPLETED" && !r.leadsReady ? `${fmtNum(r.eligiblePending)} eligible leads awaiting your decision` : undefined}
          >
            {p.leadStatus === "COMPLETED" && !r.leadsReady ? "Waiting for Marketing Head approval" : "Manual lead review"}
          </Step>
          <Step state={r.leadsReady ? "done" : "todo"} sub={r.approvedLeads ? `${fmtNum(r.approvedLeads)} approved` : undefined}>
            Approved leads
          </Step>
        </div>

        <div className="mk-track" aria-label="Pipeline B: content generation">
          <div className="mk-h">Pipeline B · Content generation</div>
          <Step
            state={contentState}
            sub={p.contentStatus === "FAILED" ? p.contentError : p.contentStatus === "RUNNING" ? "Writing from your product and offer details only" : undefined}
          >
            {p.contentStatus === "RUNNING" ? "Generating campaign content…" : p.contentStatus === "COMPLETED" ? "Content generation completed" : p.contentStatus === "FAILED" ? "Content generation failed" : "Generate content"}
          </Step>
          {p.contentStatus === "COMPLETED" && p.contentError && <div className="mk-sub" style={{ marginLeft: 30 }}>{p.contentError}</div>}
          <Step
            state={r.contentReady ? "done" : p.contentStatus === "COMPLETED" ? "wait" : "todo"}
            sub={p.contentStatus === "COMPLETED" && !r.contentReady ? `${contentDone} of ${r.requiredContent.length} items approved` : undefined}
          >
            {p.contentStatus === "COMPLETED" && !r.contentReady ? "Waiting for Marketing Head approval" : "Manual content review"}
          </Step>
          <Step state={r.contentReady ? "done" : "todo"}>Approved content</Step>
        </div>
      </div>

      <div className="mk-track" style={{ display: "flex", gap: 24, flexWrap: "wrap", padding: "10px 14px" }}>
        <Step state={launched ? "done" : c.status === "READY_TO_LAUNCH" ? "wait" : "todo"}>Campaign strategy</Step>
        <Step state={!c.abTest?.enabled ? (launched ? "done" : "todo") : c.abTest.status === "DECIDED" ? "done" : c.abTest.status === "CONFIGURED" ? "todo" : "run"} sub={c.abTest?.enabled ? undefined : "optional"}>
          A/B testing
        </Step>
        <Step state={c.status === "ACTIVE" ? "run" : c.status === "COMPLETED" ? "done" : c.status === "SCHEDULED" || c.status === "PAUSED" ? "wait" : "todo"}>Campaign execution</Step>
        <Step state={launched ? "run" : "todo"}>Analytics</Step>
      </div>
    </div>
  );
}
