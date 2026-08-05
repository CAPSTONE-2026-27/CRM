import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../../tokens";
import { Badge, Button } from "./ui";
import { useAssignLead, useConvertLead, useUpdateContactStatus } from "../../lib/queries";
import { useAuth } from "../../lib/auth";
import {
  CONTACT_STATUS_LABELS,
  CONVERTIBLE_CONTACT_STATUSES,
  type ContactStatus,
  type Lead,
  type UserRow,
} from "../../lib/types";

/*
 * Lead flow steps 3-6, as one panel on the lead detail view.
 *
 * Qualification, assignment, first contact and conversion are shown together
 * because they are a sequence with real gates between them: an unqualified lead
 * cannot be assigned, and a lead the customer has not agreed to meet cannot
 * become an opportunity. Splitting them across screens would hide why the next
 * button is disabled.
 */

const CONTACT_OPTIONS: ContactStatus[] = [
  "NOT_CONTACTED",
  "MEETING_SCHEDULED",
  "INTERESTED",
  "NO_RESPONSE",
  "NOT_INTERESTED",
];

export function LeadFlowPanel({
  lead,
  users,
  onConverted,
}: {
  lead: Lead;
  users: UserRow[];
  onConverted?: (dealId: string, opportunityId: string) => void;
}) {
  const { user } = useAuth();
  const canAssign = user?.role === "ADMIN" || user?.role === "MANAGER";

  const [assignee, setAssignee] = useState(lead.assignedToId ?? "");
  const [contactStatus, setContactStatus] = useState<ContactStatus>(lead.contactStatus);
  const [contactNotes, setContactNotes] = useState(lead.contactNotes ?? "");
  const [meetingAt, setMeetingAt] = useState("");
  const [meetingMode, setMeetingMode] = useState("ONLINE");
  const [participants, setParticipants] = useState("");

  const assign = useAssignLead();
  const updateContact = useUpdateContactStatus();
  const convert = useConvertLead();

  const qualified = lead.qualificationStatus === "QUALIFIED";
  const converted = Boolean(lead.convertedDealId);
  const canConvert = qualified && !converted && CONVERTIBLE_CONTACT_STATUSES.includes(lead.contactStatus);

  const handleAssign = () => {
    if (!assignee) {
      toast.error("Pick a sales executive first");
      return;
    }
    assign.mutate(
      { leadId: lead.id, assignedToId: assignee },
      {
        onSuccess: () => toast.success("Lead assigned"),
        onError: (err) => toast.error("Couldn't assign the lead", { description: err instanceof Error ? err.message : undefined }),
      }
    );
  };

  const handleContact = () => {
    updateContact.mutate(
      { leadId: lead.id, contactStatus, contactNotes: contactNotes.trim() || undefined },
      {
        onSuccess: () => toast.success(`Contact status set to ${CONTACT_STATUS_LABELS[contactStatus].toLowerCase()}`),
        onError: (err) => toast.error("Couldn't update the contact status", { description: err instanceof Error ? err.message : undefined }),
      }
    );
  };

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
        },
        onError: (err) => toast.error("Couldn't convert the lead", { description: err instanceof Error ? err.message : undefined }),
      }
    );
  };

  return (
    <div>
      {/* ---- Step 3: qualification ---- */}
      <Step number={3} title="Qualification" done={lead.qualificationStatus !== "PENDING"}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", marginBottom: 6 }}>
          <Badge
            label={
              lead.qualificationStatus === "QUALIFIED"
                ? "Qualified"
                : lead.qualificationStatus === "UNQUALIFIED"
                  ? "Unqualified"
                  : "Pending"
            }
            variant={qualified ? "green" : lead.qualificationStatus === "UNQUALIFIED" ? "red" : "amber"}
          />
          {lead.qualificationProbability != null && (
            <span style={{ fontSize: 12, color: colors.textSecondary }}>
              {lead.qualificationProbability.toFixed(0)}% likely worth pursuing
            </span>
          )}
        </div>
        {lead.qualificationReasoning && (
          <div style={{ fontSize: 12, color: colors.textSecondary, lineHeight: 1.5 }}>{lead.qualificationReasoning}</div>
        )}
        {!qualified && lead.qualificationStatus === "UNQUALIFIED" && (
          <div style={{ fontSize: 11, color: colors.danger, marginTop: 6 }}>
            Unqualified leads cannot be assigned or converted. Edit the lead to re-score it if this looks wrong.
          </div>
        )}
      </Step>

      {/* ---- Step 4: assignment ---- */}
      <Step number={4} title="Assign to a sales executive" done={lead.assignmentStatus === "ASSIGNED"}>
        {lead.assignmentStatus === "ASSIGNED" && (
          <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 8 }}>
            Assigned to {users.find((u) => u.id === lead.assignedToId)?.fullName ?? `user ${lead.assignedToId}`}
            {lead.assignedAt && ` on ${formatDate(lead.assignedAt)}`}
          </div>
        )}
        {canAssign ? (
          <div style={{ display: "flex", gap: 8, alignItems: "flex-end", flexWrap: "wrap" }}>
            <select
              value={assignee}
              onChange={(e) => setAssignee(e.target.value)}
              disabled={!qualified}
              style={{ ...inputStyle, flex: "1 1 200px", opacity: qualified ? 1 : 0.5 }}
            >
              <option value="">Unassigned</option>
              {users.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.fullName} · {u.role.replace("_", " ").toLowerCase()}
                </option>
              ))}
            </select>
            <Button
              label={assign.isPending ? "Assigning…" : "Assign"}
              onClick={handleAssign}
              disabled={assign.isPending || !qualified}
            />
          </div>
        ) : (
          <div style={{ fontSize: 11, color: colors.textTertiary }}>
            Only an administrator or manager can change who owns a lead.
          </div>
        )}
      </Step>

      {/* ---- Step 5: first contact ---- */}
      <Step number={5} title="Contact the customer" done={lead.contactStatus !== "NOT_CONTACTED"}>
        <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginBottom: 10 }}>
          {CONTACT_OPTIONS.map((option) => {
            const active = contactStatus === option;
            return (
              <button
                key={option}
                type="button"
                onClick={() => setContactStatus(option)}
                aria-pressed={active}
                style={{
                  border: `1px solid ${active ? colors.primary : colors.border}`,
                  background: active ? colors.primaryLight : "#FFFFFF",
                  color: active ? colors.primary : colors.textPrimary,
                  borderRadius: 20,
                  padding: "5px 12px",
                  fontSize: 12,
                  cursor: "pointer",
                  fontFamily: "inherit",
                }}
              >
                {CONTACT_STATUS_LABELS[option]}
              </button>
            );
          })}
        </div>
        <textarea
          value={contactNotes}
          onChange={(e) => setContactNotes(e.target.value)}
          rows={2}
          placeholder="What happened on the call (optional)"
          style={{ ...inputStyle, resize: "vertical", lineHeight: 1.5, marginBottom: 8 }}
        />
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
          <span style={{ fontSize: 11, color: colors.textTertiary }}>
            {lead.contactStatusUpdatedAt ? `Last updated ${formatDate(lead.contactStatusUpdatedAt)}` : "Not recorded yet"}
          </span>
          <Button
            label={updateContact.isPending ? "Saving…" : "Save contact status"}
            onClick={handleContact}
            disabled={updateContact.isPending}
          />
        </div>
      </Step>

      {/* ---- Step 6: conversion ---- */}
      <Step number={6} title="Convert to opportunity" done={converted} last>
        {converted ? (
          <div style={{ fontSize: 12 }}>
            <Badge label="Converted" variant="green" />
            <div style={{ color: colors.textSecondary, marginTop: 6 }}>
              Became deal {lead.convertedDealId}
              {lead.convertedAt && ` on ${formatDate(lead.convertedAt)}`}. Open it from the Sales pipeline to record
              meetings and see its score.
            </div>
          </div>
        ) : (
          <>
            <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 10 }}>
              {canConvert
                ? "Creates an opportunity, links it to this lead, and carries the lead score across as the model's starting point."
                : qualified
                  ? "Set the contact status to Meeting scheduled or Interested first — an opportunity means the customer has agreed to talk."
                  : "Only qualified leads can become opportunities."}
            </div>
            {canConvert && (
              <>
                <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))", gap: 8, marginBottom: 8 }}>
                  <LabelledInput label="Meeting date and time (optional)">
                    <input type="datetime-local" value={meetingAt} onChange={(e) => setMeetingAt(e.target.value)} style={inputStyle} />
                  </LabelledInput>
                  <LabelledInput label="Mode">
                    <select value={meetingMode} onChange={(e) => setMeetingMode(e.target.value)} style={inputStyle} disabled={!meetingAt}>
                      {["ONLINE", "ONSITE", "PHONE"].map((m) => (
                        <option key={m} value={m}>
                          {m.charAt(0) + m.slice(1).toLowerCase()}
                        </option>
                      ))}
                    </select>
                  </LabelledInput>
                </div>
                <input
                  value={participants}
                  onChange={(e) => setParticipants(e.target.value)}
                  placeholder="Participants (optional)"
                  disabled={!meetingAt}
                  style={{ ...inputStyle, marginBottom: 10 }}
                />
              </>
            )}
            <div style={{ display: "flex", justifyContent: "flex-end" }}>
              <Button
                label={convert.isPending ? "Converting…" : "Convert to opportunity"}
                variant="primary"
                onClick={handleConvert}
                disabled={!canConvert || convert.isPending}
              />
            </div>
          </>
        )}
      </Step>
    </div>
  );
}

function Step({
  number,
  title,
  done,
  last,
  children,
}: {
  number: number;
  title: string;
  done: boolean;
  last?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div style={{ display: "flex", gap: 12, alignItems: "stretch" }}>
      {/* The connector makes the sequence readable as a sequence rather than
          four unrelated boxes stacked on top of each other. */}
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", flexShrink: 0 }}>
        <div
          style={{
            width: 22,
            height: 22,
            borderRadius: 22,
            background: done ? colors.success : colors.bgSecondary,
            color: done ? "#FFFFFF" : colors.textSecondary,
            border: `1px solid ${done ? colors.success : colors.border}`,
            fontSize: 10,
            fontWeight: 600,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          {done ? "✓" : number}
        </div>
        {!last && <div style={{ flex: 1, width: 1, background: colors.border, marginTop: 4 }} />}
      </div>
      <div style={{ flex: 1, minWidth: 0, paddingBottom: last ? 0 : 20 }}>
        <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 8 }}>{title}</div>
        {children}
      </div>
    </div>
  );
}

function LabelledInput({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label style={{ fontSize: 11, fontWeight: 500, color: colors.textSecondary, display: "block", marginBottom: 5 }}>
        {label}
      </label>
      {children}
    </div>
  );
}

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
