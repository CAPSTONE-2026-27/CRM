import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../../tokens";
import { Badge, Button } from "./ui";
import { useAssignLead, useQualifyLead } from "../../lib/queries";
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

export function LeadFlowPanel({ lead, users }: { lead: Lead; users: UserRow[] }) {
  const { user } = useAuth();
  const canAssign = user?.role === "ADMIN" || user?.role === "MANAGER";

  const [assignee, setAssignee] = useState(lead.assignedToId ?? "");

  const assign = useAssignLead();
  const qualify = useQualifyLead(lead.id);

  const qualified = lead.qualificationStatus === "QUALIFIED";

  const decide = (isQualified: boolean) =>
    qualify.mutate(
      { qualified: isQualified },
      {
        onSuccess: () =>
          toast.success(isQualified ? "Lead qualified" : "Lead disqualified", {
            description: isQualified
              ? "It can now be assigned and converted."
              : "It will not be converted to an opportunity.",
          }),
        onError: (err) =>
          toast.error("Couldn't record the decision", {
            description: err instanceof Error ? err.message : undefined,
          }),
      }
    );

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

        {/* Only while the verdict is Pending, which means the scoring model
            never reached this lead. A lead left there is one nobody can act
            on, so a person decides instead. Where the model did answer, its
            verdict stands. */}
        {lead.qualificationStatus === "PENDING" && (
          <div style={{ marginTop: 10 }}>
            <div style={{ fontSize: 11, color: colors.textSecondary, marginBottom: 8 }}>
              This lead was never scored, so there is no verdict. Decide it yourself.
            </div>
            <div style={{ display: "flex", gap: 8 }}>
              <Button
                label={qualify.isPending ? "Saving…" : "Qualify"}
                variant="primary"
                onClick={() => decide(true)}
                disabled={qualify.isPending}
              />
              <Button
                label="Disqualify"
                onClick={() => decide(false)}
                disabled={qualify.isPending}
              />
            </div>
          </div>
        )}
      </Step>

      {/* ---- Step 4: assignment ---- */}
      <Step number={4} title="Assign to a sales executive" last done={lead.assignmentStatus === "ASSIGNED"}>
        {lead.assignmentStatus === "ASSIGNED" && (
          <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 8 }}>
            Assigned to {users.find((u) => u.id === lead.assignedToId)?.fullName ?? `user ${lead.assignedToId}`}
            {lead.assignedAt && ` on ${formatDate(lead.assignedAt)}`}
          </div>
        )}
        {/* A dimmed control with no explanation reads as broken rather than
            blocked. Qualification is the gate, and it is one step above. */}
        {canAssign && !qualified && (
          <div style={{ fontSize: 11, color: colors.textSecondary, marginBottom: 8 }}>
            {lead.qualificationStatus === "UNQUALIFIED"
              ? "Unqualified leads are not assigned to anyone."
              : "Qualify this lead first — only qualified leads are assigned to a sales executive."}
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
