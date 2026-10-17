import { useState } from "react";
import { toast } from "sonner";
import { Pencil, UserPlus } from "lucide-react";
import { colors } from "../../tokens";
import { Avatar, Badge, Button, Field } from "./ui";
import { useContacts, useCreateContact, useDeals, useUpdateAccount } from "../../lib/queries";
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
 *
 * It is also where an account is corrected and its people added. Proposal and
 * contract generation both refuse an account with no billing address or no
 * contact with an email, so those two must be fixable here, not only by API.
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

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function numberOrNull(raw: string): number | null {
  const n = parseFloat(raw.replace(/[^0-9.]/g, ""));
  return Number.isFinite(n) ? n : null;
}

/**
 * Edits the account in place.
 *
 * The API's update is a PUT that replaces every field, so the fields this form
 * does not show (owner, parent, sentiment, integrations) are sent back with
 * their current values; leaving them out would erase them.
 */
function EditAccountForm({ account, onDone }: { account: Account; onDone: () => void }) {
  const [form, setForm] = useState({
    name: account.name ?? "",
    industry: account.industry ?? "",
    employeeCount: account.employeeCount ?? "",
    annualRevenue: account.annualRevenue != null ? String(account.annualRevenue) : "",
    relationshipValue: account.relationshipValue != null ? String(account.relationshipValue) : "",
    billingAddress: account.billingAddress ?? "",
  });
  const set = (key: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [key]: v }));
  const updateAccount = useUpdateAccount();

  const save = () => {
    if (!form.name.trim()) {
      toast.error("Account name is required");
      return;
    }
    updateAccount.mutate(
      {
        id: account.id,
        name: form.name.trim(),
        industry: form.industry.trim() || null,
        employeeCount: form.employeeCount.trim() || null,
        annualRevenue: numberOrNull(form.annualRevenue),
        relationshipValue: numberOrNull(form.relationshipValue),
        billingAddress: form.billingAddress.trim() || null,
        ownerId: account.ownerId != null ? Number(account.ownerId) : null,
        parentAccountId: account.parentAccountId != null ? Number(account.parentAccountId) : null,
        aiSentimentScore: account.aiSentimentScore ?? null,
        emailIntegrationEnabled: account.emailIntegrationEnabled ?? false,
        telephonyIntegrationEnabled: account.telephonyIntegrationEnabled ?? false,
        docRepoSyncEnabled: account.docRepoSyncEnabled ?? false,
      },
      {
        onSuccess: () => {
          toast.success(`${form.name.trim()} updated`);
          onDone();
        },
        onError: (err) =>
          toast.error("Couldn't update the account", { description: err instanceof Error ? err.message : undefined }),
      }
    );
  };

  return (
    <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, padding: 12, marginBottom: 16 }}>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10, marginBottom: 10 }}>
        <Field required label="Account name" value={form.name} onChange={set("name")} />
        <Field label="Industry" value={form.industry} onChange={set("industry")} />
        <Field label="Employee count" value={form.employeeCount} onChange={set("employeeCount")} />
        <Field label="Annual revenue (₹)" value={form.annualRevenue} onChange={set("annualRevenue")} />
        <Field label="Relationship value (₹)" value={form.relationshipValue} onChange={set("relationshipValue")} />
      </div>
      <div style={{ marginBottom: 12 }}>
        <Field label="Billing address" value={form.billingAddress} onChange={set("billingAddress")} placeholder="Street, city, state, PIN" />
      </div>
      <div style={{ display: "flex", justifyContent: "flex-end", gap: 8 }}>
        <Button label="Cancel" onClick={onDone} disabled={updateAccount.isPending} />
        <Button
          label={updateAccount.isPending ? "Saving…" : "Save account"}
          variant="primary"
          onClick={save}
          disabled={updateAccount.isPending}
        />
      </div>
    </div>
  );
}

/** Adds a person to the account. The account is fixed by where the form is
 *  opened, so a contact cannot land on the wrong company. */
