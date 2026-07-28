import type { AccessTokenPayload } from "./jwt.js";

type Role = AccessTokenPayload["role"];

// Canonical permission keys. These intentionally match the frontend's
// ScreenId values (src/app/components/crm/Sidebar.tsx) so a permission maps
// 1:1 to a screen. "dashboard" and "profile" are always allowed and have no
// key. Backend resources map to a key via RESOURCE below.
export const PERMISSION_KEYS = [
  "leads",
  "pipeline",
  "accounts",
  "cases",
  "workflow",
  "rpa",
  "marketing",
  "analytics",
  "security",
  "copilot",
] as const;

export type PermissionKey = (typeof PERMISSION_KEYS)[number];

export function isPermissionKey(value: string): value is PermissionKey {
  return (PERMISSION_KEYS as readonly string[]).includes(value);
}

// Sensible starting point when a user is created with a given role — the
// admin can then override per user. Kept in sync with the migration backfill.
const ROLE_DEFAULTS: Record<Role, PermissionKey[]> = {
  ADMIN: [...PERMISSION_KEYS],
  MANAGER: [...PERMISSION_KEYS],
  SALES_REP: ["leads", "pipeline", "accounts", "copilot"],
  SUPPORT_AGENT: ["cases", "copilot"],
};

export function defaultPermissionsForRole(role: Role): PermissionKey[] {
  return [...ROLE_DEFAULTS[role]];
}

// A user can access a resource if they're an ADMIN (escape hatch — admins
// always have full access and can't lock themselves out) or their granted
// permissions include the required key.
export function hasPermission(auth: AccessTokenPayload, key: PermissionKey): boolean {
  if (auth.role === "ADMIN") return true;
  return auth.permissions?.includes(key) ?? false;
}
