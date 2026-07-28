import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../../tokens";
import { Badge, Button, Field } from "./ui";
import { useAnalyzeMeeting, useLeadMeetings, useSaveMeeting } from "../../lib/queries";
import type { Lead, MeetingAnalysis } from "../../lib/types";

/*
 * Lead Output module — records the outcome of a customer meeting.
 *
 * Two phases: the rep fills in the meeting details and generates an AI summary
 * plus a re-evaluated score (nothing is persisted yet), reviews and optionally
 * edits that summary, then saves. Saving appends a new history row; earlier
 * meetings are never overwritten.
 */

const sectionLabel: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  color: colors.textSecondary,
  textTransform: "uppercase",
  letterSpacing: 0.4,
  marginBottom: 8,
};

const textareaStyle: React.CSSProperties = {
  width: "100%",
  border: `0.5px solid ${colors.border}`,
  borderRadius: 6,
  padding: "8px 10px",
  fontSize: 12,
  resize: "vertical",
  outline: "none",
  fontFamily: "inherit",
  lineHeight: 1.5,
};

const readOnlyRow: React.CSSProperties = {
  display: "flex",
  justifyContent: "space-between",
  gap: 12,
  padding: "9px 12px",
  fontSize: 12,
};

function todayIso(): string {
  // Local date (not UTC) so the default matches the rep's calendar day.
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}

