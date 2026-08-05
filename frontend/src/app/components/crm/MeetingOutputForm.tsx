import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../../tokens";
import { Button, Field } from "./ui";
import { useSubmitMeetingOutput, type MeetingOutputInput } from "../../lib/queries";
import type { MeetingOutputDetail } from "../../lib/types";

/*
 * Deal flow step 4 — the structured meeting output.
 *
 * Replaces the free-text notes box. Every field is long-form because the
 * analysis model reasons from detail, and a rep who is given one big box tends
 * to write one paragraph. Only the date, time and summary are required: an
 * executive should not be blocked from recording a meeting because the customer
 * never mentioned a competitor.
 *
 * Submitting runs the whole chain server-side, which takes several seconds, so
 * the button reports what is happening rather than just spinning.
 */

const MEETING_TYPES = ["ONLINE", "ONSITE", "PHONE", "OTHER"];

type FormState = MeetingOutputInput;

const emptyForm = (): FormState => ({
  meetingDate: todayIso(),
  meetingTime: nowHhMm(),
  meetingType: "ONLINE",
  participants: "",
  meetingSummary: "",
  customerRequirements: "",
  keyDiscussionPoints: "",
  customerQuestions: "",
  competitorMentioned: "",
  objections: "",
  budgetDiscussion: "",
  timeline: "",
  nextSteps: "",
  executiveRemarks: "",
});

export function MeetingOutputForm({
  dealId,
  nextVersion,
  onSubmitted,
}: {
  dealId: string;
  nextVersion: number;
  onSubmitted: (result: MeetingOutputDetail) => void;
}) {
  const [form, setForm] = useState<FormState>(emptyForm);
  const submit = useSubmitMeetingOutput(dealId);

  const set = <K extends keyof FormState>(key: K) => (value: FormState[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }));

  const handleSubmit = () => {
    if (!form.meetingSummary.trim()) {
      toast.error("A meeting summary is required", {
        description: "It is the main thing the analysis model reads.",
      });
      return;
    }

    // Blank optional fields are dropped rather than sent as "": an empty string
    // reads to the model as "asked and answered with nothing", which is not the
    // same as never discussed.
    const payload = Object.fromEntries(
      Object.entries(form).filter(([, value]) => typeof value !== "string" || value.trim() !== "")
    ) as MeetingOutputInput;

    submit.mutate(payload, {
      onSuccess: (result) => {
        const score = result.prediction?.dealScore;
        toast.success(`Meeting ${result.version} analysed`, {
          description:
            score == null
              ? "Saved, but the scoring model was unavailable — the deal keeps its previous score."
              : `Deal score is now ${score.toFixed(1)}.`,
        });
        setForm(emptyForm());
        onSubmitted(result);
      },
      onError: (err) =>
        toast.error("Couldn't save the meeting output", {
          description: err instanceof Error ? err.message : undefined,
        }),
    });
  };

  return (
    <div>
      <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 14 }}>
        Meeting {nextVersion} for this opportunity. Submitting runs the analysis model over what you write, extracts
        the business signals, and re-scores the deal — earlier meetings are kept.
      </div>

      <Section title="Meeting details">
        <Grid>
          <Field required label="Meeting date" type="date" value={form.meetingDate} onChange={set("meetingDate")} />
          <Field required label="Meeting time" type="time" value={form.meetingTime} onChange={set("meetingTime")} />
          <Field label="Meeting type" type="select" value={form.meetingType} onChange={set("meetingType")} options={MEETING_TYPES} />
          <Field
            label="Participants"
            value={form.participants}
            onChange={set("participants")}
            placeholder="Names and roles on both sides"
          />
        </Grid>
      </Section>

      <Section title="What happened">
        <TextArea
          required
          label="Meeting summary"
          value={form.meetingSummary}
          onChange={set("meetingSummary")}
          rows={5}
          placeholder="What was covered, how the customer responded, and how the meeting ended."
        />
        <TextArea
          label="Key discussion points"
          value={form.keyDiscussionPoints}
          onChange={set("keyDiscussionPoints")}
          placeholder="The main topics, in the order they came up."
        />
        <TextArea
          label="Customer requirements"
          value={form.customerRequirements}
          onChange={set("customerRequirements")}
          placeholder="What they need the product to do — integrations, scale, compliance, rollout."
        />
        <TextArea
          label="Customer questions"
          value={form.customerQuestions}
          onChange={set("customerQuestions")}
          placeholder="What they asked. Unanswered questions are a buying signal too."
        />
      </Section>

      <Section title="Signals and blockers">
        <Grid>
          <TextArea
            label="Competitor mentioned"
            value={form.competitorMentioned}
            onChange={set("competitorMentioned")}
            placeholder="Who, and what they said about them."
          />
          <TextArea
            label="Objections"
            value={form.objections}
            onChange={set("objections")}
            placeholder="Every push-back, even ones you answered on the call."
          />
          <TextArea
            label="Budget discussion"
            value={form.budgetDiscussion}
            onChange={set("budgetDiscussion")}
            placeholder="Amount, approval status, and who holds the signing authority."
          />
          <TextArea
            label="Timeline"
            value={form.timeline}
            onChange={set("timeline")}
            placeholder="Their dates — decision, contract, go-live."
          />
        </Grid>
      </Section>

      <Section title="Outcome">
        <TextArea
          label="Next steps"
          value={form.nextSteps}
          onChange={set("nextSteps")}
          placeholder="Who does what, by when."
        />
        <TextArea
          label="Executive remarks"
          value={form.executiveRemarks}
          onChange={set("executiveRemarks")}
          placeholder="Your own read on the deal — the part the transcript doesn't capture."
        />
      </Section>

      {submit.isPending && (
        <div
          style={{
            background: colors.aiLight,
            border: "0.5px solid #AFA9EC",
            borderRadius: 6,
            padding: "10px 12px",
            fontSize: 12,
            color: "#3C3489",
            marginBottom: 12,
          }}
        >
          Analysing the meeting, extracting business parameters, and re-scoring the deal. This takes a few seconds.
        </div>
      )}

      <div style={{ display: "flex", justifyContent: "flex-end" }}>
        <Button
          label={submit.isPending ? "Analysing…" : "Submit and analyse"}
          variant="primary"
          onClick={handleSubmit}
          disabled={submit.isPending}
        />
      </div>
    </div>
  );
}

