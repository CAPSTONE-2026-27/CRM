import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Check, FileText, Mail, PencilLine, RefreshCw, Save, ThumbsDown, ThumbsUp, X } from "lucide-react";
import type { CampaignDetail, ContentItem } from "../../api/types";
import { useCampaignContent, useCampaignLeads, useContentDecision, useEmailPreview, useGenerateContent, useUpdateContent } from "../../api/hooks";
import { Badge, Btn, Callout, ConfirmDialog, EmptyState, ErrorState, KeyValues, Panel, ProvenanceTag, Segmented, Skeleton, TransparencyTag } from "../../components/ui";
import { channelFields, fmtDateTime, fmtNum } from "../../theme";

const errMsg = (e: unknown) => (e instanceof Error ? e.message : undefined);
const statusBadge = (s: ContentItem["status"]) =>
  s === "APPROVED" ? <Badge tone="ok">Approved</Badge> : s === "REJECTED" ? <Badge tone="bad">Rejected</Badge> : s === "DRAFT" ? <Badge tone="neutral">Draft</Badge> : <Badge tone="warn">Pending review</Badge>;

export function ContentPanel({ c }: { c: CampaignDetail }) {
  const running = c.pipeline.contentStatus === "RUNNING";
  const { data, isLoading, error, refetch } = useCampaignContent(c.id, running);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const items = data ?? [];
  const selected = items.find((i) => i.id === selectedId) ?? items[0] ?? null;

  if (error) return <ErrorState error={error} onRetry={() => refetch()} />;
  if (isLoading) return <Skeleton rows={10} height={20} />;
  if (items.length === 0) {
    return (
      <Panel>
        <EmptyState icon={FileText} title={running ? "Writing the campaign email…" : "No email yet"}>
          {running
            ? "The AI is writing the email from your product, offer and audience details. This usually takes under a minute."
            : c.pipeline.contentStatus === "FAILED"
              ? "Generation failed. Use “Generate content again” above."
              : "Start AI processing to generate the email."}
        </EmptyState>
      </Panel>
    );
  }

  const required = new Set(c.readiness.requiredContent);
  return (
    <div className="mk-split">
      <div className="mk-stack">
        <Panel title="Campaign email" subtitle={`${c.readiness.approvedContent.length} of ${c.readiness.requiredContent.length} required versions approved`} flush>
          <ul style={{ listStyle: "none", margin: 0, padding: "0 8px 8px" }}>
            {items.map((i) => {
              const active = selected?.id === i.id;
              return (
                <li key={i.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(i.id)}
                    aria-current={active || undefined}
                    style={{
                      width: "100%", display: "flex", gap: 10, alignItems: "center", padding: "9px 8px", border: 0, borderRadius: 7,
                      background: active ? "var(--mk-primary-tint)" : "transparent", cursor: "pointer", textAlign: "left",
                    }}
                  >
                    <Mail size={16} aria-hidden color={active ? "#185FA5" : "#4F5157"} />
                    <span style={{ flex: 1, minWidth: 0 }}>
                      <span style={{ fontWeight: 600, display: "block" }}>
                        {c.abTest?.enabled || i.variant === "B" ? `Email · variant ${i.variant}` : "Email"}
                      </span>
                      <span className="mk-sub">{i.validationWarnings.length ? `${i.validationWarnings.length} note${i.validationWarnings.length > 1 ? "s" : ""}` : i.provenance.replace("_", " ").toLowerCase()}</span>
                    </span>
                    {statusBadge(i.status)}
                  </button>
                  {!required.has(`${i.channel}:${i.variant}`) && <div className="mk-sub" style={{ padding: "0 8px 4px 34px" }}>Not required for launch</div>}
                </li>
              );
            })}
          </ul>
        </Panel>

        <Panel title="Campaign information" subtitle="The only facts the AI may use">
          <KeyValues
            rows={[
              ["Product", c.productName],
              ["Objective", c.objective],
              ["Offer", [c.offer, c.discount].filter(Boolean).join(" · ")],
              ["Pricing", c.pricingInfo],
              ["Call-to-action", c.contentConfig.cta || c.callToAction],
              ["Landing page", c.landingPageUrl],
              ["Sender", [c.contentConfig.senderName, c.contentConfig.senderEmail].filter(Boolean).join(" · ")],
              ["Tone", [c.contentConfig.tone, c.contentConfig.writingStyle].filter(Boolean).join(" · ")],
              ["Language", c.contentConfig.language],
              ["Personalization", c.contentConfig.personalization === false ? "Same for everyone" : "Merge fields per lead"],
            ]}
          />
        </Panel>
      </div>

      {selected && <ContentEditor key={selected.id} c={c} item={selected} locked={running || !["PENDING_LEAD_APPROVAL", "PENDING_CONTENT_APPROVAL", "READY_TO_LAUNCH"].includes(c.status)} running={running} />}
    </div>
  );
}