function AddContactForm({ accountId, hasPrimary, onDone }: { accountId: string; hasPrimary: boolean; onDone: () => void }) {
  const [form, setForm] = useState({ fullName: "", jobTitle: "", email: "", phone: "" });
  // The first contact is the primary one unless the user says otherwise.
  const [isPrimary, setIsPrimary] = useState(!hasPrimary);
  const set = (key: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [key]: v }));
  const createContact = useCreateContact();

  const save = () => {
    if (!form.fullName.trim()) {
      toast.error("Full name is required");
      return;
    }
    if (form.email.trim() && !EMAIL_PATTERN.test(form.email.trim())) {
      toast.error("Enter a valid email address, or leave it blank");
      return;
    }
    createContact.mutate(
      {
        // The API takes a numeric account id; the Contact type carries it as a string.
        accountId: Number(accountId) as unknown as string,
        fullName: form.fullName.trim(),
        jobTitle: form.jobTitle.trim() || undefined,
        email: form.email.trim() || undefined,
        phone: form.phone.trim() || undefined,
        isPrimary,
      },
      {
        onSuccess: () => {
          toast.success(`${form.fullName.trim()} added`, {
            description: form.email.trim() ? undefined : "Without an email, proposals and contracts can't be sent to them.",
          });
          onDone();
        },
        onError: (err) =>
          toast.error("Couldn't add the contact", { description: err instanceof Error ? err.message : undefined }),
      }
    );
  };

  return (
    <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, padding: 12, marginBottom: 10 }}>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10, marginBottom: 10 }}>
        <Field required label="Full name" value={form.fullName} onChange={set("fullName")} placeholder="e.g. Chris Gayle" />
        <Field label="Job title" value={form.jobTitle} onChange={set("jobTitle")} placeholder="e.g. Finance Manager" />
        <Field label="Email" value={form.email} onChange={set("email")} placeholder="name@company.com" />
        <Field label="Phone" value={form.phone} onChange={set("phone")} placeholder="+91 90000 00000" />
      </div>
      <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12, marginBottom: 12, cursor: "pointer" }}>
        <input type="checkbox" checked={isPrimary} onChange={(e) => setIsPrimary(e.target.checked)} />
        Primary contact for this account
      </label>
      <div style={{ display: "flex", justifyContent: "flex-end", gap: 8 }}>
        <Button label="Cancel" onClick={onDone} disabled={createContact.isPending} />
        <Button
          label={createContact.isPending ? "Adding…" : "Add contact"}
          variant="primary"
          onClick={save}
          disabled={createContact.isPending}
        />
      </div>
    </div>
  );
}

const sectionHeader: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  marginBottom: 8,
};

const linkButton: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  gap: 4,
  border: "none",
  background: "transparent",
  color: colors.primary,
  fontSize: 12,
  cursor: "pointer",
  padding: 0,
};

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
  const [editing, setEditing] = useState(false);
  const [addingContact, setAddingContact] = useState(false);

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
        <div style={sectionHeader}>
          <div style={{ ...sectionLabel, marginBottom: 0 }}>Account details</div>
          {!editing && (
            <button style={linkButton} onClick={() => setEditing(true)}>
              <Pencil size={12} /> Edit
            </button>
          )}
        </div>
        {editing ? (
          <EditAccountForm account={account} onDone={() => setEditing(false)} />
        ) : (
          <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 16 }}>
            {rows.map((r, i) => (
              <Detail key={r.label} label={r.label} value={r.value} alt={i % 2 === 1} />
            ))}
          </div>
        )}

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
        <div style={sectionHeader}>
          <div style={{ ...sectionLabel, marginBottom: 0 }}>Contacts ({accountContacts.length})</div>
          {!addingContact && (
            <button style={linkButton} onClick={() => setAddingContact(true)}>
              <UserPlus size={12} /> Add contact
            </button>
          )}
        </div>
        {addingContact && (
          <AddContactForm
            accountId={account.id}
            hasPrimary={accountContacts.some((c) => c.isPrimary)}
            onDone={() => setAddingContact(false)}
          />
        )}
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
                    {[c.jobTitle, c.email, c.phone].filter(Boolean).join(" · ") || "—"}
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
