import { useState } from "react";
import { colors } from "./tokens";
import { Sidebar, ScreenId, screenAllowed } from "./components/crm/Sidebar";
import { Topbar } from "./components/crm/Topbar";
import { ErrorBoundary } from "./components/crm/ErrorBoundary";
import { BrandLoader } from "./components/crm/BrandLogo";
import {
  Dashboard,
  Leads,
  Pipeline,
  Accounts,
  RPA,
  Copilot,
  Marketing,
  Analytics,
  Security,
} from "./screens/MainScreens";
import { Wizard, WizardId, wizardMeta } from "./screens/Wizards";
import { AuthScreen, ForceChangePasswordScreen } from "./screens/Auth";
import { Profile } from "./screens/Profile";
import { useAuth } from "./lib/auth";
import { Toaster } from "sonner";

const screenMeta: Record<ScreenId, { title: string }> = {
  dashboard: { title: "Dashboard" },
  leads: { title: "Lead management" },
  pipeline: { title: "Sales pipeline" },
  accounts: { title: "Accounts & contacts" },
  rpa: { title: "RPA bot control room" },
  copilot: { title: "Deal coach" },
  marketing: { title: "Marketing automation" },
  analytics: { title: "Analytics & reporting" },
  security: { title: "Security & audit" },
  profile: { title: "My profile" },
};

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts[1]?.[0] ?? "")).toUpperCase() || "—";
}

export default function App() {
  const { status, user, logout } = useAuth();
  const [screen, setScreen] = useState<ScreenId>("dashboard");
  const [wizard, setWizard] = useState<WizardId | null>(null);

  if (status === "loading") {
    return <BrandLoader />;
  }

  if (status === "unauthenticated" || !user) {
    return (
      <>
        <AuthScreen />
        <Toaster position="top-right" richColors closeButton />
      </>
    );
  }

  if (user.mustChangePassword) {
    return (
      <>
        <ForceChangePasswordScreen />
        <Toaster position="top-right" richColors closeButton />
      </>
    );
  }

  const openWizard = (w: WizardId) => setWizard(w);
  const closeWizard = () => setWizard(null);

  const navigate = (s: ScreenId) => {
    setWizard(null);
    setScreen(s);
  };

  const renderScreen = () => {
    if (!screenAllowed(screen, user.role, user.permissions)) {
      return (
        <div style={{ padding: 32, textAlign: "center", color: colors.textSecondary, fontSize: 13 }}>
          You don't have access to this section. Ask an admin if you think this is a mistake.
        </div>
      );
    }
    switch (screen) {
      case "dashboard":
        return <Dashboard />;
      case "leads":
        return <Leads onNavigate={openWizard} />;
      case "pipeline":
        return <Pipeline onNavigate={openWizard} />;
      case "accounts":
        return <Accounts onNavigate={openWizard} />;
      case "rpa":
        return <RPA />;
      case "copilot":
        return <Copilot />;
      case "marketing":
        return <Marketing onNavigate={openWizard} />;
      case "analytics":
        return <Analytics />;
      case "security":
        return <Security onNavigate={openWizard} />;
      case "profile":
        return <Profile />;
    }
  };

  const meta = wizard ? wizardMeta[wizard] : screenMeta[screen];

  return (
    <div
      style={{
        display: "flex",
        height: "100vh",
        width: "100%",
        background: colors.bgPrimary,
        color: colors.textPrimary,
        fontFamily: "Inter, system-ui, sans-serif",
        overflow: "hidden",
      }}
    >
      <Sidebar active={screen} role={user.role} permissions={user.permissions} onNavigate={navigate} />
      <div style={{ flex: 1, display: "flex", flexDirection: "column", minWidth: 0 }}>
        <Topbar
          title={meta.title}
          onBack={wizard ? closeWizard : undefined}
          userName={user.fullName}
          userEmail={user.email}
          userRole={user.role}
          userInitials={initials(user.fullName)}
          avatarUrl={user.avatarUrl}
          onViewProfile={() => navigate("profile")}
          // Settings is the route to Security & audit, which is where users are
          // added — POST /api/users is hasRole('ADMIN'). Managers keep it too,
          // since they already reach that screen from the sidebar. Everyone else
          // gets no item at all: it used to send them to their own profile,
          // duplicating the entry directly above it.
          onOpenSettings={
            user.role === "ADMIN" || user.role === "MANAGER" ? () => navigate("security") : undefined
          }
          onLogout={logout}
        />
        <main style={{ flex: 1, overflowY: "auto", padding: 20, background: colors.bgPrimary }}>
          {/* Scoped per screen/wizard so a crash is contained and navigating away clears it. */}
          <ErrorBoundary resetKey={wizard ?? screen}>
            {wizard ? <Wizard id={wizard} onCancel={closeWizard} /> : renderScreen()}
          </ErrorBoundary>
        </main>
      </div>
      <Toaster position="top-right" richColors closeButton />
    </div>
  );
}
