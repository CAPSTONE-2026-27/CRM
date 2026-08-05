import { colors } from "../../tokens";
import { Badge } from "./ui";
import type { DealPrediction, RiskLevel, ScoreBand } from "../../lib/types";

/*
 * Deal flow step 9 — the score card.
 *
 * Shows four numbers that are easy to confuse, so each is labelled with what it
 * actually means: the score is the model's rating, the win probability is a
 * calibration of that score, the risk level is a separate read on what could go
 * wrong, and the confidence says how much meeting evidence any of it rests on.
 */

const bandColor: Record<ScoreBand, string> = {
  HIGH: colors.success,
  MEDIUM: colors.primary,
  LOW: colors.warning,
  "VERY LOW": colors.danger,
};

const riskVariant: Record<RiskLevel, "green" | "amber" | "red"> = {
  LOW: "green",
  MEDIUM: "amber",
  HIGH: "red",
};

export function scoreColor(score: number | null | undefined): string {
  if (score == null) return colors.textTertiary;
  if (score >= 75) return colors.success;
  if (score >= 50) return colors.primary;
  if (score >= 25) return colors.warning;
  return colors.danger;
}

export function DealScoreCard({ prediction }: { prediction: DealPrediction | null | undefined }) {
  if (!prediction) {
    return (
      <div style={cardStyle}>
        <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>Deal score</div>
        <div style={{ fontSize: 12, color: colors.textSecondary }}>
          Not scored yet. Submit a meeting output and the model will score this deal from what was discussed.
        </div>
      </div>
    );
  }

  const score = prediction.dealScore;
  const accent = scoreColor(score);
  const band = prediction.band ?? "VERY LOW";

  return (
    <div style={cardStyle}>
      <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 12, marginBottom: 14 }}>
        <div>
          <div style={{ fontSize: 13, fontWeight: 600 }}>Deal score</div>
          <div style={{ fontSize: 11, color: colors.textSecondary }}>
            {prediction.modelVersion ? `XGBoost v${prediction.modelVersion}` : "XGBoost"}
            {" · "}
            {formatWhen(prediction.predictedAt)}
          </div>
        </div>
        <div style={{ display: "flex", gap: 6, flexWrap: "wrap", justifyContent: "flex-end" }}>
          <Badge label={band} variant={band === "HIGH" ? "green" : band === "MEDIUM" ? "blue" : band === "LOW" ? "amber" : "red"} />
          {prediction.riskLevel && (
            <Badge label={`${titleCase(prediction.riskLevel)} risk`} variant={riskVariant[prediction.riskLevel]} />
          )}
        </div>
      </div>

      {/* Score, big, with the progress bar the spec asks for */}
      <div style={{ display: "flex", alignItems: "baseline", gap: 8, marginBottom: 6 }}>
        <span style={{ fontSize: 34, fontWeight: 600, color: accent, lineHeight: 1 }}>{score.toFixed(1)}</span>
        <span style={{ fontSize: 13, color: colors.textSecondary }}>/ 100</span>
      </div>
      <div style={{ height: 8, borderRadius: 20, background: colors.bgSecondary, overflow: "hidden", marginBottom: 16 }}>
        <div
          role="progressbar"
          aria-valuenow={Math.round(score)}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Deal score"
          style={{ width: `${clampPct(score)}%`, height: "100%", background: accent, borderRadius: 20, transition: "width 400ms ease" }}
        />
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(120px, 1fr))", gap: 12, marginBottom: 16 }}>
        <Stat
          label="Win probability"
          value={prediction.winProbability == null ? "—" : `${prediction.winProbability.toFixed(0)}%`}
          hint="Calibrated from the score"
        />
        <Stat
          label="Confidence"
          value={prediction.confidence == null ? "—" : `${(prediction.confidence * 100).toFixed(0)}%`}
          hint="How much of the meeting the model could read"
        />
        <Stat label="Risk level" value={prediction.riskLevel ? titleCase(prediction.riskLevel) : "—"} hint="Score plus explicit risk signals" />
      </div>

      <FactorList title="Key positive factors" items={prediction.positiveFactors} color={colors.success} />
      <FactorList title="Key negative factors" items={prediction.negativeFactors} color={colors.danger} />

      {prediction.recommendedAction && (
        <div
          style={{
            marginTop: 14,
            background: colors.aiLight,
            border: "0.5px solid #AFA9EC",
            borderRadius: 6,
            padding: "10px 12px",
          }}
        >
          <div style={{ fontSize: 10, fontWeight: 600, color: "#3C3489", textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 3 }}>
            Recommended next action
          </div>
          <div style={{ fontSize: 12, color: "#3C3489" }}>{prediction.recommendedAction}</div>
        </div>
      )}
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div>
      <div style={{ fontSize: 10, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4 }}>{label}</div>
      <div style={{ fontSize: 18, fontWeight: 600, color: colors.textPrimary, lineHeight: 1.3 }}>{value}</div>
      {hint && <div style={{ fontSize: 10, color: colors.textTertiary }}>{hint}</div>}
    </div>
  );
}

function FactorList({ title, items, color }: { title: string; items: string[]; color: string }) {
  if (!items.length) return null;
  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ fontSize: 10, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 5 }}>
        {title}
      </div>
      {items.map((item, i) => (
        <div key={i} style={{ display: "flex", gap: 7, alignItems: "flex-start", fontSize: 12, marginBottom: 3 }}>
          <span aria-hidden="true" style={{ color, marginTop: 5, flexShrink: 0, width: 5, height: 5, borderRadius: 5, background: color }} />
          <span style={{ color: colors.textPrimary }}>{item}</span>
        </div>
      ))}
    </div>
  );
}

const cardStyle: React.CSSProperties = {
  background: "#FFFFFF",
  border: `0.5px solid ${colors.border}`,
  borderRadius: 8,
  padding: 16,
};

function clampPct(value: number): number {
  return Math.max(0, Math.min(100, value));
}

function titleCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase();
}

export function formatWhen(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}

export { bandColor };
