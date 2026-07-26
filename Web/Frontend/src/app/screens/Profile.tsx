import { useState } from "react";
import { toast } from "sonner";
import { colors } from "../tokens";
import { Card, Stack, Button, Avatar } from "../components/crm/ui";
import { useAuth } from "../lib/auth";
import { api } from "../lib/apiClient";

function EditableField({
  label,
  value,
  onChange,
  disabled,
  type = "text",
}: {
  label: string;
  value: string;
  onChange?: (v: string) => void;
  disabled?: boolean;
  type?: string;
}) {
  return (
    <div>
      <label style={{ fontSize: 11, fontWeight: 500, color: colors.textSecondary, display: "block", marginBottom: 5 }}>{label}</label>
      <input
        type={type}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange?.(e.target.value)}
        style={{
          width: "100%",
          border: `0.5px solid ${colors.border}`,
          borderRadius: 6,
          padding: "8px 10px",
          fontSize: 12,
          outline: "none",
          fontFamily: "inherit",
          background: disabled ? colors.bgSecondary : "#FFFFFF",
          color: disabled ? colors.textSecondary : colors.textPrimary,
        }}
      />
    </div>
  );
}

const providerLabel: Record<string, string> = { LOCAL: "Local password", GOOGLE: "Google", MICROSOFT: "Microsoft" };
const roleLabel: Record<string, string> = { ADMIN: "Admin", MANAGER: "Manager", SALES_REP: "Sales representative", SUPPORT_AGENT: "Support agent" };

function ChangePasswordForm({ onDone }: { onDone: () => void }) {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    setBusy(true);
    try {
      await api.post("/auth/change-password", { currentPassword, newPassword });
      toast.success("Password updated");
      onDone();
    } catch (err) {
      toast.error("Failed to update password", { description: err instanceof Error ? err.message : undefined });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack gap={8}>
      <EditableField label="Current password" type="password" value={currentPassword} onChange={setCurrentPassword} />
      <EditableField label="New password (min 8 characters)" type="password" value={newPassword} onChange={setNewPassword} />
      <div style={{ display: "flex", gap: 8 }}>
        <Button label={busy ? "Saving…" : "Update password"} variant="primary" onClick={submit} />
        <Button label="Cancel" onClick={onDone} />
      </div>
    </Stack>
  );
}

export function Profile() {
  const { user, refreshUser } = useAuth();
  const [fullName, setFullName] = useState(user?.fullName ?? "");
  const [phone, setPhone] = useState(user?.phone ?? "");
  const [jobTitle, setJobTitle] = useState(user?.jobTitle ?? "");
  const [department, setDepartment] = useState(user?.department ?? "");
  const [saving, setSaving] = useState(false);
  const [changingPassword, setChangingPassword] = useState(false);

  if (!user) return null;
  const isLocal = user.authProvider === "LOCAL";

  const handleAvatarUpload = (file: File) => {
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        await api.patch("/users/me", { avatarUrl: String(reader.result) });
        await refreshUser();
        toast.success("Profile photo updated");
      } catch (err) {
        toast.error("Failed to update photo", { description: err instanceof Error ? err.message : undefined });
      }
    };
    reader.readAsDataURL(file);
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      await api.patch("/users/me", { fullName, phone, jobTitle, department });
      await refreshUser();
      toast.success("Profile updated");
    } catch (err) {
      toast.error("Failed to update profile", { description: err instanceof Error ? err.message : undefined });
    } finally {
      setSaving(false);
    }
  };

  return (
    <Stack>
      <Card title="Profile photo">
        <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
          {user.avatarUrl ? (
            <img src={user.avatarUrl} alt="" width={64} height={64} style={{ borderRadius: "50%", objectFit: "cover" }} />
          ) : (
            <Avatar initials={fullName.slice(0, 2).toUpperCase() || "—"} color={colors.primary} size={64} />
          )}
          <div>
            <label style={{ display: "inline-block" }}>
              <span
                style={{
                  display: "inline-block",
                  border: `0.5px solid ${colors.border}`,
                  borderRadius: 6,
                  padding: "7px 14px",
                  fontSize: 12,
                  fontWeight: 500,
                  cursor: "pointer",
                }}
              >
                Upload photo
              </span>
              <input
                type="file"
                accept="image/*"
                style={{ display: "none" }}
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (file) handleAvatarUpload(file);
                }}
              />
            </label>
            <div style={{ fontSize: 11, color: colors.textTertiary, marginTop: 6 }}>JPG or PNG, stored on your profile.</div>
          </div>
        </div>
      </Card>

      <Card title="Personal details">
        <Stack gap={12}>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <EditableField label="Full name" value={fullName} onChange={setFullName} />
            <EditableField label="Work email" value={user.email} disabled />
            <EditableField label="Role" value={roleLabel[user.role] ?? user.role} disabled />
            <EditableField label="Phone" value={phone} onChange={setPhone} />
            <EditableField label="Job title" value={jobTitle} onChange={setJobTitle} />
            <EditableField label="Department" value={department} onChange={setDepartment} />
          </div>
          <div style={{ fontSize: 11, color: colors.textTertiary }}>
            Role is managed by an admin, not editable here.
          </div>
          <div>
            <Button label={saving ? "Saving…" : "Save changes"} variant="primary" onClick={handleSave} />
          </div>
        </Stack>
      </Card>

      <Card title="Sign-in method">
        <Stack gap={10}>
          <div style={{ fontSize: 12, color: colors.textPrimary }}>
            Signed in with <strong>{providerLabel[user.authProvider]}</strong>
            {!user.emailVerified && <span style={{ color: colors.textTertiary }}> · email not verified</span>}
          </div>
          {isLocal ? (
            changingPassword ? (
              <ChangePasswordForm onDone={() => setChangingPassword(false)} />
            ) : (
              <Button label="Change password" onClick={() => setChangingPassword(true)} />
            )
          ) : (
            <div style={{ fontSize: 11, color: colors.textSecondary }}>
              This account signs in via {providerLabel[user.authProvider]} — password changes aren't applicable.
            </div>
          )}
        </Stack>
      </Card>
    </Stack>
  );
}
