import { Router } from "express";
import bcrypt from "bcryptjs";
import { z } from "zod";
import { prisma } from "../lib/prisma.js";
import { requireAuth } from "../middleware/auth.js";
import { asyncHandler, HttpError } from "../middleware/errorHandler.js";
import { signAccessToken, signRefreshToken, verifyRefreshToken } from "../lib/jwt.js";
import { sha256Hex } from "../lib/hash.js";
import { signupSchema, loginSchema } from "../schemas/auth.js";
import { defaultPermissionsForRole } from "../lib/permissions.js";
import { passport, googleOAuthEnabled, microsoftOAuthEnabled } from "../lib/passport.js";
import type { User } from "@prisma/client";

export const authRouter = Router();

const REFRESH_COOKIE = "refreshToken";
const REFRESH_COOKIE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

async function issueSession(
  res: import("express").Response,
  user: { id: string; organizationId: string; role: string; permissions: string[] }
) {
  const accessToken = signAccessToken({
    sub: user.id,
    organizationId: user.organizationId,
    role: user.role as "ADMIN" | "MANAGER" | "SALES_REP" | "SUPPORT_AGENT",
    permissions: user.permissions,
  });
  const refreshToken = signRefreshToken(user.id);
  await prisma.refreshToken.create({
    data: {
      userId: user.id,
      tokenHash: sha256Hex(refreshToken),
      expiresAt: new Date(Date.now() + REFRESH_COOKIE_MAX_AGE_MS),
    },
  });
  res.cookie(REFRESH_COOKIE, refreshToken, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    maxAge: REFRESH_COOKIE_MAX_AGE_MS,
  });
  return accessToken;
}

// Bootstraps a brand-new organization with its first admin user.
authRouter.post(
  "/signup",
  asyncHandler(async (req, res) => {
    const body = signupSchema.parse(req.body);
    const existing = await prisma.user.findFirst({ where: { email: body.email } });
    if (existing) throw new HttpError(409, "Email already in use");

    const passwordHash = await bcrypt.hash(body.password, 12);
    const { user } = await prisma.$transaction(async (tx) => {
      const organization = await tx.organization.create({ data: { name: body.organizationName } });
      const user = await tx.user.create({
        data: {
          organizationId: organization.id,
          email: body.email,
          fullName: body.fullName,
          passwordHash,
          role: "ADMIN",
          permissions: defaultPermissionsForRole("ADMIN"),
        },
      });
      // Register the 3 built-in RPA bots this platform ships with, so the
      // lead-enrichment / follow-up-sequencing / case-routing triggers have
      // a registry row to enqueue against from day one.
      await tx.rpaBot.createMany({
        data: [
          { organizationId: organization.id, name: "Lead enrichment bot", platform: "UIPATH", triggerSource: "Lead created", status: "REGISTERED" },
          { organizationId: organization.id, name: "Follow-up sequencing bot", platform: "UIPATH", triggerSource: "Scheduled (hourly)", status: "REGISTERED" },
          { organizationId: organization.id, name: "Case routing bot", platform: "UIPATH", triggerSource: "Case created", status: "REGISTERED" },
        ],
      });
      return { organization, user };
    });

    const accessToken = await issueSession(res, user);
    res.status(201).json({ accessToken, user: { id: user.id, fullName: user.fullName, email: user.email, role: user.role } });
  })
);

authRouter.post(
  "/login",
  asyncHandler(async (req, res) => {
    const body = loginSchema.parse(req.body);
    const user = await prisma.user.findFirst({ where: { email: body.email } });
    if (!user || !user.passwordHash) throw new HttpError(401, "Invalid email or password");
    const valid = await bcrypt.compare(body.password, user.passwordHash);
    if (!valid) throw new HttpError(401, "Invalid email or password");

    const accessToken = await issueSession(res, user);
    res.json({ accessToken, user: { id: user.id, fullName: user.fullName, email: user.email, role: user.role } });
  })
);

// --- OAuth (Google / Microsoft) ---
// Server-driven redirect flow: the button on the frontend does a full
// navigation here, not a popup or client-side SDK. On success we set the
// same httpOnly refresh cookie normal login uses and redirect back to the
// frontend with no token in the URL — the app's existing silent-refresh-on-
// mount flow then picks up the session.
const FRONTEND_URL = process.env.FRONTEND_URL ?? "http://localhost:5173";

