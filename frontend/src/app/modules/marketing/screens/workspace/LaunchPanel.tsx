import { useEffect, useState } from "react";
import { toast } from "sonner";
import { ArrowLeft, CalendarClock, CalendarX, CheckCircle2, Circle, Pause, Play, Rocket, Save } from "lucide-react";
import type { CampaignDetail, StrategyRequest } from "../../api/types";
import { useCampaignCommand, useMarketingMeta, useScheduleCampaign, useUpdateStrategy } from "../../api/hooks";
import { Btn, Callout, ConfirmDialog, Field, KeyValues, Panel, StatusBadge } from "../../components/ui";
import { abElementLabel, abMetricLabel, fmtDate, fmtDateTime, fmtNum } from "../../theme";
import type { WorkspaceTab } from "../CampaignWorkspace";

const errMsg = (e: unknown) => (e instanceof Error ? e.message : undefined);
const SEND_WINDOWS = ["Any time", "Business hours (9:00–18:00)", "Mornings (9:00–12:00)", "Afternoons (13:00–17:00)", "Weekdays only"];

function toLocalInput(d: Date) {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function LaunchPanel({ c, onTab }: { c: CampaignDetail; onTab: (t: WorkspaceTab) => void }) {
  const meta = useMarketingMeta();
  const saveStrategy = useUpdateStrategy();
  const command = useCampaignCommand();
  const schedule = useScheduleCampaign();
  const [strategy, setStrategy] = useState<StrategyRequest>(c.strategy);
  useEffect(() => setStrategy(c.strategy), [c.strategy]);
  const [ask, setAsk] = useState<null | "launch" | "schedule" | "unschedule" | "pause" | "resume">(null);
  const [when, setWhen] = useState(() => toLocalInput(new Date(Date.now() + 3600_000)));

  const r = c.readiness;
  const launched = ["SCHEDULED", "ACTIVE", "PAUSED", "COMPLETED", "CANCELLED"].includes(c.status);
  const canAct = c.viewerIsApprover;
  const days = c.startDate && c.endDate ? Math.round((new Date(c.endDate).getTime() - new Date(c.startDate).getTime()) / 86400000) + 1 : null;
  const email = meta.data?.email;

  const persist = () =>
    saveStrategy.mutate(
      { id: c.id, body: strategy },
      { onSuccess: () => toast.success("Strategy saved"), onError: (e) => toast.error("Couldn't save", { description: errMsg(e) }) }
    );

  const run = () => {
    const done = (msg: string) => () => {
      toast.success(msg);
      setAsk(null);
    };
    const fail = (what: string) => (e: unknown) => toast.error(`Couldn't ${what}`, { description: errMsg(e) });
    if (ask === "schedule") {
      schedule.mutate({ id: c.id, scheduledAt: new Date(when).toISOString() }, { onSuccess: done(`Scheduled for ${fmtDateTime(new Date(when).toISOString())}`), onError: fail("schedule") });
    } else if (ask) {
      command.mutate(
        { id: c.id, command: ask },
        {
          onSuccess: done(ask === "launch" ? "Campaign launched" : ask === "unschedule" ? "Schedule removed" : ask === "pause" ? "Campaign paused" : "Campaign resumed"),
          onError: fail(ask),
        }
      );
    }
  };

  const Check = ({ ok, children }: { ok: boolean; children: React.ReactNode }) => (
    <div className="mk-factor">
      {ok ? <CheckCircle2 size={15} color="#3B6D11" aria-label="Done" /> : <Circle size={15} color="#85878C" aria-label="Not done" />}
      <span>{children}</span>
    </div>
  );

  return (
    <div className="mk-stack">
      {c.status === "SCHEDULED" && (
        <Callout tone="info" icon={CalendarClock} title={`Scheduled for ${fmtDateTime(c.scheduledAt)}`}>
          Sending starts automatically at that time.
          {canAct && <div style={{ marginTop: 8 }}><Btn size="sm" icon={CalendarX} onClick={() => setAsk("unschedule")}>Unschedule</Btn></div>}
        </Callout>
      )}
      {(c.status === "ACTIVE" || c.status === "PAUSED") && (
        <Callout tone={c.status === "ACTIVE" ? "ok" : "warn"} title={c.status === "ACTIVE" ? `Live since ${fmtDateTime(c.launchedAt)}` : "Paused"}>
          {c.status === "ACTIVE"
            ? email && !email.configured
              ? "Emails are queued but nothing is being sent: email sending isn't configured on the server."
              : "Emails are being sent in controlled batches. Failed sends are retried automatically; unsubscribed and bounced addresses are skipped."
            : "Nothing more is sent until you resume."}
          {canAct && (
            <div style={{ marginTop: 8 }}>
              {c.status === "ACTIVE" ? <Btn size="sm" icon={Pause} onClick={() => setAsk("pause")}>Pause</Btn> : <Btn size="sm" icon={Play} onClick={() => setAsk("resume")}>Resume</Btn>}
            </div>
          )}
        </Callout>
      )}

      <div className="mk-grid-main">
        <div className="mk-stack">
          <Panel title="Campaign strategy" subtitle="Review the complete campaign before it goes out">
            <KeyValues
              rows={[
                ["Status", <StatusBadge status={c.status} />],
                ["Objective", c.objective],
                ["Target audience", [c.audience.customerSegment, c.audience.industries.join(", "), c.audience.companySize].filter(Boolean).join(" · ")],
                ["Eligible leads", `${fmtNum(r.eligibleLeads)} of ${fmtNum(r.evaluatedLeads)} evaluated (AI recommended)`],
                ["Approved leads", `${fmtNum(r.approvedLeads)} (human approved) — the selected audience`],
                ["Channel", "Email: one approved email per approved lead"],
                ["From", c.contentConfig.senderName || c.contentConfig.senderEmail ? `${c.contentConfig.senderName ?? ""} <${c.contentConfig.senderEmail ?? email?.verifiedSenders[0] ?? "default sender"}>` : null],
                ["Reply-to", c.contentConfig.replyToEmail],
                ["Duration", `${fmtDate(c.startDate)} – ${fmtDate(c.endDate)}${days ? ` (${days} days)` : ""}`],
                ["Email", `${r.approvedContent.length} of ${r.requiredContent.length} required versions approved`],
                ["A/B testing", c.abTest?.enabled ? `${abElementLabel[c.abTest.testElement]} · ${c.abTest.splitAPct}/${100 - c.abTest.splitAPct} split · ${abMetricLabel[c.abTest.primaryMetric]} · ${c.abTest.decisionRule === "MANUAL" ? "manual decision" : "highest measured metric"}` : "Off"],
                ["Email provider", email ? (email.configured ? email.provider : "Not configured") : null],
                ["Tracking", email ? (email.trackingEnabled ? "Clicks and estimated opens via tracked links" : "Off") : null],
              ]}
            />
          </Panel>

          <Panel title="Sending schedule" actions={!launched ? <Btn icon={Save} busy={saveStrategy.isPending} onClick={persist}>Save draft</Btn> : undefined}>
            <div className="mk-fields">
              <Field label="Send window" htmlFor="mk-window">
                <select id="mk-window" className="mk-select" disabled={launched} value={strategy.sendWindow ?? ""} onChange={(e) => setStrategy({ ...strategy, sendWindow: e.target.value || null })}>
                  <option value="">Not set</option>
                  {SEND_WINDOWS.map((w) => <option key={w}>{w}</option>)}
                </select>
              </Field>
              <Field label="Time zone" htmlFor="mk-tz">
                <input id="mk-tz" className="mk-input" disabled={launched} placeholder="Asia/Kolkata" value={strategy.timezone ?? ""} onChange={(e) => setStrategy({ ...strategy, timezone: e.target.value || null })} />
              </Field>
              <Field label="Strategy notes" htmlFor="mk-notes" wide>
                <textarea id="mk-notes" className="mk-textarea" rows={3} maxLength={1000} disabled={launched} value={strategy.notes ?? ""} onChange={(e) => setStrategy({ ...strategy, notes: e.target.value || null })} />
              </Field>
            </div>
          </Panel>
        </div>

        <Panel title="Launch checklist">
          <div className="mk-stack" style={{ gap: 4 }}>
            <Check ok={c.pipeline.leadStatus === "COMPLETED"}>Leads evaluated by AI</Check>
            <Check ok={r.eligiblePending === 0 && c.pipeline.leadStatus === "COMPLETED"}>Every eligible lead reviewed</Check>
            <Check ok={r.approvedLeads > 0}>At least one lead approved ({fmtNum(r.approvedLeads)})</Check>
            <Check ok={r.contentReady}>Email approved{c.abTest?.enabled ? " (both variants)" : ""}</Check>
            <Check ok={!c.abTest?.enabled || r.requiredContent.some((k) => k.endsWith(":B"))}>A/B variants in place{c.abTest?.enabled ? "" : " (not used)"}</Check>
            <Check ok={!!email?.configured}>Email provider configured</Check>
          </div>
          {!launched && r.blockers.length > 0 && (
            <div style={{ marginTop: 12 }}>
              <Callout tone="warn" title="Not ready yet">
                <ul style={{ margin: "2px 0 0", paddingLeft: 18 }}>{r.blockers.map((b) => <li key={b}>{b}</li>)}</ul>
              </Callout>
            </div>
          )}
          {email && !email.configured && (
            <div style={{ marginTop: 12 }}>
              <Callout tone="warn" title="Configuration required">
                Campaign email can't be sent until an administrator configures the email provider on the server. Missing:
                <ul style={{ margin: "4px 0 0", paddingLeft: 18 }}>{email.missing.map((m) => <li key={m}>{m}</li>)}</ul>
              </Callout>
            </div>
          )}
          {!canAct && !launched && <p className="mk-sub" style={{ marginTop: 12 }}>Scheduling and launching need the Marketing Head (marketing approver) permission.</p>}
          {!launched && (
            <div className="mk-row" style={{ marginTop: 16 }}>
              <Btn icon={ArrowLeft} onClick={() => onTab("content")}>Back</Btn>
              <span className="mk-spacer" />
              <Btn icon={CalendarClock} disabled={!canAct || !r.canLaunch} onClick={() => setAsk("schedule")}>Schedule</Btn>
              <Btn variant="primary" icon={Rocket} disabled={!canAct || !r.canLaunch} onClick={() => setAsk("launch")}>Launch campaign</Btn>
            </div>
          )}
        </Panel>
      </div>

      {ask && (
        <ConfirmDialog
          title={
            ask === "launch" ? `Launch "${c.name}" now?` : ask === "schedule" ? "Schedule this campaign" : ask === "unschedule" ? "Remove the schedule?" : ask === "pause" ? "Pause sending?" : "Resume sending?"
          }
          body={
            ask === "launch"
              ? `The approved email is queued for ${fmtNum(r.approvedLeads)} approved leads and sent in batches. Addresses that are missing, duplicated, unsubscribed or bounced are skipped. Leads and email are locked from this point.`
              : ask === "schedule"
                ? "Leads and content are locked now; sending starts at the chosen time."
                : ask === "unschedule"
                  ? "The campaign returns to review and can be edited again."
                  : undefined
          }
          confirmLabel={ask === "launch" ? "Launch campaign" : ask === "schedule" ? "Schedule" : ask === "unschedule" ? "Unschedule" : ask === "pause" ? "Pause" : "Resume"}
          tone={ask === "launch" || ask === "resume" ? "ok" : "primary"}
          busy={command.isPending || schedule.isPending}
          onConfirm={run}
          onCancel={() => setAsk(null)}
        >
          {ask === "schedule" && (
            <label className="mk-field" style={{ marginTop: 12 }}>
              <span className="mk-label">Send at</span>
              <input type="datetime-local" className="mk-input" value={when} min={toLocalInput(new Date())} max={c.endDate ? `${c.endDate}T23:59` : undefined} onChange={(e) => setWhen(e.target.value)} />
            </label>
          )}
        </ConfirmDialog>
      )}
    </div>
  );
}
