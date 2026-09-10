import { colors } from "../../tokens";
import { Avatar, Badge, Button } from "./ui";
import { useContacts, useDeals } from "../../lib/queries";
import { DEAL_STAGE_LABELS, type Account } from "../../lib/types";

/*
 * Everything recorded about one account, on one screen.
 *
 * The list row shows a name, an industry and a value; this is the rest of it —
 * the firmographics, the integration switches, the people, and the deals that
 * belong to the company. Contacts and deals are filtered from the lists already
 * in the cache rather than fetched per account: both are small, both are
 * already loaded for the screen behind this modal, and a second round trip to
 * show what is already in memory would only add a spinner.
 */

const sectionLabel: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  color: colors.textSecondary,
  textTransform: "uppercase",
  letterSpacing: 0.4,
  marginBottom: 8,
};

function formatMoney(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === "") return "—";
  const n = typeof v === "number" ? v : Number(v);
  if (!Number.isFinite(n)) return String(v);
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(n);
}

function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? String(iso)
    : d.toLocaleString(undefined, { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

function Detail({ label, value, alt }: { label: string; value: React.ReactNode; alt: boolean }) {
  return (
    <div
      style={{
        display: "flex",
        justifyContent: "space-between",
        gap: 14,
        padding: "9px 12px",
        fontSize: 12,
        background: alt ? colors.bgSecondary : "#FFFFFF",
      }}
    >
      <span style={{ color: colors.textSecondary, flexShrink: 0 }}>{label}</span>
      <span style={{ color: colors.textPrimary, fontWeight: 500, textAlign: "right", wordBreak: "break-word" }}>
        {value}
      </span>
    </div>
  );
}

export function AccountDetailModal({
  account,
  ownerName,
  parentName,
  onClose,
}: {
  account: Account;
  ownerName?: string;
  parentName?: string;
  onClose: () => void;
}) {
  const { data: contacts } = useContacts();
  const { data: deals } = useDeals();

  const accountContacts = (contacts ?? []).filter((c) => String(c.accountId) === String(account.id));
  const accountDeals = (deals ?? []).filter((d) => String(d.accountId) === String(account.id));
  const pipelineValue = accountDeals.reduce((sum, d) => sum + (Number(d.value) || 0), 0);

  const rows: { label: string; value: React.ReactNode }[] = [
    { label: "Account name", value: account.name },
    { label: "Industry", value: account.industry || "—" },
    { label: "Employee count", value: account.employeeCount || "—" },
    { label: "Annual revenue", value: formatMoney(account.annualRevenue) },
    { label: "Relationship value", value: formatMoney(account.relationshipValue) },
    { label: "Billing address", value: account.billingAddress || "—" },
    { label: "Owner", value: ownerName ?? (account.ownerId ? `User ${account.ownerId}` : "Unassigned") },
    { label: "Parent account", value: parentName ?? (account.parentAccountId ? `Account ${account.parentAccountId}` : "—") },
    { label: "Account ID", value: account.id },
    { label: "Created", value: formatDate(account.createdAt) },
    { label: "Last modified", value: formatDate(account.updatedAt) },
  ];

  const integrations: { label: string; on: boolean | undefined }[] = [
    { label: "Email integration", on: account.emailIntegrationEnabled },
    { label: "Telephony integration", on: account.telephonyIntegrationEnabled },
    { label: "Document repository sync", on: account.docRepoSyncEnabled },
  ];

  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed", inset: 0, background: "rgba(0,0,0,0.35)", display: "flex",
        alignItems: "center", justifyContent: "center", zIndex: 50, padding: 20,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: "#FFFFFF", borderRadius: 8, width: "min(620px, 100%)",
          maxHeight: "85vh", overflowY: "auto", padding: 20,
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 14 }}>
          <Avatar initials={account.name.slice(0, 2).toUpperCase()} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 15, fontWeight: 600 }}>{account.name}</div>
            <div style={{ fontSize: 12, color: colors.textSecondary }}>{account.industry || "No industry recorded"}</div>
          </div>
          {account.aiSentimentScore != null && (
            <Badge
              label={`Sentiment ${account.aiSentimentScore}%`}
              variant={account.aiSentimentScore >= 50 ? "green" : "amber"}
            />
          )}
        </div>

        {/* ---- summary tiles ---- */}
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 8, marginBottom: 16 }}>
          {[
            { label: "Contacts", value: String(accountContacts.length) },
            { label: "Deals", value: String(accountDeals.length) },
            { label: "Pipeline value", value: pipelineValue ? formatMoney(pipelineValue) : "—" },
          ].map((tile) => (
            <div
              key={tile.label}
              style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, padding: "9px 11px" }}
            >
              <div style={{ fontSize: 10.5, color: colors.textSecondary }}>{tile.label}</div>
              <div style={{ fontSize: 15, fontWeight: 600, marginTop: 2 }}>{tile.value}</div>
            </div>
          ))}
        </div>

        {/* ---- every stored field ---- */}
        <div style={sectionLabel}>Account details</div>
        <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 16 }}>
          {rows.map((r, i) => (
            <Detail key={r.label} label={r.label} value={r.value} alt={i % 2 === 1} />
          ))}
        </div>

        <div style={sectionLabel}>Integrations</div>
        <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 16 }}>
          {integrations.map((it, i) => (
            <Detail
              key={it.label}
              label={it.label}
              alt={i % 2 === 1}
              value={<Badge label={it.on ? "Enabled" : "Disabled"} variant={it.on ? "green" : "blue"} />}
            />
          ))}
        </div>

        {/* ---- people ---- */}
        <div style={sectionLabel}>Contacts ({accountContacts.length})</div>
        <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 16 }}>
          {accountContacts.length === 0 ? (
            <div style={{ padding: "12px", fontSize: 12, color: colors.textSecondary }}>
              No contacts recorded against this account yet.
            </div>
          ) : (
            accountContacts.map((c, i) => (
              <div
                key={c.id}
                style={{
                  display: "flex", alignItems: "center", gap: 10, padding: "9px 12px",
                  fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF",
                }}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 500 }}>{c.fullName}</div>
                  <div style={{ color: colors.textSecondary, fontSize: 11 }}>
                    {[c.jobTitle, c.email].filter(Boolean).join(" · ") || "—"}
                  </div>
                </div>
                {c.isPrimary && <Badge label="Primary" variant="green" />}
              </div>
            ))
          )}
        </div>

        {/* ---- deals ---- */}
        <div style={sectionLabel}>Deals ({accountDeals.length})</div>
        <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 18 }}>
          {accountDeals.length === 0 ? (
            <div style={{ padding: "12px", fontSize: 12, color: colors.textSecondary }}>
              No deals linked to this account yet.
            </div>
          ) : (
            accountDeals.map((d, i) => (
              <div
                key={d.id}
                style={{
                  display: "flex", alignItems: "center", gap: 10, padding: "9px 12px",
                  fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF",
                }}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 500 }}>{d.name}</div>
                  <div style={{ color: colors.textSecondary, fontSize: 11 }}>
                    {DEAL_STAGE_LABELS[d.stage] ?? d.stage}
                    {d.probability != null ? ` · ${d.probability}% likely` : ""}
                  </div>
                </div>
                <span style={{ fontWeight: 500, whiteSpace: "nowrap" }}>{formatMoney(d.value)}</span>
              </div>
            ))
          )}
        </div>

        <div style={{ display: "flex", justifyContent: "flex-end" }}>
          <Button label="Close" onClick={onClose} />
        </div>
      </div>
    </div>
  );
}