function ContentEditor({ c, item, locked, running }: { c: CampaignDetail; item: ContentItem; locked: boolean; running: boolean }) {
  const update = useUpdateContent();
  const decide = useContentDecision();
  const generate = useGenerateContent();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Record<string, string>>(item.fields);
  const [ask, setAsk] = useState<null | "reject" | "regenerate" | "approve">(null);
  useEffect(() => setDraft(item.fields), [item.fields]);

  const fields = channelFields[item.channel];
  const dirty = useMemo(() => fields.some((f) => (draft[f.key] ?? "") !== (item.fields[f.key] ?? "")), [draft, item.fields, fields]);
  const tooLong = fields.find((f) => (draft[f.key] ?? "").length > f.limit);
  const canApprove = c.viewerIsApprover && !locked;

  const save = (asDraft: boolean) =>
    update.mutate(
      { id: c.id, contentId: item.id, fields: draft, draft: asDraft },
      {
        onSuccess: () => {
          toast.success(asDraft ? "Draft saved" : "Changes saved and sent for review");
          setEditing(false);
        },
        onError: (e) => toast.error("Couldn't save", { description: errMsg(e) }),
      }
    );

  const act = (note: string) => {
    if (ask === "regenerate") {
      generate.mutate(
        { id: c.id, channels: item.variant === "A" ? [item.channel] : undefined, variant: item.variant, instructions: note || undefined },
        { onSuccess: () => { toast.success(`Regenerating email variant ${item.variant}`); setAsk(null); }, onError: (e) => toast.error("Couldn't regenerate", { description: errMsg(e) }) }
      );
      return;
    }
    decide.mutate(
      { id: c.id, contentId: item.id, approve: ask === "approve", note: note || undefined },
      {
        onSuccess: () => { toast.success(ask === "approve" ? "Content approved" : "Content rejected"); setAsk(null); },
        onError: (e) => toast.error("Decision not saved", { description: errMsg(e) }),
      }
    );
  };

  return (
    <Panel
      title={
        <span className="mk-row" style={{ gap: 8 }}>
          Email
          {(c.abTest?.enabled || item.variant === "B") && <span className="mk-muted">· variant {item.variant}</span>}
          {statusBadge(item.status)}
        </span>
      }
      subtitle={
        <span className="mk-row" style={{ gap: 6, marginTop: 4 }}>
          <ProvenanceTag provenance={item.provenance} />
          {item.status === "APPROVED" && <TransparencyTag kind="HUMAN_APPROVED" title={item.reviewedBy ? `Approved by ${item.reviewedBy} ${fmtDateTime(item.reviewedAt)}` : undefined} />}
          <span className="mk-sub">
            {item.modelName ? `${item.modelName} · ` : ""}
            generated {fmtNum(item.generationCount)}× · updated {fmtDateTime(item.updatedAt)}
          </span>
        </span>
      }
      actions={
        editing ? (
          <>
            <Btn variant="ghost" icon={X} onClick={() => { setDraft(item.fields); setEditing(false); }}>Discard</Btn>
            <Btn icon={Save} disabled={!dirty || !!tooLong} busy={update.isPending} onClick={() => save(true)}>Save draft</Btn>
            <Btn variant="primary" icon={Check} disabled={!!tooLong} busy={update.isPending} onClick={() => save(false)}>Save for review</Btn>
          </>
        ) : (
          <>
            <Btn icon={PencilLine} disabled={locked} onClick={() => setEditing(true)}>Edit</Btn>
            <Btn variant="ai" icon={RefreshCw} disabled={locked} busy={running} onClick={() => setAsk("regenerate")}>Regenerate</Btn>
            {item.status !== "REJECTED" && <Btn variant="danger" icon={ThumbsDown} disabled={!canApprove} onClick={() => setAsk("reject")}>Reject</Btn>}
            {item.status !== "APPROVED" && <Btn variant="ok" icon={ThumbsUp} disabled={!canApprove || item.status === "DRAFT"} title={item.status === "DRAFT" ? "Save the draft for review first" : undefined} onClick={() => setAsk("approve")}>Approve</Btn>}
          </>
        )
      }
    >
      <div className="mk-stack">
        {item.validationWarnings.length > 0 && (
          <Callout tone="warn" title="Check before approving">
            <ul style={{ margin: "2px 0 0", paddingLeft: 18 }}>
              {item.validationWarnings.map((w) => <li key={w}>{w}</li>)}
            </ul>
          </Callout>
        )}
        {item.status === "REJECTED" && item.reviewNote && <Callout tone="bad" title={`Rejected by ${item.reviewedBy ?? "a reviewer"}`}>{item.reviewNote}</Callout>}
        {item.provenance === "AI_GENERATED" && !editing && (
          <div className="mk-sub">
            <TransparencyTag kind="AI" /> Written by the AI from your inputs and checked automatically for figures, links and claims that weren't in the campaign details.
          </div>
        )}

        {editing ? (
          <div className="mk-stack" style={{ gap: 12 }}>
            {fields.map((f) => {
              const v = draft[f.key] ?? "";
              const over = v.length > f.limit;
              return (
                <label key={f.key} className="mk-field">
                  <span className="mk-label" style={{ justifyContent: "space-between", display: "flex" }}>
                    {f.label}
                    <span className={over ? "mk-error" : "mk-help"}>{v.length}/{f.limit}</span>
                  </span>
                  {f.help && <span className="mk-help">{f.help}</span>}
                  {f.multiline ? (
                    <textarea className="mk-textarea" rows={f.key === "body" ? 12 : 4} value={v} aria-invalid={over || undefined} onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))} />
                  ) : (
                    <input className="mk-input" value={v} aria-invalid={over || undefined} onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))} />
                  )}
                </label>
              );
            })}
            <p className="mk-help" style={{ margin: 0 }}>
              Personalization: <code>{"{{first_name}}"}</code>, <code>{"{{company_name}}"}</code> and <code>{"{{product_name}}"}</code> are filled in
              per lead when sending. Without a value they become “there”, “your company” and the product name.
            </p>
          </div>
        ) : (
          <EmailPreviewPane c={c} item={item} />
        )}
      </div>

      {ask && (
        <ConfirmDialog
          title={ask === "approve" ? "Approve this email?" : ask === "reject" ? "Reject this email?" : `Regenerate email variant ${item.variant}?`}
          body={
            ask === "approve"
              ? "Only the approved version is sent to approved leads. It is locked once the campaign launches."
              : ask === "reject"
                ? "Say what's wrong. Your note is kept with the content and helps when regenerating."
                : "The AI writes a new version from your campaign inputs, replacing the current text (including any edits). Optional feedback guides the rewrite."
          }
          confirmLabel={ask === "approve" ? "Approve" : ask === "reject" ? "Reject" : "Regenerate"}
          tone={ask === "approve" ? "ok" : ask === "reject" ? "danger" : "primary"}
          note={ask === "approve" ? "Note (optional)" : ask === "reject" ? "Reason" : "Feedback for the AI (optional)"}
          noteRequired={ask === "reject"}
          busy={decide.isPending || generate.isPending}
          onConfirm={act}
          onCancel={() => setAsk(null)}
        />
      )}
    </Panel>
  );
}

