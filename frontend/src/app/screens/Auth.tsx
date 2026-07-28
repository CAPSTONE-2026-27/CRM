import { useState, useEffect, FormEvent } from "react";
import { toast } from "sonner";
import { colors } from "../tokens";
import { useAuth } from "../lib/auth";
import { SERVER_ORIGIN } from "../lib/apiClient";

function GoogleIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 48 48" aria-hidden="true">
      <path fill="#4285F4" d="M45.12 24.5c0-1.56-.14-3.06-.4-4.5H24v8.51h11.84c-.51 2.75-2.06 5.08-4.39 6.64v5.52h7.11c4.16-3.83 6.56-9.47 6.56-16.17z" />
      <path fill="#34A853" d="M24 46c5.94 0 10.92-1.97 14.56-5.33l-7.11-5.52c-1.97 1.32-4.49 2.1-7.45 2.1-5.73 0-10.58-3.87-12.31-9.07H4.34v5.7C7.96 41.07 15.4 46 24 46z" />
      <path fill="#FBBC05" d="M11.69 28.18C11.25 26.86 11 25.45 11 24s.25-2.86.69-4.18v-5.7H4.34C2.85 17.09 2 20.45 2 24s.85 6.91 2.34 9.88l7.35-5.7z" />
      <path fill="#EA4335" d="M24 10.75c3.23 0 6.13 1.11 8.41 3.29l6.31-6.31C34.91 4.18 29.93 2 24 2 15.4 2 7.96 6.93 4.34 14.12l7.35 5.7c1.73-5.2 6.58-9.07 12.31-9.07z" />
    </svg>
  );
}

function MicrosoftIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 21 21" aria-hidden="true">
      <rect x="1" y="1" width="9" height="9" fill="#F25022" />
      <rect x="11" y="1" width="9" height="9" fill="#7FBA00" />
      <rect x="1" y="11" width="9" height="9" fill="#00A4EF" />
      <rect x="11" y="11" width="9" height="9" fill="#FFB900" />
    </svg>
  );
}

// Microsoft is registered as "azure" server-side (the client registration id),
// so the button's provider name can't be used in the URL directly.
const OAUTH_REGISTRATION_ID: Record<"google" | "microsoft", string> = {
  google: "google",
  microsoft: "azure",
};

function OAuthButton({ provider, label }: { provider: "google" | "microsoft"; label: string }) {
  return (
    <button
      type="button"
      onClick={() => {
        // Full navigation, not fetch: the provider redirects the browser back
        // to the server, which sets the refresh cookie and returns to the app.
        window.location.href = `${SERVER_ORIGIN}/oauth2/authorization/${OAUTH_REGISTRATION_ID[provider]}`;
      }}
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        gap: 8,
        width: "100%",
        border: `0.5px solid ${colors.border}`,
        borderRadius: 6,
        background: "#FFFFFF",
        color: colors.textPrimary,
        fontSize: 12,
        fontWeight: 500,
        padding: "8px 14px",
        cursor: "pointer",
        fontFamily: "inherit",
      }}
    >
      {provider === "google" ? <GoogleIcon /> : <MicrosoftIcon />}
      {label}
    </button>
  );
}

function Divider({ label }: { label: string }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, margin: "2px 0" }}>
      <div style={{ flex: 1, height: 1, background: colors.border }} />
      <span style={{ fontSize: 11, color: colors.textTertiary }}>{label}</span>
      <div style={{ flex: 1, height: 1, background: colors.border }} />
    </div>
  );
}

function TextInput({ label, type = "text", value, onChange }: { label: string; type?: string; value: string; onChange: (v: string) => void }) {
  return (
    <div>
      <label style={{ fontSize: 11, fontWeight: 500, color: colors.textSecondary, display: "block", marginBottom: 5 }}>{label}</label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        required
        style={{
          width: "100%",
          border: `0.5px solid ${colors.border}`,
          borderRadius: 6,
          padding: "8px 10px",
          fontSize: 12,
          outline: "none",
          fontFamily: "inherit",
        }}
      />
    </div>
  );
}

