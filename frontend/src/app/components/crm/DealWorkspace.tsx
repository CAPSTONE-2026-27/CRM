import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../../tokens";
import { Badge, Button } from "./ui";
import { DealScoreCard, formatWhen, scoreColor } from "./DealScoreCard";
import { MeetingOutputForm } from "./MeetingOutputForm";
import { ParameterViewer } from "./ParameterViewer";
import { ManagerReviewPanel } from "./ManagerReviewPanel";
import { useAuth } from "../../lib/auth";
import {
  useDealOnboarding,
  useDealWorkspace,
  useUpdateDealStage,
  useUpdateOnboarding,
} from "../../lib/queries";
import { DEAL_FLOW_STEPS, DEAL_STAGE_LABELS, type DealStage, type MeetingOutputDetail } from "../../lib/types";

/*
 * The deal workflow, end to end, for one opportunity.
 *
 * Laid out as the workflow it is: a progress tracker across the top, the live
 * score card on the right where it stays visible, and the working area on the
 * left switching between recording a meeting, inspecting what the models made
 * of it, and the manager's decision.
 *
 * The history tab is deliberately not a list of summaries — every meeting keeps
 * its own parameters, features and prediction, so a deal's progression can be
 * read back meeting by meeting rather than only as a final number.
 */

type Tab = "meeting" | "analysis" | "review" | "history";

const TABS: { id: Tab; label: string }[] = [
  { id: "meeting", label: "Meeting output" },
  { id: "analysis", label: "AI analysis" },
  { id: "review", label: "Manager review" },
  { id: "history", label: "History" },
];

