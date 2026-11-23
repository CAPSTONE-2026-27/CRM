import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Save, Sparkles, Split, Trophy } from "lucide-react";
import type { AbElement, AbMetric, AbTestRequest, CampaignDetail, Funnel } from "../../api/types";
import { useAnalytics, useCampaignContent, useChooseWinner, useConfigureAbTest, useGenerateContent } from "../../api/hooks";
import { Badge, Btn, Callout, ConfirmDialog, EmptyState, Field, Panel, Segmented, Skeleton } from "../../components/ui";
import { abElementLabel, abMetricLabel, channelFields, fmtDateTime, fmtNum, fmtPct } from "../../theme";

const errMsg = (e: unknown) => (e instanceof Error ? e.message : undefined);

const DEFAULTS: AbTestRequest = {
  enabled: true,
  testElement: "EMAIL_SUBJECT",
  splitAPct: 50,
  testAudiencePct: 100,
  durationHours: 72,
  primaryMetric: "OPEN_RATE",
  decisionRule: "MANUAL",
  minSamplePerVariant: 100,
};

/** Which email field differs for each element, for the side-by-side view. Structure compares the whole email. */
const ELEMENT_FIELD: Record<AbElement, string | null> = {
  EMAIL_SUBJECT: "subject",
  PREVIEW_TEXT: "preheader",
  EMAIL_BODY: "body",
  CTA: "cta",
  EMAIL_STRUCTURE: null,
};

const toRequest = (t: AbTestRequest): AbTestRequest => ({
  enabled: t.enabled,
  testElement: t.testElement,
  splitAPct: t.splitAPct,
  testAudiencePct: t.testAudiencePct,
  durationHours: t.durationHours,
  primaryMetric: t.primaryMetric,
  decisionRule: t.decisionRule,
  minSamplePerVariant: t.minSamplePerVariant,
});

const metricOf = (f: Funnel, m: AbMetric) =>
  m === "OPEN_RATE" ? f.openRate : m === "CLICK_RATE" ? f.clickRate : m === "CONVERSION_RATE" ? f.conversionRate : f.responseRate;

