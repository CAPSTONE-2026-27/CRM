import { useState } from "react";
import { colors } from "../../tokens";
import { Badge } from "./ui";
import type { ExtractedParameter, FeatureSet } from "../../lib/types";

/*
 * Deal flow steps 6-7 — what the analysis model read, and what was sent to the
 * scoring model.
 *
 * Two views of the same reading, because they answer different questions. The
 * parameters are the business signals a sales manager can argue with; the
 * features are the numbers the pipeline actually derived from them. Showing
 * only the first hides the translation, and showing only the second is
 * unreadable.
 */

export function ParameterViewer({
  parameters,
  featureSet,
}: {
  parameters: ExtractedParameter[];
  featureSet: FeatureSet | null | undefined;
}) {
  const [showFeatures, setShowFeatures] = useState(false);
  const imputed = new Set(featureSet?.imputedFields ?? []);

  if (!parameters.length) {
    return (
      <div style={{ fontSize: 12, color: colors.textSecondary }}>
        No parameters were extracted for this meeting.
      </div>
    );
  }

  return (
    <div>
      {imputed.size > 0 && (
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
          {imputed.size} signal{imputed.size > 1 ? "s" : ""} could not be read from this write-up and fell back to a
          neutral default. The score rests on less evidence than a full reading would give it.
        </div>
      )}

      <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
        {parameters.map((p, i) => (
          <ParameterRow key={p.name} parameter={p} striped={i % 2 === 1} imputed={imputed.has(p.name)} />
        ))}
      </div>

      {featureSet && (
        <div style={{ marginTop: 12 }}>
          <button
            type="button"
            onClick={() => setShowFeatures((v) => !v)}
            aria-expanded={showFeatures}
            style={{
              border: "none",
              background: "transparent",
              color: colors.primary,
              fontSize: 12,
              cursor: "pointer",
              padding: 0,
              fontFamily: "inherit",
            }}
          >
            {showFeatures ? "Hide" : "Show"} engineered features and model inputs
          </button>

          {showFeatures && (
            <div style={{ marginTop: 10, display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))", gap: 12 }}>
              <FeatureTable
                title="Engineered features"
                caption="Numeric vector, oriented so higher is always better for the deal."
                rows={Object.entries(featureSet.features).map(([k, v]) => [k, formatNumber(v)])}
              />
              <FeatureTable
                title="Model inputs"
                caption="The exact values sent to the XGBoost model."
                rows={Object.entries(featureSet.modelInputs).map(([k, v]) => [k, String(v)])}
              />
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function ParameterRow({
  parameter,
  striped,
  imputed,
}: {
  parameter: ExtractedParameter;
  striped: boolean;
  imputed: boolean;
}) {
  const confidence = parameter.confidence;
  return (
    <div style={{ padding: "10px 12px", background: striped ? colors.bgSecondary : "#FFFFFF" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 10, marginBottom: 4 }}>
        <span style={{ fontSize: 11, color: colors.textSecondary }}>{parameter.displayName}</span>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          {imputed && <Badge label="Defaulted" variant="amber" />}
          {confidence != null && <ConfidenceMeter value={confidence} />}
        </div>
      </div>
      <div style={{ fontSize: 12, fontWeight: 500, color: colors.textPrimary }}>{parameter.value ?? "—"}</div>
      {parameter.explanation && (
        <div style={{ fontSize: 11, color: colors.textSecondary, marginTop: 3, lineHeight: 1.45 }}>
          {parameter.explanation}
        </div>
      )}
    </div>
  );
}

/** Confidence is shown as a bar as well as a number: "0.4" and "0.9" look
 *  similar in a list, but a bar that is half full does not. */
function ConfidenceMeter({ value }: { value: number }) {
  const pct = Math.max(0, Math.min(100, value * 100));
  const color = value >= 0.75 ? colors.success : value >= 0.45 ? colors.warning : colors.danger;
  return (
    <span style={{ display: "flex", alignItems: "center", gap: 5 }} title={`Extraction confidence ${pct.toFixed(0)}%`}>
      <span style={{ width: 44, height: 4, borderRadius: 4, background: colors.bgSecondary, overflow: "hidden" }}>
        <span style={{ display: "block", width: `${pct}%`, height: "100%", background: color, borderRadius: 4 }} />
      </span>
      <span style={{ fontSize: 10, color: colors.textTertiary, minWidth: 26, textAlign: "right" }}>{pct.toFixed(0)}%</span>
    </span>
  );
}

function FeatureTable({ title, caption, rows }: { title: string; caption: string; rows: [string, string][] }) {
  return (
    <div>
      <div style={{ fontSize: 10, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4 }}>
        {title}
      </div>
      <div style={{ fontSize: 10, color: colors.textTertiary, marginBottom: 6 }}>{caption}</div>
      <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
        {rows.map(([key, value], i) => (
          <div
            key={key}
            style={{
              display: "flex",
              justifyContent: "space-between",
              gap: 10,
              padding: "5px 10px",
              fontSize: 11,
              background: i % 2 ? colors.bgSecondary : "#FFFFFF",
            }}
          >
            <span style={{ color: colors.textSecondary, fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" }}>
              {key}
            </span>
            <span style={{ color: colors.textPrimary, fontWeight: 500, textAlign: "right" }}>{value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(3).replace(/0+$/, "").replace(/\.$/, "");
}