export function AuthScreen() {
  const { login, signup } = useAuth();
  const [mode, setMode] = useState<"login" | "signup">("login");
  const [organizationName, setOrganizationName] = useState("");
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const oauthError = params.get("oauth_error");
    if (oauthError) {
      toast.error("Sign-in failed", { description: `Could not complete sign-in with ${oauthError === "google" ? "Google" : "Microsoft"}.` });
      window.history.replaceState({}, "", window.location.pathname);
    }
  }, []);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      if (mode === "login") {
        await login(email, password);
      } else {
        await signup(organizationName, fullName, email, password);
      }
    } catch (err) {
      toast.error(mode === "login" ? "Login failed" : "Signup failed", {
        description: err instanceof Error ? err.message : "Please try again.",
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      style={{
        height: "100vh",
        width: "100%",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: colors.bgSecondary,
        fontFamily: "Inter, system-ui, sans-serif",
      }}
    >
      <form
        onSubmit={handleSubmit}
        style={{
          width: 360,
          background: "#FFFFFF",
          border: `0.5px solid ${colors.border}`,
          borderRadius: 8,
          padding: 24,
          display: "flex",
          flexDirection: "column",
          gap: 14,
        }}
      >
        <div style={{ fontSize: 18, fontWeight: 600, color: colors.textPrimary, marginBottom: 4 }}>
          <span style={{ color: colors.primary }}>Tech</span>CRM
        </div>
        <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 4 }}>
          {mode === "login" ? "Sign in to your workspace" : "Create a new organization"}
        </div>

        <OAuthButton provider="google" label="Continue with Google" />
        <OAuthButton provider="microsoft" label="Continue with Microsoft" />
        <Divider label="or continue with email" />

        {mode === "signup" && <TextInput label="Organization name" value={organizationName} onChange={setOrganizationName} />}
        {mode === "signup" && <TextInput label="Full name" value={fullName} onChange={setFullName} />}
        <TextInput label="Work email" type="email" value={email} onChange={setEmail} />
        <TextInput label="Password" type="password" value={password} onChange={setPassword} />

        <button
          type="submit"
          disabled={busy}
          style={{
            background: colors.primary,
            border: "none",
            borderRadius: 6,
            color: "#FFFFFF",
            fontSize: 12,
            fontWeight: 500,
            padding: "9px 14px",
            cursor: busy ? "default" : "pointer",
            opacity: busy ? 0.6 : 1,
            marginTop: 4,
          }}
        >
          {busy ? "Please wait…" : mode === "login" ? "Sign in" : "Create organization"}
        </button>

        <button
          type="button"
          onClick={() => setMode(mode === "login" ? "signup" : "login")}
          style={{ background: "transparent", border: "none", color: colors.primary, fontSize: 12, cursor: "pointer", padding: 0 }}
        >
          {mode === "login" ? "New here? Create an organization" : "Already have an account? Sign in"}
        </button>
      </form>
    </div>
  );
}

// Shown in place of the dashboard whenever the logged-in user's
// mustChangePassword flag is true (first login on admin-issued or
// reset credentials) — enforced server-side too, so this is UX, not the
// actual security boundary.
export function ForceChangePasswordScreen() {
  const { changePassword, logout } = useAuth();
  const [oldPassword, setOldPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [busy, setBusy] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (newPassword !== confirmPassword) {
      toast.error("Passwords don't match", { description: "Re-enter the new password to confirm it." });
      return;
    }
    setBusy(true);
    try {
      await changePassword(oldPassword, newPassword);
      toast.success("Password updated");
    } catch (err) {
      toast.error("Could not change password", { description: err instanceof Error ? err.message : "Please try again." });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      style={{
        height: "100vh",
        width: "100%",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: colors.bgSecondary,
        fontFamily: "Inter, system-ui, sans-serif",
      }}
    >
      <form
        onSubmit={handleSubmit}
        style={{
          width: 360,
          background: "#FFFFFF",
          border: `0.5px solid ${colors.border}`,
          borderRadius: 8,
          padding: 24,
          display: "flex",
          flexDirection: "column",
          gap: 14,
        }}
      >
        <div style={{ fontSize: 18, fontWeight: 600, color: colors.textPrimary, marginBottom: 4 }}>
          <span style={{ color: colors.primary }}>Tech</span>CRM
        </div>
        <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 4 }}>
          You need to set a new password before continuing.
        </div>

        <TextInput label="Current password" type="password" value={oldPassword} onChange={setOldPassword} />
        <TextInput label="New password" type="password" value={newPassword} onChange={setNewPassword} />
        <TextInput label="Confirm new password" type="password" value={confirmPassword} onChange={setConfirmPassword} />

        <button
          type="submit"
          disabled={busy}
          style={{
            background: colors.primary,
            border: "none",
            borderRadius: 6,
            color: "#FFFFFF",
            fontSize: 12,
            fontWeight: 500,
            padding: "9px 14px",
            cursor: busy ? "default" : "pointer",
            opacity: busy ? 0.6 : 1,
            marginTop: 4,
          }}
        >
          {busy ? "Please wait…" : "Set new password"}
        </button>

        <button
          type="button"
          onClick={() => logout()}
          style={{ background: "transparent", border: "none", color: colors.textTertiary, fontSize: 12, cursor: "pointer", padding: 0 }}
        >
          Sign out instead
        </button>
      </form>
    </div>
  );
}
