import { colors } from "../../tokens";
import { Button, ProgressBar } from "./ui";
import type { ValidationStatus } from "../../lib/leadValidation";

/**
 * What the representative watches while the validation bot runs.
 *
 * The steps shown are the ones the backend can actually observe — the process
 * starting, the automation checking fields, the automation asking about
 * duplicates, the instance finishing. Nothing here invents a rate or animates
 * between them: the bar sits at a known position until the next real signal
 * arrives, because a bar that creeps forward on a timer claims knowledge the
 * backend does not have.
 */

const STEPS: { key: string; label: string }[] = [
  { key: "TRIGGERED", label: "Connecting to validation service" },
  { key: "FIELD_CHECK", label: "Checking lead details" },
  { key: "DUPLICATE_CHECK", label: "Checking for duplicates" },
  { key: "DONE", label: "Finishing up" },
];

const ORDER = ["STARTING", "TRIGGERED", "FIELD_CHECK", "DUPLICATE_CHECK", "DONE"];

export function ValidationProgressModal({
  status,
  slow,
  onCancel,
}: {
  status: ValidationStatus | null;
  slow: boolean;
  onCancel: () => void;
}) {
  if (!status) return null;

  const reached = ORDER.indexOf(status.step);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Validating lead"
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(15, 23, 42, 0.45)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        zIndex: 50,
        padding: 16,
      }}
    >
      <div
        style={{
          background: colors.bgPrimary,
          borderRadius: 12,
          padding: 24,
          width: "100%",
          maxWidth: 420,
          boxShadow: "0 20px 40px rgba(15, 23, 42, 0.2)",
        }}
      >
        <h3 style={{ margin: 0, fontSize: 16, fontWeight: 600, color: colors.textPrimary }}>
          Validating lead
        </h3>
        <p style={{ margin: "6px 0 18px", fontSize: 13, color: colors.textSecondary }}>
          Checking the details before the lead is created.
        </p>

        <ProgressBar label={status.label} pct={status.percent} color={colors.primary} />

        <ul style={{ listStyle: "none", padding: 0, margin: "18px 0 0" }}>
          {STEPS.map((step) => {
            const index = ORDER.indexOf(step.key);
            const done = reached > index;
            const active = reached === index;
            return (
              <li
                key={step.key}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  fontSize: 13,
                  padding: "5px 0",
                  color: done || active ? colors.textPrimary : colors.textSecondary,
                  fontWeight: active ? 600 : 400,
                }}
              >
                <span
                  aria-hidden
                  style={{
                    width: 16,
                    height: 16,
                    borderRadius: "50%",
                    flexShrink: 0,
                    display: "inline-flex",
                    alignItems: "center",
                    justifyContent: "center",
                    fontSize: 10,
                    color: "#fff",
                    background: done
                      ? colors.success
                      : active
                        ? colors.primary
                        : colors.border,
                  }}
                >
                  {done ? "✓" : ""}
                </span>
                {step.label}
              </li>
            );
          })}
        </ul>

        {slow && (
          <p style={{ margin: "16px 0 0", fontSize: 12, color: colors.textSecondary }}>
            This is taking longer than usual. You can keep waiting, or continue without
            validation — the lead will be created either way.
          </p>
        )}

        <div style={{ display: "flex", justifyContent: "flex-end", marginTop: 20 }}>
          <Button label={slow ? "Continue without validation" : "Cancel"} onClick={onCancel} />
        </div>
      </div>
    </div>
  );
}