/* ---------------- Preview: rendered by the server, exactly as sent ---------------- */

function EmailPreviewPane({ c, item }: { c: CampaignDetail; item: ContentItem }) {
  const [mode, setMode] = useState<"html" | "text">("html");
  const [leadId, setLeadId] = useState<string>("");
  const leads = useCampaignLeads(c.id, false);
  const approved = (leads.data ?? []).filter((l) => l.approvalStatus === "APPROVED").slice(0, 50);
  const preview = useEmailPreview(c.id, item.id, item.updatedAt, leadId || null);

  return (
    <div className="mk-stack" style={{ gap: 10 }}>
      <div className="mk-row" style={{ justifyContent: "space-between" }}>
        <Segmented label="Preview format" value={mode} onChange={setMode} options={[{ value: "html", label: "HTML" }, { value: "text", label: "Plain text" }]} />
        <select className="mk-select" aria-label="Preview for" value={leadId} onChange={(e) => setLeadId(e.target.value)} style={{ maxWidth: 260 }}>
          <option value="">Sample recipient</option>
          {approved.map((l) => <option key={l.leadId} value={l.leadId}>{l.leadName}{l.company ? ` · ${l.company}` : ""}</option>)}
        </select>
      </div>
      {preview.error ? (
        <ErrorState error={preview.error} onRetry={() => preview.refetch()} />
      ) : !preview.data ? (
        <Skeleton rows={8} />
      ) : (
        <div className="mk-preview-email">
          <div className="mk-preview-email__meta">
            <span className="mk-muted">From</span><span>{preview.data.from}</span>
            {preview.data.replyTo && (<><span className="mk-muted">Reply-to</span><span>{preview.data.replyTo}</span></>)}
            <span className="mk-muted">To</span><span>{preview.data.to}{preview.data.sampleLead ? " (sample)" : ""}</span>
            <span className="mk-muted">Subject</span><strong>{preview.data.subject || "—"}</strong>
            {preview.data.preheader && (<><span className="mk-muted">Preview</span><span className="mk-sub">{preview.data.preheader}</span></>)}
          </div>
          {mode === "html" ? (
            // Sandboxed with no permissions: the email can't run script or navigate this page.
            <iframe title="Email preview" sandbox="" srcDoc={preview.data.html} style={{ width: "100%", height: 560, border: 0, background: "#f4f5f7", display: "block" }} />
          ) : (
            <div className="mk-preview-email__body">{preview.data.text}</div>
          )}
        </div>
      )}
      {preview.data && preview.data.notes.length > 0 && (
        <ul className="mk-sub" style={{ margin: 0, paddingLeft: 18 }}>
          {preview.data.notes.map((n) => <li key={n}>{n}</li>)}
        </ul>
      )}
      <div className="mk-sub">{fmtNum(c.readiness.approvedLeads)} approved leads will receive the approved version.</div>
    </div>
  );
}
