import { useState, useEffect, FormEvent } from "react";
import { toast } from "sonner";
import { colors } from "../tokens";
import { useAuth } from "../lib/auth";
import { SERVER_ORIGIN } from "../lib/apiClient";
import { BrandLockup, BrandMark, Spinner } from "../components/crm/BrandLogo";

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
    }
    // Clear both markers so a reload doesn't re-show the error or re-trigger
    // the post-OAuth session pickup.
    if (oauthError || params.has("signed_in")) {
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

  const isLogin = mode === "login";

  return (
    <div
      style={{
        minHeight: "100vh",
        width: "100%",
        display: "grid",
        // Brand panel sits alongside the form on desktop and is dropped
        // entirely below 900px, where it would only push the form off-screen.
        gridTemplateColumns: "minmax(0, 1fr)",
        background: colors.bgPrimary,
        fontFamily: "Inter, system-ui, sans-serif",
      }}
      className="auth-shell"
    >
      <BrandPanel />

      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          padding: "40px 24px",
          minHeight: "100vh",
        }}
      >
        <form
          onSubmit={handleSubmit}
          className="brand-anim-fade-up"
          style={{ width: "100%", maxWidth: 400, display: "flex", flexDirection: "column", gap: 16 }}
        >
          {/* Shown here only on narrow screens, where the brand panel is hidden. */}
          <div className="auth-compact-brand" style={{ marginBottom: 4 }}>
            <BrandLockup size={34} />
          </div>

          <div>
            <h1 style={{ fontSize: 24, fontWeight: 600, color: colors.textPrimary, margin: 0, letterSpacing: -0.4 }}>
              {isLogin ? "Sign in" : "Create your organization"}
            </h1>
            <p style={{ fontSize: 13, color: colors.textSecondary, margin: "6px 0 0" }}>
              {isLogin
                ? "Welcome back. Sign in to your TechCRM workspace."
                : "Set up a workspace and invite your team in minutes."}
            </p>
          </div>

          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            <OAuthButton provider="google" label="Continue with Google" />
            <OAuthButton provider="microsoft" label="Continue with Microsoft" />
          </div>

          <Divider label="or continue with email" />

          {!isLogin && <TextInput label="Organization name" value={organizationName} onChange={setOrganizationName} />}
          {!isLogin && <TextInput label="Full name" value={fullName} onChange={setFullName} />}
          <TextInput label="Work email" type="email" value={email} onChange={setEmail} />
          <TextInput label="Password" type="password" value={password} onChange={setPassword} />

          <button
            type="submit"
            disabled={busy}
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 8,
              background: colors.primary,
              border: "none",
              borderRadius: 6,
              color: "#FFFFFF",
              fontSize: 13,
              fontWeight: 500,
              padding: "11px 14px",
              cursor: busy ? "default" : "pointer",
              opacity: busy ? 0.7 : 1,
              marginTop: 2,
            }}
          >
            {busy && <Spinner />}
            {busy ? "Please wait…" : isLogin ? "Sign in" : "Create organization"}
          </button>

          <div style={{ fontSize: 12, color: colors.textSecondary, textAlign: "center" }}>
            {isLogin ? "New to TechCRM? " : "Already have an account? "}
            <button
              type="button"
              onClick={() => setMode(isLogin ? "signup" : "login")}
              style={{
                background: "transparent",
                border: "none",
                color: colors.primary,
                fontSize: 12,
                fontWeight: 500,
                cursor: "pointer",
                padding: 0,
              }}
            >
              {isLogin ? "Create an organization" : "Sign in"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/** Left-hand brand panel: product framing next to the form, hidden on narrow
 *  screens so the form keeps the full width. */
function BrandPanel() {
  const highlights = [
    { title: "AI lead scoring", body: "Every lead scored and ranked the moment it lands." },
    { title: "Automation that runs itself", body: "Bots enrich records, route cases and chase follow-ups." },
    { title: "One view of the pipeline", body: "Accounts, deals, cases and campaigns in a single workspace." },
  ];

  return (
    <div
      className="auth-brand-panel"
      style={{
        position: "relative",
        overflow: "hidden",
        background: `linear-gradient(150deg, ${colors.primaryDark} 0%, ${colors.primary} 55%, #2C7BC4 100%)`,
        padding: "48px 44px",
        display: "flex",
        flexDirection: "column",
        justifyContent: "space-between",
        minHeight: "100vh",
      }}
    >
      {/* Decorative drifting orbs — aria-hidden, purely atmospheric. */}
      <div
        aria-hidden="true"
        className="brand-anim-drift"
        style={{
          position: "absolute",
          top: -90,
          right: -70,
          width: 320,
          height: 320,
          borderRadius: "50%",
          background: "rgba(255,255,255,0.09)",
        }}
      />
      <div
        aria-hidden="true"
        className="brand-anim-drift"
        style={{
          position: "absolute",
          bottom: -120,
          left: -60,
          width: 280,
          height: 280,
          borderRadius: "50%",
          background: "rgba(255,255,255,0.07)",
          animationDelay: "3s",
        }}
      />

      <div style={{ position: "relative", display: "flex", alignItems: "center", gap: 10 }}>
        <BrandMark size={34} animated />
        <span style={{ fontSize: 19, fontWeight: 600, color: "#FFFFFF", letterSpacing: -0.2 }}>TechCRM</span>
      </div>

      <div style={{ position: "relative", maxWidth: 420 }}>
        <h2
          className="brand-anim-fade-up"
          style={{ fontSize: 30, lineHeight: 1.25, fontWeight: 600, color: "#FFFFFF", margin: "0 0 14px", letterSpacing: -0.6 }}
        >
          The CRM that scores, routes and follows up for you.
        </h2>
        <p
          className="brand-anim-fade-up"
          style={{ fontSize: 14, lineHeight: 1.6, color: "rgba(255,255,255,0.82)", margin: 0, animationDelay: "0.1s" }}
        >
          Capture leads from any channel, let the model rank them, and keep the
          pipeline moving without the manual admin.
        </p>

        <div style={{ display: "flex", flexDirection: "column", gap: 16, marginTop: 32 }}>
          {highlights.map((item, i) => (
            <div
              key={item.title}
              className="brand-anim-fade-up"
              style={{ display: "flex", gap: 12, animationDelay: `${0.2 + i * 0.1}s` }}
            >
              <span
                aria-hidden="true"
                style={{
                  marginTop: 6,
                  width: 6,
                  height: 6,
                  borderRadius: "50%",
                  background: "rgba(255,255,255,0.9)",
                  flexShrink: 0,
                }}
              />
              <div>
                <div style={{ fontSize: 13, fontWeight: 600, color: "#FFFFFF" }}>{item.title}</div>
                <div style={{ fontSize: 12.5, color: "rgba(255,255,255,0.75)", marginTop: 2 }}>{item.body}</div>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div style={{ position: "relative", fontSize: 11.5, color: "rgba(255,255,255,0.6)" }}>
        © {new Date().getFullYear()} TechCRM
      </div>
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
