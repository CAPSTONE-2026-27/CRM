import { colors } from "../../tokens";
import {
  LayoutDashboard,
  UserPlus,
  BarChart3,
  Building2,
  Ticket,
  GitBranch,
  Bot,
  Brain,
  Megaphone,
  PieChart,
  Shield,
} from "lucide-react";

export type ScreenId =
  | "dashboard"
  | "leads"
  | "pipeline"
  | "accounts"
  | "cases"
  | "workflow"
  | "rpa"
  | "copilot"
  | "marketing"
  | "analytics"
  | "security"
  | "profile";

export type Role = "ADMIN" | "MANAGER" | "SALES_REP" | "SUPPORT_AGENT" | "MARKETING";

// Screens with no permission key are always allowed (dashboard, copilot,
// profile). Every other screen maps 1:1 to a per-user permission key that the
// backend enforces with the same name — hiding a page here is UX; the API
// enforces it with 403s.
const SCREEN_PERMISSION: Partial<Record<ScreenId, string>> = {
  leads: "leads",
  pipeline: "pipeline",
  accounts: "accounts",
  cases: "cases",
  workflow: "workflow",
  rpa: "rpa",
  copilot: "copilot",
  marketing: "marketing",
  analytics: "analytics",
  security: "security",
};

export function screenAllowed(screen: ScreenId, role: Role, permissions: string[]): boolean {
  if (role === "ADMIN") return true; // admins always have full access
  const key = SCREEN_PERMISSION[screen];
  return !key || permissions.includes(key);
}

// The full set of assignable permissions with human labels, for the
// create/edit-user permission editors. Keys match the backend PERMISSION_KEYS.
export const PERMISSION_CATALOG: { key: string; label: string }[] = [
  { key: "leads", label: "Lead management" },
  { key: "pipeline", label: "Sales pipeline" },
  { key: "accounts", label: "Accounts & contacts" },
  { key: "cases", label: "Cases & tickets" },
  { key: "workflow", label: "Workflow engine" },
  { key: "rpa", label: "RPA bots" },
  { key: "marketing", label: "Marketing automation" },
  { key: "analytics", label: "Analytics & reporting" },
  { key: "security", label: "Security & audit" },
  { key: "copilot", label: "AI copilot" },
];

// Frontend mirror of the backend's ROLE_DEFAULTS — used to pre-fill the
// permission toggles when an admin picks a role in the create/edit UI.
export const ROLE_DEFAULT_PERMISSIONS: Record<Role, string[]> = {
  ADMIN: PERMISSION_CATALOG.map((p) => p.key),
  MANAGER: PERMISSION_CATALOG.map((p) => p.key),
  SALES_REP: ["leads", "pipeline", "accounts", "copilot"],
  SUPPORT_AGENT: ["cases", "copilot"],
  MARKETING: ["marketing", "analytics", "copilot"],
};

const sections: {
  label: string;
  items: {
    icon: React.ComponentType<{ size?: number; color?: string }>;
    label: string;
    screen: ScreenId;
  }[];
}[] = [
  {
    label: "Core",
    items: [
      { icon: LayoutDashboard, label: "Dashboard", screen: "dashboard" },
      { icon: UserPlus, label: "Lead management", screen: "leads" },
      { icon: BarChart3, label: "Sales pipeline", screen: "pipeline" },
      { icon: Building2, label: "Accounts", screen: "accounts" },
    ],
  },
  {
    label: "Service",
    items: [
      { icon: Ticket, label: "Cases & tickets", screen: "cases" },
      { icon: GitBranch, label: "Workflow engine", screen: "workflow" },
    ],
  },
  {
    label: "Automation",
    items: [
      { icon: Bot, label: "RPA bots", screen: "rpa" },
      { icon: Brain, label: "AI copilot", screen: "copilot" },
      { icon: Megaphone, label: "Marketing automation", screen: "marketing" },
    ],
  },
  {
    label: "Insights",
    items: [
      { icon: PieChart, label: "Analytics", screen: "analytics" },
      { icon: Shield, label: "Security & audit", screen: "security" },
    ],
  },
];

export function Sidebar({
  active,
  role,
  permissions,
  onNavigate,
}: {
  active: ScreenId;
  role: Role;
  permissions: string[];
  onNavigate: (s: ScreenId) => void;
}) {
  return (
    <aside
      style={{
        width: 200,
        flexShrink: 0,
        background: colors.bgSecondary,
        borderRight: `0.5px solid ${colors.border}`,
        height: "100%",
        overflowY: "auto",
      }}
    >
      <div style={{ padding: "12px 16px", fontSize: 18, fontWeight: 600, color: colors.textPrimary }}>
        <span style={{ color: colors.primary }}>Tech</span>CRM
      </div>
      {sections.map((section) => {
        const visibleItems = section.items.filter((item) => screenAllowed(item.screen, role, permissions));
        if (visibleItems.length === 0) return null;
        return (
        <div key={section.label} style={{ padding: "4px 8px" }}>
          <div
            style={{
              fontSize: 10,
              fontWeight: 600,
              color: colors.textTertiary,
              textTransform: "uppercase",
              letterSpacing: 0.5,
              padding: "8px 8px 4px",
            }}
          >
            {section.label}
          </div>
          {visibleItems.map((item) => {
            const isActive = item.screen === active;
            const Icon = item.icon;
            return (
              <button
                key={item.screen}
                onClick={() => onNavigate(item.screen)}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 9,
                  width: "100%",
                  padding: "7px 8px",
                  borderRadius: 6,
                  border: "none",
                  cursor: "pointer",
                  textAlign: "left",
                  background: isActive ? colors.primaryLight : "transparent",
                  color: isActive ? colors.primary : colors.textSecondary,
                  fontSize: 12,
                  fontWeight: isActive ? 600 : 400,
                  marginBottom: 1,
                }}
              >
                <Icon size={15} color={isActive ? colors.primary : colors.textSecondary} />
                {item.label}
              </button>
            );
          })}
        </div>
        );
      })}
    </aside>
  );
}