export function DealWorkspace({ dealId, onClose }: { dealId: string; onClose: () => void }) {
  const { data: workspace, isLoading } = useDealWorkspace(dealId);
  const { data: onboarding } = useDealOnboarding(dealId);
  const { user } = useAuth();
  const [tab, setTab] = useState<Tab>("meeting");
  const [selectedMeetingId, setSelectedMeetingId] = useState<string | null>(null);

  const canReview = user?.role === "ADMIN" || user?.role === "MANAGER";
  const meetings = workspace?.meetings ?? [];
  // Newest first from the API, so the head is the current reading.
  const selected = meetings.find((m) => m.id === selectedMeetingId) ?? meetings[0] ?? null;

  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(0,0,0,0.35)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        zIndex: 50,
        padding: 20,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-label="Deal workspace"
        style={{
          background: colors.bgSecondary,
          borderRadius: 8,
          width: "min(1080px, 100%)",
          maxHeight: "90vh",
          overflowY: "auto",
          padding: 20,
        }}
      >
        {isLoading && <div style={{ fontSize: 12, color: colors.textSecondary }}>Loading the workspace…</div>}

        {workspace && (
          <>
            {/* ---- Header ---- */}
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 12, marginBottom: 14 }}>
              <div>
                <div style={{ fontSize: 16, fontWeight: 600 }}>{workspace.name}</div>
                <div style={{ fontSize: 11, color: colors.textSecondary, marginTop: 2 }}>
                  <span style={{ fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" }}>
                    {workspace.opportunityId ?? `Deal ${workspace.dealId}`}
                  </span>
                  {workspace.leadId && ` · from lead ${workspace.leadId}`}
                  {" · "}
                  {formatCurrency(workspace.value, workspace.currency)}
                </div>
              </div>
              <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                <Badge label={DEAL_STAGE_LABELS[workspace.stage] ?? workspace.stage} variant={stageVariant(workspace.stage)} />
                <Button label="Close" onClick={onClose} />
              </div>
            </div>

            <ProgressTracker stage={workspace.stage} />

            {workspace.meetingScheduledAt && (
              <div style={{ ...panelStyle, marginBottom: 14, padding: "10px 12px", fontSize: 12 }}>
                <span style={{ color: colors.textSecondary }}>Meeting scheduled </span>
                <span style={{ fontWeight: 500 }}>{formatWhen(workspace.meetingScheduledAt)}</span>
                {workspace.meetingMode && <span style={{ color: colors.textSecondary }}> · {workspace.meetingMode}</span>}
                {workspace.meetingParticipants && (
                  <div style={{ color: colors.textSecondary, marginTop: 2 }}>{workspace.meetingParticipants}</div>
                )}
              </div>
            )}

            <div style={{ display: "grid", gridTemplateColumns: "minmax(0, 1.75fr) minmax(280px, 1fr)", gap: 16, alignItems: "start" }}>
              {/* ---- Working area ---- */}
              <div style={panelStyle}>
                <div style={{ display: "flex", gap: 4, borderBottom: `0.5px solid ${colors.border}`, marginBottom: 16, flexWrap: "wrap" }}>
                  {TABS.map((t) => (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => setTab(t.id)}
                      aria-current={tab === t.id}
                      style={{
                        border: "none",
                        background: "transparent",
                        borderBottom: `2px solid ${tab === t.id ? colors.primary : "transparent"}`,
                        color: tab === t.id ? colors.primary : colors.textSecondary,
                        fontSize: 12,
                        fontWeight: tab === t.id ? 600 : 500,
                        padding: "8px 10px",
                        cursor: "pointer",
                        fontFamily: "inherit",
                      }}
                    >
                      {t.label}
                      {t.id === "history" && meetings.length > 0 ? ` (${meetings.length})` : ""}
                    </button>
                  ))}
                </div>

                {tab === "meeting" && (
                  <MeetingOutputForm
                    dealId={dealId}
                    nextVersion={meetings.length + 1}
                    onSubmitted={(result) => {
                      setSelectedMeetingId(result.id);
                      setTab("analysis");
                    }}
                  />
                )}

                {tab === "analysis" && <AnalysisTab meeting={selected} />}

                {tab === "review" && (
                  <ManagerReviewPanel
                    dealId={dealId}
                    prediction={workspace.latestPrediction}
                    reviews={workspace.reviews}
                    canReview={canReview}
                  />
                )}

                {tab === "history" && (
                  <HistoryTimeline
                    meetings={meetings}
                    selectedId={selected?.id ?? null}
                    onSelect={(id) => {
                      setSelectedMeetingId(id);
                      setTab("analysis");
                    }}
                  />
                )}
              </div>

              {/* ---- Score card + closing ---- */}
              <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
                <DealScoreCard prediction={workspace.latestPrediction} />
                <ClosePanel
                  dealId={dealId}
                  stage={workspace.stage}
                  closingReason={workspace.closingReason}
                  closedAt={workspace.closedAt}
                />
                {onboarding && <OnboardingPanel dealId={dealId} onboarding={onboarding} />}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------- progress tracker */

function ProgressTracker({ stage }: { stage: DealStage }) {
  const lost = stage === "CLOSED_LOST";
  // A lost deal has no position on the winning path, so the tracker is shown
  // fully spent in red rather than pretending it stalled at some step.
  const currentIndex = lost ? DEAL_FLOW_STEPS.length - 1 : Math.max(DEAL_FLOW_STEPS.indexOf(stage), 0);

  return (
    <div style={{ ...panelStyle, padding: "14px 16px", marginBottom: 14 }}>
      <div style={{ display: "flex", alignItems: "center" }}>
        {DEAL_FLOW_STEPS.map((step, i) => {
          const done = i < currentIndex;
          const active = i === currentIndex;
          const color = lost ? colors.danger : done || active ? colors.primary : colors.border;
          return (
            <div key={step} style={{ display: "flex", alignItems: "center", flex: i === DEAL_FLOW_STEPS.length - 1 ? "0 0 auto" : 1 }}>
              <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 5, flexShrink: 0 }}>
                <div
                  style={{
                    width: 22,
                    height: 22,
                    borderRadius: 22,
                    border: `2px solid ${color}`,
                    background: done || active || lost ? color : "#FFFFFF",
                    color: done || active || lost ? "#FFFFFF" : colors.textTertiary,
                    fontSize: 10,
                    fontWeight: 600,
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                  }}
                >
                  {done ? "✓" : i + 1}
                </div>
                <span
                  style={{
                    fontSize: 10,
                    color: active ? colors.textPrimary : colors.textSecondary,
                    fontWeight: active ? 600 : 400,
                    textAlign: "center",
                    maxWidth: 84,
                    lineHeight: 1.3,
                  }}
                >
                  {lost && i === DEAL_FLOW_STEPS.length - 1 ? "Closed lost" : DEAL_STAGE_LABELS[step]}
                </span>
              </div>
              {i < DEAL_FLOW_STEPS.length - 1 && (
                <div style={{ flex: 1, height: 2, background: lost || done ? color : colors.border, margin: "0 6px", marginBottom: 18 }} />
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------ analysis tab */

function AnalysisTab({ meeting }: { meeting: MeetingOutputDetail | null }) {
  if (!meeting) {
    return (
      <div style={{ fontSize: 12, color: colors.textSecondary }}>
        No meeting has been analysed yet. Record one under Meeting output and the model will extract the business
        signals from it.
      </div>
    );
  }

  const analysis = meeting.analysis;

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 10, marginBottom: 12, flexWrap: "wrap" }}>
        <div>
          <div style={{ fontSize: 13, fontWeight: 600 }}>
            Meeting {meeting.version} · {formatDate(meeting.meetingDate)} at {meeting.meetingTime}
          </div>
          <div style={{ fontSize: 11, color: colors.textSecondary }}>
            {analysis?.modelVersion ?? "—"}
            {analysis?.latencyMs != null && ` · analysed in ${(analysis.latencyMs / 1000).toFixed(1)}s`}
          </div>
        </div>
        {analysis && (
          <Badge
            label={analysis.status === "SUCCEEDED" ? "Model analysis" : "Fallback analysis"}
            variant={analysis.status === "SUCCEEDED" ? "purple" : "amber"}
          />
        )}
      </div>

      {analysis?.status === "DEGRADED" && (
        <div
          style={{
            background: colors.warningLight,
            border: `0.5px solid ${colors.warning}`,
            borderRadius: 6,
            padding: "8px 10px",
            fontSize: 11,
            color: colors.warning,
            marginBottom: 12,
          }}
        >
          {analysis.errorMessage ?? "The analysis model was unavailable, so these signals came from keyword matching."}
        </div>
      )}

      <ParameterViewer parameters={meeting.parameters} featureSet={meeting.featureSet} />

      {meeting.prediction && (
        <div style={{ marginTop: 16, fontSize: 12, color: colors.textSecondary }}>
          This reading produced a deal score of{" "}
          <span style={{ color: scoreColor(meeting.prediction.dealScore), fontWeight: 600 }}>
            {meeting.prediction.dealScore.toFixed(1)}
          </span>
          .
        </div>
      )}
    </div>
  );
}

/* --------------------------------------------------------- history timeline */

function HistoryTimeline({
  meetings,
  selectedId,
  onSelect,
}: {
  meetings: MeetingOutputDetail[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  if (!meetings.length) {
    return <div style={{ fontSize: 12, color: colors.textSecondary }}>No meetings recorded for this opportunity yet.</div>;
  }

  return (
    <div>
      <div style={{ fontSize: 11, color: colors.textSecondary, marginBottom: 12 }}>
        Every submission is kept. Scores are shown oldest to newest so the deal's progression is readable.
      </div>

      {[...meetings].reverse().map((m, i, ordered) => {
        const score = m.prediction?.dealScore;
        const previous = ordered[i - 1]?.prediction?.dealScore;
        const delta = score != null && previous != null ? score - previous : null;

        return (
          <button
            key={m.id}
            type="button"
            onClick={() => onSelect(m.id)}
            style={{
              display: "block",
              width: "100%",
              textAlign: "left",
              border: `1px solid ${selectedId === m.id ? colors.primary : colors.border}`,
              background: selectedId === m.id ? colors.primaryLight : "#FFFFFF",
              borderRadius: 6,
              padding: "10px 12px",
              marginBottom: 8,
              cursor: "pointer",
              fontFamily: "inherit",
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", gap: 10, alignItems: "center", marginBottom: 4 }}>
              <span style={{ fontSize: 12, fontWeight: 600 }}>
                Meeting {m.version} · {formatDate(m.meetingDate)}
              </span>
              <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
                {delta != null && delta !== 0 && (
                  <span style={{ fontSize: 11, fontWeight: 600, color: delta > 0 ? colors.success : colors.danger }}>
                    {delta > 0 ? "+" : ""}
                    {delta.toFixed(1)}
                  </span>
                )}
                <span style={{ fontSize: 13, fontWeight: 600, color: scoreColor(score) }}>
                  {score == null ? "—" : score.toFixed(1)}
                </span>
              </span>
            </div>
            {m.meetingSummary && (
              <div
                style={{
                  fontSize: 11,
                  color: colors.textSecondary,
                  lineHeight: 1.45,
                  display: "-webkit-box",
                  WebkitLineClamp: 2,
                  WebkitBoxOrient: "vertical",
                  overflow: "hidden",
                }}
              >
                {m.meetingSummary}
              </div>
            )}
          </button>
        );
      })}
    </div>
  );
}

/* ------------------------------------------------------------ close + onboarding */

function ClosePanel({
  dealId,
  stage,
  closingReason,
  closedAt,
}: {
  dealId: string;
  stage: DealStage;
  closingReason: string | null | undefined;
  closedAt: string | null | undefined;
}) {
  const [reason, setReason] = useState("");
  const updateStage = useUpdateDealStage();
  const closed = stage === "CLOSED_WON" || stage === "CLOSED_LOST";

  if (closed) {
    return (
      <div style={panelStyle}>
        <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
          {stage === "CLOSED_WON" ? "Closed won" : "Closed lost"}
        </div>
        <div style={{ fontSize: 11, color: colors.textSecondary }}>{formatWhen(closedAt)}</div>
        {closingReason && <div style={{ fontSize: 12, marginTop: 6, whiteSpace: "pre-wrap" }}>{closingReason}</div>}
      </div>
    );
  }

  const close = (target: "CLOSED_WON" | "CLOSED_LOST") => {
    if (!reason.trim()) {
      toast.error("Add a closing reason", { description: "It is what makes win/loss reporting worth reading." });
      return;
    }
    updateStage.mutate(
      { id: dealId, stage: target, closingReason: reason.trim() },
      {
        onSuccess: () => {
          toast.success(target === "CLOSED_WON" ? "Deal won — onboarding started" : "Deal marked as lost");
          setReason("");
        },
        onError: (err) => toast.error("Couldn't close the deal", { description: err instanceof Error ? err.message : undefined }),
      }
    );
  };

  return (
    <div style={panelStyle}>
      <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>Final decision</div>
      <textarea
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        rows={3}
        placeholder="Closing reason — why this was won or lost"
        style={{
          width: "100%",
          border: `0.5px solid ${colors.border}`,
          borderRadius: 6,
          padding: "8px 10px",
          fontSize: 12,
          resize: "vertical",
          outline: "none",
          fontFamily: "inherit",
          marginBottom: 10,
        }}
      />
      <div style={{ display: "flex", gap: 8 }}>
        <Button label="Closed won" variant="primary" onClick={() => close("CLOSED_WON")} disabled={updateStage.isPending} />
        <Button label="Closed lost" onClick={() => close("CLOSED_LOST")} disabled={updateStage.isPending} />
      </div>
      <div style={{ fontSize: 10, color: colors.textTertiary, marginTop: 8 }}>
        Winning the deal starts customer onboarding automatically.
      </div>
    </div>
  );
}

function OnboardingPanel({
  dealId,
  onboarding,
}: {
  dealId: string;
  onboarding: { status: string; initiatedAt: string; completedAt?: string | null; notes?: string | null };
}) {
  const update = useUpdateOnboarding(dealId);
  const statuses = ["INITIATED", "IN_PROGRESS", "COMPLETED", "CANCELLED"];

  return (
    <div style={panelStyle}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8, marginBottom: 8 }}>
        <span style={{ fontSize: 13, fontWeight: 600 }}>Customer onboarding</span>
        <Badge
          label={titleCase(onboarding.status)}
          variant={onboarding.status === "COMPLETED" ? "green" : onboarding.status === "CANCELLED" ? "red" : "blue"}
        />
      </div>
      <div style={{ fontSize: 11, color: colors.textSecondary, marginBottom: 10 }}>
        Started {formatWhen(onboarding.initiatedAt)}
        {onboarding.completedAt && ` · completed ${formatWhen(onboarding.completedAt)}`}
      </div>
      <select
        value={onboarding.status}
        onChange={(e) =>
          update.mutate(
            { status: e.target.value },
            { onError: (err) => toast.error("Couldn't update onboarding", { description: err instanceof Error ? err.message : undefined }) }
          )
        }
        style={{
          width: "100%",
          border: `0.5px solid ${colors.border}`,
          borderRadius: 6,
          padding: "7px 10px",
          fontSize: 12,
          background: "#FFFFFF",
          outline: "none",
        }}
      >
        {statuses.map((s) => (
          <option key={s} value={s}>
            {titleCase(s)}
          </option>
        ))}
      </select>
    </div>
  );
}

/* ------------------------------------------------------------------ helpers */

const panelStyle: React.CSSProperties = {
  background: "#FFFFFF",
  border: `0.5px solid ${colors.border}`,
  borderRadius: 8,
  padding: 16,
};

function stageVariant(stage: DealStage): "green" | "red" | "blue" | "amber" | "purple" {
  if (stage === "CLOSED_WON") return "green";
  if (stage === "CLOSED_LOST") return "red";
  if (stage === "NEGOTIATION" || stage === "PROPOSAL") return "amber";
  return "blue";
}

function titleCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, " ");
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

function formatCurrency(value: string | number, currency: string): string {
  const n = typeof value === "number" ? value : parseFloat(value);
  if (!Number.isFinite(n)) return String(value);
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: currency || "INR", maximumFractionDigits: 0 }).format(n);
}