function notConfigured(provider: string) {
  return (_req: import("express").Request, res: import("express").Response) =>
    res.status(501).json({ error: `${provider} login is not configured on this server` });
}

authRouter.get(
  "/google",
  googleOAuthEnabled ? passport.authenticate("google", { session: false }) : notConfigured("Google")
);

authRouter.get(
  "/google/callback",
  googleOAuthEnabled
    ? passport.authenticate("google", { session: false, failureRedirect: `${FRONTEND_URL}/?oauth_error=google` })
    : notConfigured("Google"),
  asyncHandler(async (req, res) => {
    await issueSession(res, req.user as User);
    res.redirect(FRONTEND_URL);
  })
);

authRouter.get(
  "/microsoft",
  microsoftOAuthEnabled ? passport.authenticate("microsoft", { session: false }) : notConfigured("Microsoft")
);

authRouter.get(
  "/microsoft/callback",
  microsoftOAuthEnabled
    ? passport.authenticate("microsoft", { session: false, failureRedirect: `${FRONTEND_URL}/?oauth_error=microsoft` })
    : notConfigured("Microsoft"),
  asyncHandler(async (req, res) => {
    await issueSession(res, req.user as User);
    res.redirect(FRONTEND_URL);
  })
);

const changePasswordSchema = z.object({
  currentPassword: z.string().min(1),
  newPassword: z.string().min(8),
});

authRouter.post(
  "/change-password",
  requireAuth,
  asyncHandler(async (req, res) => {
    const body = changePasswordSchema.parse(req.body);
    const user = await prisma.user.findUniqueOrThrow({ where: { id: req.auth!.sub } });
    if (!user.passwordHash) {
      throw new HttpError(400, "This account signs in via Google/Microsoft and has no password to change");
    }
    const valid = await bcrypt.compare(body.currentPassword, user.passwordHash);
    if (!valid) throw new HttpError(401, "Current password is incorrect");

    const passwordHash = await bcrypt.hash(body.newPassword, 12);
    await prisma.user.update({ where: { id: user.id }, data: { passwordHash } });
    res.status(204).send();
  })
);

authRouter.post(
  "/refresh",
  asyncHandler(async (req, res) => {
    const token = req.cookies?.[REFRESH_COOKIE];
    if (!token) throw new HttpError(401, "Missing refresh token");

    let payload: { sub: string };
    try {
      payload = verifyRefreshToken(token);
    } catch {
      throw new HttpError(401, "Invalid refresh token");
    }

    const tokenHash = sha256Hex(token);
    const stored = await prisma.refreshToken.findFirst({
      where: { userId: payload.sub, tokenHash, revoked: false, expiresAt: { gt: new Date() } },
    });
    if (!stored) throw new HttpError(401, "Refresh token not recognized");

    const user = await prisma.user.findUniqueOrThrow({ where: { id: payload.sub } });

    // Rotate: revoke the used refresh token and issue a new one.
    await prisma.refreshToken.update({ where: { id: stored.id }, data: { revoked: true } });
    const accessToken = await issueSession(res, user);
    res.json({ accessToken });
  })
);

authRouter.get(
  "/me",
  requireAuth,
  asyncHandler(async (req, res) => {
    const user = await prisma.user.findUniqueOrThrow({ where: { id: req.auth!.sub } });
    res.json({
      id: user.id,
      fullName: user.fullName,
      email: user.email,
      role: user.role,
      permissions: user.permissions,
      avatarUrl: user.avatarUrl,
      authProvider: user.authProvider,
      emailVerified: user.emailVerified,
      phone: user.phone,
      jobTitle: user.jobTitle,
      department: user.department,
    });
  })
);

authRouter.post(
  "/logout",
  asyncHandler(async (req, res) => {
    const token = req.cookies?.[REFRESH_COOKIE];
    if (token) {
      const tokenHash = sha256Hex(token);
      await prisma.refreshToken.updateMany({ where: { tokenHash }, data: { revoked: true } });
    }
    res.clearCookie(REFRESH_COOKIE);
    res.status(204).send();
  })
);
