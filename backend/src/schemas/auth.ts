import { z } from "zod";
import { PERMISSION_KEYS } from "../lib/permissions.js";

export const signupSchema = z.object({
  organizationName: z.string().min(1),
  fullName: z.string().min(1),
  email: z.string().email(),
  password: z.string().min(8),
});

export const loginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});

export const createUserSchema = z.object({
  fullName: z.string().min(1),
  email: z.string().email(),
  password: z.string().min(8),
  jobTitle: z.string().optional(),
  phone: z.string().optional(),
  department: z.string().optional(),
  role: z.enum(["ADMIN", "MANAGER", "SALES_REP", "SUPPORT_AGENT"]),
  reportingManagerId: z.string().optional(),
  // Explicit per-screen access. Omit to seed from the role's defaults.
  permissions: z.array(z.enum(PERMISSION_KEYS)).optional(),
  permissionScope: z.string().optional(),
  dataRegion: z.string().optional(),
  identityProvider: z.enum(["AZURE_AD", "OKTA", "LOCAL"]).optional(),
  mfaEnabled: z.boolean().optional(),
  ssoEnabled: z.boolean().optional(),
  ldapSyncEnabled: z.boolean().optional(),
  sessionTimeoutMinutes: z.number().int().optional(),
});
