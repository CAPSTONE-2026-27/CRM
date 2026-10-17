import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../../tokens";
import { Button, Field } from "./ui";
import { useDeal, useScheduleMeeting } from "../../lib/queries";
import type { Lead } from "../../lib/types";

/*
 * Booking a meeting with a lead, and inviting them to it.
 *
 * Only what an invitation needs: when, and where. What was discussed is a
 * different form on a different screen, filled in after the meeting rather
 * than before it.
 *
 * The lead's address is shown and not editable. It is already on the record,
 * and a field for it would let a representative mistype the one value the
 * whole invitation depends on.
 */

const sectionLabel: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  color: colors.textSecondary,
  textTransform: "uppercase",
  letterSpacing: 0.4,
  marginBottom: 8,
};

const readOnlyRow: React.CSSProperties = {
  display: "flex",
  justifyContent: "space-between",
  gap: 12,
  padding: "9px 12px",
  fontSize: 12,
};

export function ScheduleMeetingModal({ lead, onClose }: { lead: Lead; onClose: () => void }) {
  const [meetingDate, setMeetingDate] = useState(todayIso());
  const [meetingTime, setMeetingTime] = useState(nowHhMm());
  const [meetingLink, setMeetingLink] = useState("");

  const schedule = useScheduleMeeting(lead.id);

  // Once the lead has been converted the meeting is about the opportunity, so
  // the invitation and this panel name the deal. The email address does not
  // change — it is the same person either way.
  const converted = Boolean(lead.convertedDealId);
  const { data: deal } = useDeal(lead.convertedDealId);

  const handleSchedule = () => {
    if (!meetingLink.trim()) {
      toast.error("Add the meeting link", { description: "It is what the lead clicks to join." });
      return;
    }

    schedule.mutate(
      { meetingDate, meetingTime, meetingLink: meetingLink.trim() },
      {
        onSuccess: (result) => {
          if (result.invitationStarted) {
            toast.success("Meeting scheduled", {
              description: `The invitation is on its way to ${result.leadEmail}.`,
            });
          } else {
            // Booked, but nobody was told — worth saying plainly rather than
            // letting a representative assume the lead has been invited.
            toast.warning("Meeting scheduled, but no invitation was sent", {
              description: "The scheduling bot is unavailable. Send the link to the lead yourself.",
            });
          }
          onClose();
        },
        onError: (err) =>
          toast.error("Couldn't schedule the meeting", {
            description: err instanceof Error ? err.message : undefined,
          }),
      }
    );
  };

  return (
    <div
      onClick={schedule.isPending ? undefined : onClose}
      role="dialog"
      aria-modal="true"
      aria-label="Schedule a meeting"
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
        style={{
          background: colors.bgPrimary,
          borderRadius: 8,
          width: "min(560px, 100%)",
          maxHeight: "85vh",
          overflowY: "auto",
          padding: 20,
        }}
      >
        <div style={{ fontSize: 15, fontWeight: 600 }}>Schedule meeting</div>
        <div style={{ fontSize: 12, color: colors.textSecondary, margin: "4px 0 18px" }}>
          {converted
            ? "The customer is emailed an invitation naming the deal, with the date, time and link."
            : "The lead is emailed an invitation with the date, time and link."}
        </div>

        <div style={sectionLabel}>{converted ? "Deal information" : "Lead information"}</div>
        <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, marginBottom: 18 }}>
          <div style={readOnlyRow}>
            <span style={{ color: colors.textSecondary }}>{converted ? "Deal ID" : "Lead ID"}</span>
            <span style={{ fontWeight: 500 }}>
              {converted ? (deal?.opportunityId ?? lead.convertedDealId) : lead.id}
            </span>
          </div>
          <div style={{ ...readOnlyRow, background: colors.bgSecondary }}>
            <span style={{ color: colors.textSecondary }}>{converted ? "Deal name" : "Lead name"}</span>
            <span style={{ fontWeight: 500 }}>
              {converted
                ? (deal?.name ?? `${lead.fullName}${lead.company ? ` · ${lead.company}` : ""}`)
                : `${lead.fullName}${lead.company ? ` · ${lead.company}` : ""}`}
            </span>
          </div>
          <div style={readOnlyRow}>
            <span style={{ color: colors.textSecondary }}>Email</span>
            <span style={{ fontWeight: 500 }}>{lead.email ?? "—"}</span>
          </div>
        </div>

        <div style={sectionLabel}>Meeting information</div>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
          <Field label="Meeting date" type="date" value={meetingDate} onChange={setMeetingDate} required />
          <Field label="Meeting time" type="time" value={meetingTime} onChange={setMeetingTime} required />
        </div>
        <Field
          label="Meeting link"
          value={meetingLink}
          placeholder="https://meet.google.com/abc-defg-hij"
          onChange={setMeetingLink}
          required
        />

        {!lead.email && (
          <div style={{ fontSize: 11, color: colors.danger, marginTop: 10 }}>
            {lead.fullName} has no email address, so there is nobody to invite. Add one to the lead first.
          </div>
        )}

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 20 }}>
          <Button label="Cancel" onClick={onClose} disabled={schedule.isPending} />
          <Button
            label={schedule.isPending ? "Scheduling…" : "Schedule meeting"}
            variant="primary"
            onClick={handleSchedule}
            disabled={schedule.isPending || !lead.email}
          />
        </div>
      </div>
    </div>
  );
}

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