function nowHhMm(): string {
  const now = new Date();
  return `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" });
}

// "15:30" -> "3:30 PM", matching the summary format in the spec.
function formatTime(hhmm: string): string {
  const [h, m] = hhmm.split(":").map(Number);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return hhmm;
  const period = h >= 12 ? "PM" : "AM";
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return `${hour12}:${String(m).padStart(2, "0")} ${period}`;
}

function scoreTone(diff: number): { color: string; sign: string } {
  if (diff > 0) return { color: colors.success, sign: "+" };
  if (diff < 0) return { color: colors.danger, sign: "" };
  return { color: colors.textSecondary, sign: "" };
}

export function LeadOutputModal({ lead, onClose }: { lead: Lead; onClose: () => void }) {
  const [meetingDate, setMeetingDate] = useState(todayIso());
  const [meetingTime, setMeetingTime] = useState(nowHhMm());
  const [meetingOutput, setMeetingOutput] = useState("");

  const [analysis, setAnalysis] = useState<MeetingAnalysis | null>(null);
  const [summaryDraft, setSummaryDraft] = useState("");

  const analyze = useAnalyzeMeeting(lead.id);
  const saveMeeting = useSaveMeeting(lead.id);
  const { data: history, isLoading: historyLoading } = useLeadMeetings(lead.id);

  const busy = analyze.isPending || saveMeeting.isPending;

  const handleGenerate = () => {
    if (!meetingOutput.trim()) {
      toast.error("Add the meeting output before generating a summary");
      return;
    }
    analyze.mutate(
      { meetingDate, meetingTime, meetingOutput: meetingOutput.trim() },
      {
        onSuccess: (result) => {
          setAnalysis(result);
          setSummaryDraft(result.aiSummary);
          toast.success("Summary generated", { description: "Review and edit it before saving." });
        },
        onError: (err) =>
          toast.error("Couldn't generate the summary", {
            description: err instanceof Error ? err.message : "The AI model may be unavailable.",
          }),
      }
    );
  };

  const handleSave = () => {
    if (!analysis) return;
    if (!summaryDraft.trim()) {
      toast.error("Summary can't be empty");
      return;
    }
    saveMeeting.mutate(
      {
        meetingDate,
        meetingTime,
        meetingOutput: meetingOutput.trim(),
        aiSummary: summaryDraft.trim(),
        updatedScore: analysis.updatedScore,
        scoreLabel: analysis.scoreLabel,
        scoreChangeReason: analysis.reasons.join("; "),
      },
      {
        onSuccess: () => {
          toast.success("Meeting record saved", {
            description: `${lead.fullName}'s score is now ${analysis.updatedScore}.`,
          });
          onClose();
        },
        onError: (err) =>
          toast.error("Couldn't save the meeting record", {
            description: err instanceof Error ? err.message : undefined,
          }),
      }
    );
  };

  const tone = analysis ? scoreTone(analysis.scoreDifference) : null;

  return (
    <div
      onClick={busy ? undefined : onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 50, padding: 20 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ background: "#FFFFFF", borderRadius: 8, width: "min(620px, 100%)", maxHeight: "85vh", overflowY: "auto", padding: 20 }}
      >
        <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 2 }}>Lead output</div>
        <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 16 }}>
          Record what happened in the meeting — the AI re-scores the lead from your notes.
        </div>

        {/* ---- Lead information (read-only) ---- */}
        <div style={sectionLabel}>Lead information</div>
        <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 16 }}>
          <div style={{ ...readOnlyRow, background: "#FFFFFF" }}>
            <span style={{ color: colors.textSecondary }}>Lead ID</span>
            <span style={{ color: colors.textPrimary, fontWeight: 500, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", wordBreak: "break-all" }}>
              {lead.id}
            </span>
          </div>
          <div style={{ ...readOnlyRow, background: colors.bgSecondary }}>
            <span style={{ color: colors.textSecondary }}>Lead name</span>
            <span style={{ color: colors.textPrimary, fontWeight: 500 }}>
              {lead.fullName}
              {lead.company ? ` · ${lead.company}` : ""}
            </span>
          </div>
        </div>

        {/* ---- Meeting information ---- */}
        <div style={sectionLabel}>Meeting information</div>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12, marginBottom: 12 }}>
          <Field required label="Meeting date" type="date" value={meetingDate} onChange={setMeetingDate} />
          <Field required label="Meeting time" type="time" value={meetingTime} onChange={setMeetingTime} />
        </div>
        <label style={{ fontSize: 11, fontWeight: 500, color: colors.textSecondary, display: "block", marginBottom: 5 }}>
          Meeting output / discussion
          <span aria-hidden="true" style={{ color: colors.danger, marginLeft: 3 }}>*</span>
        </label>
        <textarea
          value={meetingOutput}
          onChange={(e) => setMeetingOutput(e.target.value)}
          placeholder="Capture everything discussed — customer requirements, questions asked, objections, budget, timeline, level of interest, competitors mentioned, next steps, and the overall outcome."
          style={{ ...textareaStyle, minHeight: 150 }}
        />

        <div style={{ display: "flex", justifyContent: "flex-end", marginTop: 12 }}>
          <Button
            label={analyze.isPending ? "Generating summary…" : analysis ? "Regenerate summary" : "Generate summary"}
            variant="primary"
            onClick={handleGenerate}
            disabled={busy}
          />
        </div>

        {analyze.isPending && (
          <div style={{ fontSize: 12, color: colors.textSecondary, textAlign: "center", padding: "12px 0" }}>
            Summarising the meeting and re-evaluating the score — this can take a moment…
          </div>
        )}

        {/* ---- AI summary + score re-evaluation ---- */}
        {analysis && !analyze.isPending && (
          <>
            <div style={{ height: 1, background: colors.border, margin: "18px 0" }} />

            <div style={sectionLabel}>AI summary</div>
            <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 10 }}>
              <div style={{ ...readOnlyRow, background: "#FFFFFF" }}>
                <span style={{ color: colors.textSecondary }}>Lead ID</span>
                <span style={{ fontWeight: 500, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", wordBreak: "break-all" }}>{analysis.leadId}</span>
              </div>
              <div style={{ ...readOnlyRow, background: colors.bgSecondary }}>
                <span style={{ color: colors.textSecondary }}>Meeting date</span>
                <span style={{ fontWeight: 500 }}>{formatDate(analysis.meetingDate)}</span>
              </div>
              <div style={{ ...readOnlyRow, background: "#FFFFFF" }}>
                <span style={{ color: colors.textSecondary }}>Meeting time</span>
                <span style={{ fontWeight: 500 }}>{formatTime(analysis.meetingTime)}</span>
              </div>
            </div>
            <textarea
              value={summaryDraft}
              onChange={(e) => setSummaryDraft(e.target.value)}
              style={{ ...textareaStyle, minHeight: 96 }}
            />
            <div style={{ fontSize: 11, color: colors.textSecondary, marginTop: 4, marginBottom: 16 }}>
              You can edit this summary before saving.
            </div>

            <div style={sectionLabel}>Lead score re-evaluation</div>
            <div
              style={{
                background: colors.aiLight,
                border: "0.5px solid #AFA9EC",
                borderRadius: 8,
                padding: 14,
                marginBottom: 16,
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: 16, flexWrap: "wrap", marginBottom: 12 }}>
                <div>
                  <div style={{ fontSize: 11, color: "#3C3489" }}>Previous score</div>
                  <div style={{ fontSize: 20, fontWeight: 600, color: colors.textSecondary }}>
                    {analysis.previousScore ?? "—"}
                  </div>
                </div>
                <div style={{ fontSize: 18, color: "#3C3489" }}>→</div>
                <div>
                  <div style={{ fontSize: 11, color: "#3C3489" }}>Updated score</div>
                  <div style={{ fontSize: 20, fontWeight: 600, color: colors.aiPurple }}>{analysis.updatedScore}</div>
                </div>
                {tone && (
                  <div>
                    <div style={{ fontSize: 11, color: "#3C3489" }}>Difference</div>
                    <div style={{ fontSize: 20, fontWeight: 600, color: tone.color }}>
                      {tone.sign}
                      {analysis.scoreDifference}
                    </div>
                  </div>
                )}
                <div style={{ marginLeft: "auto" }}>
                  <Badge
                    label={`${analysis.scoreLabel} lead`}
                    variant={analysis.scoreLabel === "Hot" ? "green" : analysis.scoreLabel === "Cold" ? "blue" : "amber"}
                  />
                </div>
              </div>

              {analysis.reasons.length > 0 && (
                <>
                  <div style={{ fontSize: 11, fontWeight: 600, color: "#3C3489", marginBottom: 4 }}>
                    Reason for score change
                  </div>
                  <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12, color: "#3C3489" }}>
                    {analysis.reasons.map((r, i) => (
                      <li key={i} style={{ marginBottom: 2 }}>{r}</li>
                    ))}
                  </ul>
                </>
              )}
            </div>
          </>
        )}

        {/* ---- Previous meetings for this lead ---- */}
        {!historyLoading && history && history.length > 0 && (
          <>
            <div style={sectionLabel}>Previous meetings ({history.length})</div>
            <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 16 }}>
              {history.map((m, i) => (
                <div key={m.id} style={{ padding: "10px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
                  <div style={{ display: "flex", justifyContent: "space-between", gap: 10, marginBottom: 3 }}>
                    <span style={{ fontWeight: 500 }}>
                      {formatDate(m.meetingDate)} · {formatTime(m.meetingTime)}
                    </span>
                    <span style={{ color: colors.textSecondary, whiteSpace: "nowrap" }}>
                      {m.previousScore ?? "—"} → {m.updatedScore ?? "—"}
                    </span>
                  </div>
                  <div style={{ color: colors.textSecondary, whiteSpace: "pre-wrap" }}>{m.aiSummary}</div>
                  {m.recordedBy?.fullName && (
                    <div style={{ color: colors.textTertiary, fontSize: 11, marginTop: 3 }}>
                      Recorded by {m.recordedBy.fullName}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </>
        )}

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8 }}>
          <Button label="Cancel" onClick={onClose} disabled={busy} />
          <Button
            label={saveMeeting.isPending ? "Saving…" : "Save meeting record"}
            variant="primary"
            onClick={handleSave}
            disabled={busy || !analysis}
          />
        </div>
      </div>
    </div>
  );
}