export function AbTestPanel({ c }: { c: CampaignDetail }) {
  const configure = useConfigureAbTest();
  const generate = useGenerateContent();
  const content = useCampaignContent(c.id, c.pipeline.contentStatus === "RUNNING");
  const launched = ["ACTIVE", "PAUSED", "COMPLETED", "CANCELLED", "SCHEDULED"].includes(c.status);
  const analytics = useAnalytics(c.id, { key: "90d" }, launched && !!c.abTest?.enabled);
  const [form, setForm] = useState<AbTestRequest>(c.abTest ? toRequest(c.abTest) : { ...DEFAULTS, enabled: false });
  useEffect(() => {
    if (c.abTest) setForm(toRequest(c.abTest));
  }, [c.abTest]);

  const editable = ["DRAFT", "PENDING_LEAD_APPROVAL", "PENDING_CONTENT_APPROVAL", "READY_TO_LAUNCH"].includes(c.status);
  const set = <K extends keyof AbTestRequest>(k: K, v: AbTestRequest[K]) => setForm((f) => ({ ...f, [k]: v }));
  const ab = c.abTest;
  const tested = ab?.enabled ? ab.testedChannel : null;
  const variantA = content.data?.find((i) => i.channel === tested && i.variant === "A");
  const variantB = content.data?.find((i) => i.channel === tested && i.variant === "B");

  const save = () =>
    configure.mutate(
      { id: c.id, body: toRequest(form) },
      {
        onSuccess: () => toast.success(form.enabled ? "A/B test saved" : "A/B testing turned off"),
        onError: (e) => toast.error("Couldn't save the A/B test", { description: errMsg(e) }),
      }
    );
  const genB = () =>
    generate.mutate(
      { id: c.id, variant: "B" },
      { onSuccess: () => toast.success("Generating variant B"), onError: (e) => toast.error("Couldn't generate variant B", { description: errMsg(e) }) }
    );

  const bSize = Math.round((form.testAudiencePct * (100 - form.splitAPct)) / 100);
  const aSize = Math.round((form.testAudiencePct * form.splitAPct) / 100);
  const approved = c.readiness.approvedLeads;

  return (
    <div className="mk-stack">
      <div className="mk-grid-2" style={{ alignItems: "start" }}>
        <Panel
          title="Test configuration"
          subtitle="Two approved email versions, one difference, measured on real recipient events"
          actions={editable ? <Btn variant="primary" icon={Save} busy={configure.isPending} onClick={save}>Save</Btn> : undefined}
        >
          <div className="mk-fields">
            <Field label="A/B testing" wide>
              <Segmented
                label="A/B testing"
                value={form.enabled ? "on" : "off"}
                onChange={(v) => set("enabled", v === "on")}
                options={[{ value: "off", label: "Off" }, { value: "on", label: "On" }]}
              />
            </Field>
            <Field label="What to test" wide help="Only this element differs between A and B.">
              <select className="mk-select" disabled={!editable || !form.enabled} value={form.testElement} onChange={(e) => set("testElement", e.target.value as AbElement)}>
                {(Object.keys(abElementLabel) as AbElement[]).map((k) => <option key={k} value={k}>{abElementLabel[k]}</option>)}
              </select>
            </Field>
            <Field label={`Audience split · ${form.splitAPct}% A / ${100 - form.splitAPct}% B`} wide>
              <input type="range" min={10} max={90} step={5} disabled={!editable || !form.enabled} value={form.splitAPct} onChange={(e) => set("splitAPct", Number(e.target.value))} aria-label="Share of the test audience receiving variant A" />
            </Field>
            <Field label="Test audience" help="Share of approved leads in the test. The rest wait and get the chosen variant.">
              <select className="mk-select" disabled={!editable || !form.enabled} value={form.testAudiencePct} onChange={(e) => set("testAudiencePct", Number(e.target.value))}>
                {[100, 50, 30, 20, 10].map((p) => <option key={p} value={p}>{p === 100 ? "All approved leads" : `${p}% of approved leads`}</option>)}
              </select>
            </Field>
            <Field label="Test duration">
              <select className="mk-select" disabled={!editable || !form.enabled} value={form.durationHours} onChange={(e) => set("durationHours", Number(e.target.value))}>
                {[24, 48, 72, 120, 168, 336].map((h) => <option key={h} value={h}>{h < 168 ? `${h / 24} day${h > 24 ? "s" : ""}` : `${h / 168} week${h > 168 ? "s" : ""}`}</option>)}
              </select>
            </Field>
            <Field label="Primary success metric">
              <select className="mk-select" disabled={!editable || !form.enabled} value={form.primaryMetric} onChange={(e) => set("primaryMetric", e.target.value as AbMetric)}>
                {(Object.keys(abMetricLabel) as AbMetric[]).map((k) => <option key={k} value={k}>{abMetricLabel[k]}</option>)}
              </select>
            </Field>
            <Field label="Minimum sample per variant" help="Reachable emails (sent minus bounced) needed before a rule-based decision">
              <input className="mk-input" type="number" min={1} disabled={!editable || !form.enabled} value={form.minSamplePerVariant} onChange={(e) => set("minSamplePerVariant", Math.max(1, Number(e.target.value) || 1))} />
            </Field>
            <Field label="Decision rule" wide>
              <div className="mk-stack" style={{ gap: 6 }}>
                {(
                  [
                    ["MANUAL", "I'll choose after reviewing the results", "Recommended. Nothing is decided for you."],
                    ["HIGHEST_PRIMARY_METRIC", "Pick the higher measured primary metric", "Applied only after the window closes and both variants reach the minimum sample. A tie or thin sample comes back to you."],
                  ] as const
                ).map(([value, label, hint]) => (
                  <label key={value} className="mk-row" style={{ alignItems: "flex-start", flexWrap: "nowrap", gap: 8, cursor: "pointer" }}>
                    <input type="radio" name="mk-decision" disabled={!editable || !form.enabled} checked={form.decisionRule === value} onChange={() => set("decisionRule", value)} style={{ marginTop: 3 }} />
                    <span>
                      <span style={{ fontWeight: 600 }}>{label}</span>
                      <span className="mk-help" style={{ display: "block" }}>{hint}</span>
                    </span>
                  </label>
                ))}
              </div>
            </Field>
          </div>
          {form.enabled && (
            <p className="mk-sub" style={{ margin: "12px 0 0" }}>
              With {fmtNum(approved)} approved leads: about {fmtNum(Math.round((approved * aSize) / 100))} get A, {fmtNum(Math.round((approved * bSize) / 100))} get B
              {form.testAudiencePct < 100 ? `, and ${fmtNum(approved - Math.round((approved * form.testAudiencePct) / 100))} wait for the decision` : ""}.
            </p>
          )}
          {!editable && <Callout tone="info">The test is fixed once the campaign is scheduled or launched.</Callout>}
        </Panel>

        <Panel title="Variants" subtitle={tested ? `Testing the ${abElementLabel[ab!.testElement].toLowerCase()}` : undefined}>
          {!ab?.enabled ? (
            <EmptyState icon={Split} title="A/B testing is off">Turn it on and save to compare two versions of one element.</EmptyState>
          ) : content.isLoading ? (
            <Skeleton rows={4} />
          ) : !variantA ? (
            <Callout tone="info">Variant A is the generated campaign email. Generate the email first.</Callout>
          ) : (
            <div className="mk-stack">
              <div className="mk-compare">
                {[variantA, variantB].map((v, i) => (
                  <div key={i} className="mk-variant">
                    <div className="mk-row" style={{ justifyContent: "space-between", marginBottom: 6 }}>
                      <strong>Variant {i === 0 ? "A" : "B"}</strong>
                      {v && <Badge tone={v.status === "APPROVED" ? "ok" : v.status === "REJECTED" ? "bad" : "warn"}>{v.status === "APPROVED" ? "Approved" : v.status === "REJECTED" ? "Rejected" : v.status === "DRAFT" ? "Draft" : "Pending"}</Badge>}
                    </div>
                    {v ? (
                      <VariantText fields={v.fields} element={ab.testElement} channel={tested!} />
                    ) : (
                      <div className="mk-sub">Not created yet.</div>
                    )}
                  </div>
                ))}
              </div>
              <div className="mk-row">
                <Btn variant="ai" icon={Sparkles} disabled={!editable || c.pipeline.contentStatus === "RUNNING"} busy={generate.isPending || c.pipeline.contentStatus === "RUNNING"} onClick={genB}>
                  {variantB ? "Regenerate variant B" : "Generate variant B"}
                </Btn>
                <span className="mk-sub">Review and approve both variants on the Content tab. Only approved variants are sent.</span>
              </div>
            </div>
          )}
        </Panel>
      </div>

      {ab?.enabled && launched && (
        <Results c={c} funnels={analytics.data?.variants ?? []} loading={analytics.isLoading} />
      )}
    </div>
  );
}

