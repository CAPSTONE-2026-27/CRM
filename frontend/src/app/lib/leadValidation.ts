import { useCallback, useRef, useState } from "react";
import { api } from "./apiClient";

/**
 * Running a lead past the validation bot before creating it.
 *
 * The bot runs in SAP Build Process Automation, which answers a start request
 * in milliseconds and then works for several seconds. So this starts a run,
 * polls it, and resolves once there is a verdict — the caller awaits one
 * promise and never sees the polling.
 *
 * It resolves rather than rejects when validation cannot run. A representative
 * with a customer on the phone must be able to capture the lead whether or not
 * a cloud service is answering, so an unreachable platform produces a passing
 * verdict carrying a warning. The backend takes the same position; this only
 * mirrors it.
 */

export type ValidationVerdict = {
  valid: boolean;
  errors: string[];
  warnings: string[];
  duplicateOfLeadId: number | null;
};

export type ValidationStatus = {
  validationId: string;
  state: "PENDING" | "RUNNING" | "COMPLETED" | "TIMED_OUT" | "FAILED";
  step: string;
  label: string;
  percent: number;
  verdict: ValidationVerdict | null;
  failureReason: string | null;
};

/** How often to ask the backend where the run has got to. */
const POLL_INTERVAL_MS = 1200;

/** When to admit it is taking longer than usual, without giving up. */
const SLOW_AFTER_MS = 15_000;

/**
 * When to stop waiting.
 *
 * Comfortably past the backend's own budget (sap.process.validation-timeout,
 * 45s), so in normal operation the backend decides the outcome and this is
 * only a backstop for a status endpoint that stops answering altogether. Set
 * this below the backend's budget and the browser abandons runs the backend
 * is about to resolve.
 */
const GIVE_UP_AFTER_MS = 60_000;

const PASSED_WITHOUT_CHECKING: ValidationVerdict = {
  valid: true,
  errors: [],
  warnings: ["The lead could not be validated, so it was not checked."],
  duplicateOfLeadId: null,
};

export function useLeadValidation() {
  const [status, setStatus] = useState<ValidationStatus | null>(null);
  const [slow, setSlow] = useState(false);
  const cancelled = useRef(false);

  const reset = useCallback(() => {
    cancelled.current = false;
    setStatus(null);
    setSlow(false);
  }, []);

  /** Abandons the run. The process continues in SAP; we simply stop watching. */
  const cancel = useCallback(() => {
    cancelled.current = true;
  }, []);

  const validate = useCallback(
    async (lead: Record<string, unknown>, captureMethod: string): Promise<ValidationVerdict> => {
      cancelled.current = false;
      setSlow(false);
      setStatus({
        validationId: "",
        state: "PENDING",
        step: "STARTING",
        label: "Starting validation…",
        percent: 10,
        verdict: null,
        failureReason: null,
      });

      let validationId: string;
      try {
        const started = await api.post<{ validationId: string }>("/leads/validate/start", {
          lead,
          captureMethod,
        });
        validationId = started.validationId;
      } catch {
        // The run never began. Nothing to poll, and no reason to stop the
        // representative capturing the lead.
        return PASSED_WITHOUT_CHECKING;
      }

      const startedAt = Date.now();

      for (;;) {
        if (cancelled.current) {
          return PASSED_WITHOUT_CHECKING;
        }

        const elapsed = Date.now() - startedAt;
        if (elapsed > SLOW_AFTER_MS) {
          setSlow(true);
        }
        if (elapsed > GIVE_UP_AFTER_MS) {
          return PASSED_WITHOUT_CHECKING;
        }

        try {
          const next = await api.get<ValidationStatus>(`/leads/validate/${validationId}/status`);
          if (cancelled.current) {
            return PASSED_WITHOUT_CHECKING;
          }
          setStatus(next);

          if (next.verdict) {
            return next.verdict;
          }
        } catch {
          // A failed poll is not a failed validation — the next one may well
          // succeed, and the deadline above stops this going on for ever.
        }

        await sleep(POLL_INTERVAL_MS);
      }
    },
    [],
  );

  return { validate, status, slow, cancel, reset };
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