function TextArea({
  label,
  value,
  onChange,
  rows = 3,
  placeholder,
  required,
}: {
  label: string;
  value: string | undefined;
  onChange: (v: string) => void;
  rows?: number;
  placeholder?: string;
  required?: boolean;
}) {
  return (
    <div style={{ marginBottom: 12 }}>
      <label style={{ fontSize: 11, fontWeight: 500, color: colors.textSecondary, display: "block", marginBottom: 5 }}>
        {label}
        {required && (
          <span aria-hidden="true" style={{ color: colors.danger, marginLeft: 3 }}>
            *
          </span>
        )}
      </label>
      <textarea
        value={value ?? ""}
        rows={rows}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        style={{
          width: "100%",
          border: `0.5px solid ${colors.border}`,
          borderRadius: 6,
          padding: "8px 10px",
          fontSize: 12,
          resize: "vertical",
          outline: "none",
          fontFamily: "inherit",
          lineHeight: 1.5,
          color: colors.textPrimary,
        }}
      />
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 18 }}>
      <div
        style={{
          fontSize: 11,
          fontWeight: 600,
          color: colors.textSecondary,
          textTransform: "uppercase",
          letterSpacing: 0.4,
          marginBottom: 8,
        }}
      >
        {title}
      </div>
      {children}
    </div>
  );
}

function Grid({ children }: { children: React.ReactNode }) {
  return <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: 12 }}>{children}</div>;
}

function todayIso(): string {
  // Local date, not UTC, so the default matches the executive's calendar day.
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
}

function nowHhMm(): string {
  const now = new Date();
  return `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
}