function VariantText({ fields, element, channel }: { fields: Record<string, string>; element: AbElement; channel: string }) {
  const key = ELEMENT_FIELD[element];
  if (key && fields[key]) {
    return <div style={{ whiteSpace: "pre-wrap", fontSize: 13, maxHeight: 220, overflow: "auto" }}>{fields[key]}</div>;
  }
  const defs = channelFields[channel as keyof typeof channelFields] ?? [];
  return (
    <div className="mk-stack" style={{ gap: 6, maxHeight: 220, overflow: "auto" }}>
      {defs.filter((d) => fields[d.key]).map((d) => (
        <div key={d.key}>
          <div className="mk-help">{d.label}</div>
          <div style={{ whiteSpace: "pre-wrap", fontSize: 12.5 }}>{fields[d.key]}</div>
        </div>
      ))}
    </div>
  );
}

function Results({ c, funnels, loading }: { c: CampaignDetail; funnels: { variant: "A" | "B"; funnel: Funnel }[]; loading: boolean }) {
  const ab = c.abTest!;
  const choose = useChooseWinner();
  const [pick, setPick] = useState<"A" | "B" | null>(null);
  const byV = (v: "A" | "B") => funnels.find((f) => f.variant === v)?.funnel;
  const rows: { label: string; get: (f: Funnel) => string; metric?: AbMetric }[] = [
    { label: "Emails sent", get: (f) => fmtNum(f.sent) },
    { label: "Delivery rate", get: (f) => (f.deliveryRate == null ? "Not reported" : fmtPct(f.deliveryRate)) },
    { label: "Bounced", get: (f) => fmtNum(f.bounced) },
    { label: "Open rate (estimate)", get: (f) => fmtPct(f.openRate), metric: "OPEN_RATE" },
    { label: "Click-through rate", get: (f) => fmtPct(f.clickRate), metric: "CLICK_RATE" },
    { label: "Response rate", get: (f) => fmtPct(f.responseRate), metric: "RESPONSE_RATE" },
    { label: "Conversion rate", get: (f) => fmtPct(f.conversionRate), metric: "CONVERSION_RATE" },
  ];
  const decided = ab.status === "DECIDED";

  return (
    <Panel
      title="Measured results"
      subtitle={
        ab.status === "RUNNING"
          ? `Test running until ${fmtDateTime(ab.endsAt)}`
          : ab.status === "AWAITING_DECISION"
            ? "The test window has closed. Choose the variant to continue with."
            : decided
              ? `Variant ${ab.winnerVariant} selected ${ab.decidedBy ? `by ${ab.decidedBy}` : "by the configured decision rule"} · ${fmtDateTime(ab.decidedAt)}`
              : undefined
      }
    >
      {loading ? (
        <Skeleton rows={5} />
      ) : (
        <div className="mk-stack">
          <div className="mk-table-wrap" style={{ borderTop: 0 }}>
            <table className="mk-table">
              <caption className="mk-sr">A/B test results by variant</caption>
              <thead>
                <tr><th>Metric</th><th className="mk-num">Variant A</th><th className="mk-num">Variant B</th></tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const primary = r.metric === ab.primaryMetric;
                  const a = byV("A");
                  const b = byV("B");
                  return (
                    <tr key={r.label} style={primary ? { background: "#F7FAFD" } : undefined}>
                      <td style={{ fontWeight: primary ? 650 : 400 }}>{r.label}{primary && <span className="mk-tag mk-tag--edited" style={{ marginLeft: 6 }}>Primary</span>}</td>
                      <td className="mk-num" style={{ fontWeight: primary ? 650 : 400 }}>{a ? r.get(a) : "—"}</td>
                      <td className="mk-num" style={{ fontWeight: primary ? 650 : 400 }}>{b ? r.get(b) : "—"}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="mk-sub" style={{ margin: 0 }}>
            These are counts, not a prediction. Rates use emails sent minus bounces as the base; opens are an estimate because privacy
            features and security scanners can load or block the tracking image. Small samples swing easily; the configured minimum
            is {fmtNum(ab.minSamplePerVariant)} reachable emails per variant.
            {(() => {
              const a = byV("A");
              const b = byV("B");
              if (!a || !b) return null;
              const ma = metricOf(a, ab.primaryMetric);
              const mb = metricOf(b, ab.primaryMetric);
              if (ma == null || mb == null) return null;
              return ` Difference on ${abMetricLabel[ab.primaryMetric].toLowerCase()}: ${Math.abs(ma - mb).toFixed(1)} points.`;
            })()}
          </p>
          {decided ? (
            <Callout tone="ok" icon={Trophy} title={`Continuing with variant ${ab.winnerVariant}`}>{ab.decisionNote}</Callout>
          ) : (
            c.viewerIsApprover && (
              <div className="mk-row">
                <span className="mk-sub">Your decision:</span>
                <Btn onClick={() => setPick("A")}>Select variant A</Btn>
                <Btn onClick={() => setPick("B")}>Select variant B</Btn>
              </div>
            )
          )}
        </div>
      )}
      {pick && (
        <ConfirmDialog
          title={`Continue with variant ${pick}?`}
          body={ab.testAudiencePct < 100 ? "Leads held back from the test will receive this variant." : "The choice is recorded against the measured results in the audit trail."}
          confirmLabel={`Select variant ${pick}`}
          note="Reason (optional)"
          busy={choose.isPending}
          onConfirm={(note) =>
            choose.mutate(
              { id: c.id, variant: pick, note: note || undefined },
              { onSuccess: () => { toast.success(`Variant ${pick} selected`); setPick(null); }, onError: (e) => toast.error("Couldn't record the decision", { description: errMsg(e) }) }
            )
          }
          onCancel={() => setPick(null)}
        />
      )}
    </Panel>
  );
}
