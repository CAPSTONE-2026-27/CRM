import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../../tokens";
import { Badge, Button } from "./ui";
import { SquarePen } from "lucide-react";
import { useConvertLead, useLeadMeetings } from "../../lib/queries";
import { qualificationLabel, qualificationVariant, type Lead } from "../../lib/types";

/*
 * Converting a lead into an opportunity.
 *
 * Shows everything recorded about the lead up to this moment — its score, its
 * verdict, and every meeting logged against it — before asking for the decision.
 * Conversion is one-way and creates a deal plus (usually) an account, so the
 * screen that asks for it should show the evidence rather than a bare confirm
 * dialog with a name in it.
 */

const sectionLabel: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  color: colors.textSecondary,
  textTransform: "uppercase",
  letterSpacing: 0.4,
  marginBottom: 8,
};

const row: React.CSSProperties = {
  display: "flex",
  justifyContent: "space-between",
  gap: 12,
  padding: "9px 12px",
  fontSize: 12,
};

const inputStyle: React.CSSProperties = {
  width: "100%",
  border: `0.5px solid ${colors.border}`,
  borderRadius: 6,
  padding: "7px 10px",
  fontSize: 12,
  color: colors.textPrimary,
  background: "#FFFFFF",
  outline: "none",
  fontFamily: "inherit",
};

function formatDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

function formatTime(hhmm: string): string {
  const [h, m] = hhmm.split(":").map(Number);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return hhmm;
  const period = h >= 12 ? "PM" : "AM";
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return `${hour12}:${String(m).padStart(2, "0")} ${period}`;
}

export function ConvertLeadModal({
  lead,
  onClose,
  onConverted,
}: {
  lead: Lead;
  onClose: () => void;
  onConverted?: (dealId: string, opportunityId: string) => void;
}) {
  const [meetingAt, setMeetingAt] = useState("");
  const [meetingMode, setMeetingMode] = useState("ONLINE");
  const [participants, setParticipants] = useState("");

  const convert = useConvertLead();
  const { data: meetings, isLoading: meetingsLoading } = useLeadMeetings(lead.id);

  // The API refuses an unqualified lead; saying so here beats letting the user
  // fill in the meeting fields and then hit a 409.
  const blocked = lead.qualificationStatus === "UNQUALIFIED";

  const handleConvert = () => {
    convert.mutate(
      {
        leadId: lead.id,
        meetingScheduledAt: meetingAt ? new Date(meetingAt).toISOString() : undefined,
        meetingMode: meetingAt ? meetingMode : undefined,
        meetingParticipants: meetingAt ? participants.trim() || undefined : undefined,
      },
      {
        onSuccess: (result) => {
          toast.success(`Converted to ${result.opportunityId}`, {
            description: result.accountCreated
              ? "A new account was created for this company."
              : "Linked to the existing account for this company.",
          });
          onConverted?.(result.dealId, result.opportunityId);
          onClose();
        },
        onError: (err) =>
          toast.error("Couldn't convert the lead", {
            description: err instanceof Error ? err.message : undefined,
          }),
      }
    );
  };

  return (
    <div
      onClick={convert.isPending ? undefined : onClose}
      style={{
        position: "fixed", inset: 0, background: "rgba(0,0,0,0.35)", display: "flex",
        alignItems: "center", justifyContent: "center", zIndex: 50, padding: 20,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: "#FFFFFF", borderRadius: 8, width: "min(600px, 100%)",
          maxHeight: "85vh", overflowY: "auto", padding: 20,
        }}
      >
        <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 2 }}>Convert to opportunity</div>
        <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 16 }}>
          Creates a deal from this lead, carries its score across as the starting point, and links it to the
          account for this company.
        </div>

        {/* ---- the lead as it stands ---- */}
        <div style={sectionLabel}>Lead</div>
        <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 16 }}>
          <div style={{ ...row, background: "#FFFFFF" }}>
            <span style={{ color: colors.textSecondary }}>Name</span>
            <span style={{ fontWeight: 500 }}>
              {lead.fullName}
              {lead.company ? ` · ${lead.company}` : ""}
            </span>
          </div>
          <div style={{ ...row, background: colors.bgSecondary }}>
            <span style={{ color: colors.textSecondary }}>Product</span>
            <span style={{ fontWeight: 500 }}>{lead.product ?? "—"}</span>
          </div>
          <div style={{ ...row, background: "#FFFFFF" }}>
            <span style={{ color: colors.textSecondary }}>Current score</span>
            <span style={{ fontWeight: 500 }}>
              {lead.aiScore ?? "—"}
              {lead.status ? ` · ${lead.status}` : ""}
            </span>
          </div>
          <div style={{ ...row, background: colors.bgSecondary, alignItems: "center" }}>
            <span style={{ color: colors.textSecondary }}>Qualification</span>
            <Badge label={qualificationLabel(lead)} variant={qualificationVariant(lead)} />
          </div>
        </div>

        {/* ---- everything logged so far ---- */}
        <div style={sectionLabel}>
          Meetings {meetings && meetings.length > 0 ? `(${meetings.length})` : ""}
        </div>
        {meetingsLoading ? (
          <div style={{ fontSize: 12, color: colors.textSecondary, padding: "8px 0", marginBottom: 16 }}>
            Loading meeting history…
          </div>
        ) : !meetings || meetings.length === 0 ? (
          <div
            style={{
              fontSize: 12, color: colors.textSecondary, border: `0.5px dashed ${colors.border}`,
              borderRadius: 6, padding: "12px", marginBottom: 16, lineHeight: 1.5,
            }}
          >
            No meetings logged against this lead yet. You can still convert it — but the deal will start from the
            lead score alone, with nothing recorded about what the customer actually said.
          </div>
        ) : (
          <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 16 }}>
            {meetings.map((m, i) => (
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
              </div>
            ))}
          </div>
        )}

        {/* ---- optional first meeting on the new deal ---- */}
        {!blocked && (
          <>
            <div style={sectionLabel}>First meeting on the deal (optional)</div>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10, marginBottom: 10 }}>
              <input
                type="datetime-local"
                value={meetingAt}
                onChange={(e) => setMeetingAt(e.target.value)}
                style={inputStyle}
              />
              <select
                value={meetingMode}
                onChange={(e) => setMeetingMode(e.target.value)}
                style={inputStyle}
                disabled={!meetingAt}
              >
                {["ONLINE", "ONSITE", "PHONE"].map((m) => (
                  <option key={m} value={m}>
                    {m.charAt(0) + m.slice(1).toLowerCase()}
                  </option>
                ))}
              </select>
            </div>
            <input
              value={participants}
              onChange={(e) => setParticipants(e.target.value)}
              placeholder="Participants (optional)"
              disabled={!meetingAt}
              style={{ ...inputStyle, marginBottom: 16 }}
            />
          </>
        )}

        {blocked && (
          <div style={{ fontSize: 12, color: colors.danger, marginBottom: 16, lineHeight: 1.5 }}>
            This lead is marked unqualified, so it cannot become an opportunity. Re-qualify it from the lead list
            first if that verdict was wrong.
          </div>
        )}

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8 }}>
          <Button label="Cancel" onClick={onClose} disabled={convert.isPending} />
          <Button
            label={convert.isPending ? "Converting…" : "Convert to opportunity"}
            icon={SquarePen}
            variant="primary"
            onClick={handleConvert}
            disabled={blocked || convert.isPending}
          />
        </div>
      </div>
    </div>
  );
}
