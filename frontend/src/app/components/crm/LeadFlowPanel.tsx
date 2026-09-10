import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../../tokens";
import { Button } from "./ui";
import { QualifyControl } from "./QualifyControl";
import { useAssignLead } from "../../lib/queries";
import { useAuth } from "../../lib/auth";
import { type Lead, type UserRow } from "../../lib/types";

/*
 * Lead flow steps 3-4, as one panel on the lead detail view.
 *
 * Qualification and assignment are shown together because they are a sequence
 * with a real gate between them: an unqualified lead cannot be assigned, and
 * splitting them across screens would hide why the next control is disabled.
 * The lead's journey in this panel ends once it has an owner.
 */

export function LeadFlowPanel({ lead, users }: { lead: Lead; users: UserRow[] }) {
  const { user } = useAuth();
  const canAssign = user?.role === "ADMIN" || user?.role === "MANAGER";

  const [assignee, setAssignee] = useState(lead.assignedToId ?? "");

  const assign = useAssignLead();

  const qualified = lead.qualificationStatus === "QUALIFIED";

  // A lead is worked by a sales executive, so only sales reps are offered as
  // owners — administrators and managers run the pipeline rather than carry it.
  // This matches what the backend already does on its own: autoAssignIfQualified
  // only ever picks a SALES_REP when it places a newly qualified lead.
  //
  // A lead assigned to someone outside that set before this rule existed keeps
  // its current owner; the name still shows above, it just cannot be picked again.
  const assignableUsers = users.filter((u) => u.role === "SALES_REP");

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

  return (
    <div>
      {/* ---- Step 3: qualification ---- */}
      <Step number={3} title="Qualification" done={lead.qualificationStatus !== "PENDING"}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", marginBottom: 6 }}>
          <QualifyControl lead={lead} />
          {lead.qualificationProbability != null && (
            <span style={{ fontSize: 12, color: colors.textSecondary }}>
              {lead.qualificationProbability.toFixed(0)}% likely worth pursuing
            </span>
          )}
        </div>
        {lead.qualificationReasoning && (
          <div style={{ fontSize: 12, color: colors.textSecondary, lineHeight: 1.5 }}>{lead.qualificationReasoning}</div>
        )}
        {lead.qualificationStatus === "PENDING" && (
          <div style={{ fontSize: 12, color: colors.textSecondary, lineHeight: 1.5, marginTop: 2 }}>
            The score and the probability above are the model's read on this lead. The decision is yours — nothing
            downstream moves until you make it.
          </div>
        )}
        {lead.qualificationStatus === "UNQUALIFIED" && (
          <div style={{ fontSize: 11, color: colors.danger, marginTop: 6 }}>
            Unqualified leads cannot be assigned to a sales executive. Re-qualify it if this looks wrong.
          </div>
        )}
      </Step>

      {/* ---- Step 4: assignment ---- */}
      <Step number={4} title="Assign to a sales executive" done={lead.assignmentStatus === "ASSIGNED"} last>
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
              {assignableUsers.map((u) => (
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
