import { toast } from "sonner";
import { badgeVariants, colors } from "../../tokens";
import { useQualifyLead } from "../../lib/queries";
import { qualificationLabel, qualificationVariant, type Lead } from "../../lib/types";

/*
 * The qualification decision, as one control.
 *
 * Qualification used to be derived from the AI score — anything the model liked
 * became "Qualified" on its own. It is a human call now: the model still supplies
 * the score and the probability, but a sales executive makes the decision, and
 * re-scoring the lead never overturns it.
 *
 * Deliberately a single pill rather than a status badge sitting next to a pair of
 * action buttons. The verdict and the way to change it are the same thing, so
 * showing them as two elements per row read as clutter in a table that already
 * carries nine columns. Clicking flips to the opposite verdict; a lead nobody has
 * judged yet reads "Qualify" because there is no verdict to display, only an
 * invitation to make one.
 */

export function QualifyControl({ lead, compact }: { lead: Lead; compact?: boolean }) {
  const qualify = useQualifyLead();
  const status = lead.qualificationStatus;

  // One click, one meaning: anything that isn't already qualified becomes
  // qualified, and a qualified lead becomes unqualified. Reaching "unqualified"
  // from "pending" therefore takes two clicks — an acceptable cost for a control
  // that never asks the reader which of two buttons they wanted.
  const nextVerdict = status !== "QUALIFIED";

  const label = status === "PENDING" ? "Qualify" : qualificationLabel(lead);
  const tone = badgeVariants[qualificationVariant(lead)];
  const hint =
    status === "PENDING"
      ? "Not qualified yet — click to qualify this lead"
      : status === "QUALIFIED"
        ? "Qualified — click to disqualify"
        : "Unqualified — click to qualify";

  const handleClick = () => {
    qualify.mutate(
      { leadId: lead.id, qualified: nextVerdict },
      {
        onSuccess: () =>
          toast.success(
            nextVerdict ? `${lead.fullName} marked qualified` : `${lead.fullName} marked unqualified`,
            {
              description: nextVerdict
                ? "The lead can now be assigned and converted."
                : "Unqualified leads can't be assigned or converted.",
            }
          ),
        onError: (err) =>
          toast.error("Couldn't update the qualification", {
            description: err instanceof Error ? err.message : undefined,
          }),
      }
    );
  };

  return (
    <button
      type="button"
      title={hint}
      aria-label={`${hint} (${lead.fullName})`}
      disabled={qualify.isPending}
      onClick={(e) => {
        // The row itself opens the lead detail modal.
        e.stopPropagation();
        handleClick();
      }}
      style={{
        background: tone.bg,
        color: tone.text,
        // A hairline border is what separates this from the read-only badges in
        // the same row — without it nothing suggests the pill can be pressed.
        border: `1px solid ${qualify.isPending ? colors.border : tone.text}33`,
        borderRadius: 20,
        padding: compact ? "3px 10px" : "5px 12px",
        fontSize: compact ? 11 : 12,
        fontWeight: 500,
        lineHeight: 1.5,
        fontFamily: "inherit",
        whiteSpace: "nowrap",
        cursor: qualify.isPending ? "wait" : "pointer",
        opacity: qualify.isPending ? 0.55 : 1,
      }}
    >
      {qualify.isPending ? "Saving…" : label}
    </button>
  );
}
