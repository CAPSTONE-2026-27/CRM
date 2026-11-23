import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../../tokens";
import { Badge, Button, Field } from "./ui";
import { ClosePanel, DocumentRecipient } from "./DealWorkspace";
import { useRecordNegotiatedTerms } from "../../lib/queries";
import { DEAL_STAGE_LABELS, type Deal } from "../../lib/types";

/**
 * A deal, read.
 *
 * The board opens this for every stage but Deal Meeting, which has its own
 * workspace. The distinction is what the stage is for: a meeting stage is
 * where a conversation is written down and the deal is scored from it, while
 * Proposal, Negotiation and Contract are stages a deal passes through while
 * documents move. Showing the meeting form against those invites a
 * representative to record a meeting under the wrong one.
 *
 * Closing is here rather than in the workspace because a deal is usually won
 * or lost from Negotiation or Contract — stages this modal covers and that one
 * does not.
 */
export function DealDetailModal({ deal, onClose }: { deal: Deal; onClose: () => void }) {
  const closed = deal.stage === "CLOSED_WON" || deal.stage === "CLOSED_LOST";

  const fields: [string, string][] = [
    ["Stage", DEAL_STAGE_LABELS[deal.stage] ?? deal.stage],
    ["Value", formatCurrency(deal.value)],
    ["Expected close", deal.expectedCloseDate ? formatDate(deal.expectedCloseDate) : "—"],
    ["Win probability", deal.winProbability != null ? `${Math.round(deal.winProbability)}%` : "—"],
    ["Risk", deal.riskLevel ?? "—"],
    ["Next action", deal.dealScoreAction ?? "—"],
    ["Payment terms", deal.paymentTerms ?? "—"],
    ["Delivery timeline", deal.deliveryTimeline ?? "—"],
  ];

  return (
    <div
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={deal.name}
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
          width: "min(680px, 100%)",
          maxHeight: "85vh",
          overflowY: "auto",
          padding: 20,
        }}
      >
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 12 }}>
          <div>
            <div style={{ fontSize: 15, fontWeight: 600 }}>{deal.name}</div>
            <div
              style={{
                fontSize: 11,
                color: colors.textSecondary,
                fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
                marginTop: 2,
              }}
            >
              {deal.opportunityId ?? `Deal ${deal.id}`}
            </div>
          </div>
          <Button label="Close" onClick={onClose} />
        </div>

        <div style={{ display: "flex", gap: 8, margin: "14px 0", flexWrap: "wrap" }}>
          <Badge
            label={DEAL_STAGE_LABELS[deal.stage] ?? deal.stage}
            variant={deal.stage === "CLOSED_WON" ? "green" : deal.stage === "CLOSED_LOST" ? "red" : "blue"}
          />
          {deal.dealScore != null && <Badge label={`Score ${Math.round(deal.dealScore)}`} variant="purple" />}
        </div>

        {/* Who the documents go to, and whether they can be produced at all.
            Shown here because a missing billing address or contact is found
            at Proposal or Contract, not during the meeting. */}
        <DocumentRecipient dealId={deal.id} />

        <div
          style={{
            fontSize: 11,
            fontWeight: 600,
            color: colors.textSecondary,
            textTransform: "uppercase",
            letterSpacing: 0.4,
            margin: "18px 0 8px",
          }}
        >
          Details
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px 18px", fontSize: 12 }}>
          {fields.map(([label, value]) => (
            <div key={label} style={{ display: "flex", justifyContent: "space-between", gap: 10 }}>
              <span style={{ color: colors.textSecondary }}>{label}</span>
              <span style={{ fontWeight: 500, textAlign: "right" }}>{value}</span>
            </div>
          ))}
        </div>

        {/* Negotiation is where the price and terms are settled, so it is the
            one stage that edits them. What is agreed here is what the contract
            carries: the assembler reads the deal's own terms, and the contract
            bot sends none of its own. */}
        {deal.stage === "NEGOTIATION" && <NegotiatedTermsForm deal={deal} />}

        {!closed && deal.stage !== "NEGOTIATION" && (
          <div style={{ fontSize: 11, color: colors.textTertiary, margin: "18px 0 8px" }}>
            Meeting notes and deal scoring live in the Deal meeting stage — move the card there to record one.
          </div>
        )}

        <div style={{ marginTop: 14 }}>
          <ClosePanel
            dealId={deal.id}
            stage={deal.stage}
            closingReason={deal.closingReason}
            closedAt={deal.closedAt}
          />
        </div>
      </div>
    </div>
  );
}

/**
 * The price and terms as finally agreed.
 *
 * Pre-filled with what the deal already carries, so a representative changing
 * one figure does not have to retype the rest. Blank fields are left alone by
 * the backend rather than cleared, which is why clearing a box here does not
 * remove the term.
 */
function NegotiatedTermsForm({ deal }: { deal: Deal }) {
  const [finalAmount, setFinalAmount] = useState(deal.value == null ? "" : String(deal.value));
  const [paymentTerms, setPaymentTerms] = useState(deal.paymentTerms ?? "");
  const [deliveryTimeline, setDeliveryTimeline] = useState(deal.deliveryTimeline ?? "");

  const record = useRecordNegotiatedTerms(deal.id);

  const handleSave = () => {
    const amount = finalAmount.trim() === "" ? undefined : Number(finalAmount.replace(/[^0-9.]/g, ""));
    if (amount !== undefined && (!Number.isFinite(amount) || amount <= 0)) {
      toast.error("Enter a valid agreed price", { description: "It must be a number above zero." });
      return;
    }

    record.mutate(
      {
        finalAmount: amount,
        paymentTerms: paymentTerms.trim() || undefined,
        deliveryTimeline: deliveryTimeline.trim() || undefined,
      },
      {
        onSuccess: () =>
          toast.success("Terms recorded", {
            description: "The contract will be drawn up with these when the deal reaches Contract.",
          }),
        onError: (err) =>
          toast.error("Couldn't record the terms", {
            description: err instanceof Error ? err.message : undefined,
          }),
      }
    );
  };

  return (
    <div style={{ marginTop: 18, border: `0.5px solid ${colors.border}`, borderRadius: 6, padding: 14 }}>
      <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 2 }}>Agreed terms</div>
      <div style={{ fontSize: 11, color: colors.textSecondary, marginBottom: 12 }}>
        What the customer settled on. The contract is drawn up from these.
      </div>

      <Field label="Agreed price" value={finalAmount} onChange={setFinalAmount} placeholder="e.g. 450000" />
      <Field
        label="Payment terms"
        value={paymentTerms}
        onChange={setPaymentTerms}
        placeholder="e.g. 50% advance, balance on delivery"
      />
      <Field
        label="Delivery timeline"
        value={deliveryTimeline}
        onChange={setDeliveryTimeline}
        placeholder="e.g. Within 6 weeks of signing"
      />

      <div style={{ display: "flex", justifyContent: "flex-end", marginTop: 12 }}>
        <Button
          label={record.isPending ? "Saving…" : "Save terms"}
          variant="primary"
          onClick={handleSave}
          disabled={record.isPending}
        />
      </div>
    </div>
  );
}

function formatCurrency(value: string | number | null | undefined): string {
  if (value == null) return "—";
  const amount = Number(value);
  if (!Number.isFinite(amount)) return "—";
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(amount);
}

function formatDate(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleDateString();
}
