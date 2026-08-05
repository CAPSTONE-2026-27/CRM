import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../../tokens";
import { Badge, Button } from "./ui";
import { useReviewDeal } from "../../lib/queries";
import { formatWhen } from "./DealScoreCard";
import type { DealPrediction, ManagerDecision, ManagerReview } from "../../lib/types";

/*
 * Deal flow step 10 — sales manager decision support.
 *
 * The manager sees what the model recommended and either backs it, rejects it,
 * or substitutes their own action. All three are recorded: a rejection that
 * leaves no trace is indistinguishable from nobody having looked, and the
 * disagreements are the interesting data once the model is retrained.
 */

const decisionMeta: Record<ManagerDecision, { label: string; variant: "green" | "red" | "amber"; blurb: string }> = {
  APPROVED: { label: "Approved", variant: "green", blurb: "Back the model's recommendation and proceed." },
  REJECTED: { label: "Rejected", variant: "red", blurb: "Do not act on this recommendation." },
  OVERRIDDEN: { label: "Overridden", variant: "amber", blurb: "Replace it with a different action." },
};

export function ManagerReviewPanel({
  dealId,
  prediction,
  reviews,
  canReview,
}: {
  dealId: string;
  prediction: DealPrediction | null | undefined;
  reviews: ManagerReview[];
  canReview: boolean;
}) {
  const [decision, setDecision] = useState<ManagerDecision>("APPROVED");
  const [overriddenAction, setOverriddenAction] = useState("");
  const [comments, setComments] = useState("");
  const review = useReviewDeal(dealId);

  const submit = () => {
    if (decision === "OVERRIDDEN" && !overriddenAction.trim()) {
      toast.error("Say what should happen instead", {
        description: "An override needs a replacement action.",
      });
      return;
    }

    review.mutate(
      {
        decision,
        overriddenAction: decision === "OVERRIDDEN" ? overriddenAction.trim() : undefined,
        comments: comments.trim() || undefined,
      },
      {
        onSuccess: () => {
          toast.success(`Recommendation ${decisionMeta[decision].label.toLowerCase()}`);
          setOverriddenAction("");
          setComments("");
        },
        onError: (err) =>
          toast.error("Couldn't record the review", {
            description: err instanceof Error ? err.message : undefined,
          }),
      }
    );
  };

  return (
    <div>
      {prediction ? (
        <div
          style={{
            border: `0.5px solid ${colors.border}`,
            borderRadius: 6,
            padding: "10px 12px",
            marginBottom: 14,
            background: colors.bgSecondary,
          }}
        >
          <div style={{ fontSize: 10, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 3 }}>
            The model recommends
          </div>
          <div style={{ fontSize: 13, fontWeight: 500 }}>{prediction.recommendedAction ?? "—"}</div>
          <div style={{ fontSize: 11, color: colors.textSecondary, marginTop: 3 }}>
            Based on a score of {prediction.dealScore.toFixed(1)}
            {prediction.riskLevel ? ` and ${prediction.riskLevel.toLowerCase()} risk` : ""}.
          </div>
        </div>
      ) : (
        <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 14 }}>
          There is nothing to review yet — a meeting output has to be analysed first.
        </div>
      )}

      {canReview && prediction && (
        <>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 12 }}>
            {(Object.keys(decisionMeta) as ManagerDecision[]).map((option) => {
              const active = decision === option;
              return (
                <button
                  key={option}
                  type="button"
                  onClick={() => setDecision(option)}
                  aria-pressed={active}
                  style={{
                    flex: "1 1 150px",
                    textAlign: "left",
                    border: `1px solid ${active ? colors.primary : colors.border}`,
                    background: active ? colors.primaryLight : "#FFFFFF",
                    borderRadius: 6,
                    padding: "8px 10px",
                    cursor: "pointer",
                    fontFamily: "inherit",
                  }}
                >
                  <div style={{ fontSize: 12, fontWeight: 600, color: active ? colors.primary : colors.textPrimary }}>
                    {decisionMeta[option].label}
                  </div>
                  <div style={{ fontSize: 10, color: colors.textSecondary, marginTop: 2 }}>
                    {decisionMeta[option].blurb}
                  </div>
                </button>
              );
            })}
          </div>

          {decision === "OVERRIDDEN" && (
            <input
              value={overriddenAction}
              onChange={(e) => setOverriddenAction(e.target.value)}
              placeholder="What should happen instead"
              maxLength={200}
              style={inputStyle}
            />
          )}

          <textarea
            value={comments}
            onChange={(e) => setComments(e.target.value)}
            rows={3}
            placeholder="Comments for the sales executive (optional)"
            style={{ ...inputStyle, resize: "vertical", lineHeight: 1.5 }}
          />

          <div style={{ display: "flex", justifyContent: "flex-end", marginTop: 10, marginBottom: 18 }}>
            <Button
              label={review.isPending ? "Recording…" : "Record decision"}
              variant="primary"
              onClick={submit}
              disabled={review.isPending}
            />
          </div>
        </>
      )}

      {!canReview && (
        <div style={{ fontSize: 11, color: colors.textTertiary, marginBottom: 14 }}>
          Only an administrator or sales manager can approve, reject or override a recommendation.
        </div>
      )}

      <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 8 }}>
        Review history ({reviews.length})
      </div>
      {reviews.length === 0 ? (
        <div style={{ fontSize: 12, color: colors.textTertiary }}>No decisions recorded yet.</div>
      ) : (
        <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
          {reviews.map((r, i) => (
            <div key={r.id} style={{ padding: "10px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
              <div style={{ display: "flex", justifyContent: "space-between", gap: 10, alignItems: "center", marginBottom: 4 }}>
                <Badge label={decisionMeta[r.decision]?.label ?? r.decision} variant={decisionMeta[r.decision]?.variant ?? "blue"} />
                <span style={{ color: colors.textTertiary, fontSize: 11 }}>{formatWhen(r.createdAt)}</span>
              </div>
              {r.recommendedAction && (
                <div style={{ color: colors.textSecondary }}>
                  Model recommended: <span style={{ color: colors.textPrimary }}>{r.recommendedAction}</span>
                </div>
              )}
              {r.overriddenAction && (
                <div style={{ color: colors.textSecondary }}>
                  Replaced with: <span style={{ color: colors.textPrimary, fontWeight: 500 }}>{r.overriddenAction}</span>
                </div>
              )}
              {r.comments && <div style={{ marginTop: 4, whiteSpace: "pre-wrap" }}>{r.comments}</div>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  width: "100%",
  border: `0.5px solid ${colors.border}`,
  borderRadius: 6,
  padding: "8px 10px",
  fontSize: 12,
  color: colors.textPrimary,
  background: "#FFFFFF",
  outline: "none",
  fontFamily: "inherit",
  marginBottom: 8,
};
