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
  Cases,
  Workflow,
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

const screenMeta: Record<ScreenId, { title: string; badge: string }> = {
  dashboard: { title: "Dashboard", badge: "Overview" },
  leads: { title: "Lead management", badge: "Module 2" },
  pipeline: { title: "Sales pipeline", badge: "Module 3" },
  accounts: { title: "Accounts & contacts", badge: "Module 4" },
  cases: { title: "Cases & tickets", badge: "Module 5 & 6" },
  workflow: { title: "Workflow engine", badge: "Module 9" },
  rpa: { title: "RPA bot control room", badge: "Module 7" },
  copilot: { title: "Deal coach", badge: "Module 10" },
  marketing: { title: "Marketing automation", badge: "Module 11" },
  analytics: { title: "Analytics & reporting", badge: "Module 8" },
  security: { title: "Security & audit", badge: "Module 10" },
  profile: { title: "My profile", badge: "Account" },
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
      case "cases":
        return <Cases />;
      case "workflow":
        return <Workflow onNavigate={openWizard} />;
      case "rpa":
        return <RPA onNavigate={openWizard} />;
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
          badge={meta.badge}
          onBack={wizard ? closeWizard : undefined}
          userName={user.fullName}
          userEmail={user.email}
          userRole={user.role}
          userInitials={initials(user.fullName)}
          avatarUrl={user.avatarUrl}
          onViewProfile={() => navigate("profile")}
          onOpenSettings={() => navigate(user.role === "ADMIN" || user.role === "MANAGER" ? "security" : "profile")}
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
