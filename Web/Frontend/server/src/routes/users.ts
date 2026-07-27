import { Router } from "express";
import bcrypt from "bcryptjs";
import { z } from "zod";
import { prisma } from "../lib/prisma.js";
import { requireAuth, requireRole } from "../middleware/auth.js";
import { asyncHandler, HttpError } from "../middleware/errorHandler.js";
import { createUserSchema } from "../schemas/auth.js";
import { defaultPermissionsForRole, PERMISSION_KEYS } from "../lib/permissions.js";

export const usersRouter = Router();
usersRouter.use(requireAuth);

const publicUser = {
  id: true,
  fullName: true,
  email: true,
  jobTitle: true,
  phone: true,
  department: true,
  role: true,
  status: true,
  permissions: true,
  reportingManagerId: true,
  identityProvider: true,
  authProvider: true,
  avatarUrl: true,
  emailVerified: true,
  mfaEnabled: true,
  ssoEnabled: true,
  ldapSyncEnabled: true,
  createdAt: true,
} as const;

usersRouter.get(
  "/",
  asyncHandler(async (req, res) => {
    const users = await prisma.user.findMany({
      where: { organizationId: req.auth!.organizationId },
      select: publicUser,
      orderBy: { fullName: "asc" },
    });
    res.json(users);
  })
);

usersRouter.get(
  "/:id",
  asyncHandler(async (req, res) => {
    const user = await prisma.user
      .findFirstOrThrow({ where: { id: req.params.id, organizationId: req.auth!.organizationId }, select: publicUser })
      .catch(() => {
        throw new HttpError(404, "Not found");
      });
    res.json(user);
  })
);

// Mirrors the F02 "Create user" wizard (details, role & access, identity provider).
usersRouter.post(
  "/",
  requireRole("ADMIN"),
  asyncHandler(async (req, res) => {
    const body = createUserSchema.parse(req.body);
    const organizationId = req.auth!.organizationId;
    const existing = await prisma.user.findFirst({ where: { email: body.email } });
    if (existing) throw new HttpError(409, "Email already in use");

    const passwordHash = await bcrypt.hash(body.password, 12);
    const { password: _password, permissions, ...rest } = body;
    const user = await prisma.user.create({
      data: {
        ...rest,
        organizationId,
        passwordHash,
        // Explicit permissions if provided, else seed from the chosen role.
        permissions: permissions ?? defaultPermissionsForRole(body.role),
      },
      select: publicUser,
    });
    res.status(201).json(user);
  })
);

const selfUpdateSchema = z.object({
  fullName: z.string().min(1).optional(),
  phone: z.string().optional(),
  jobTitle: z.string().optional(),
  department: z.string().optional(),
  avatarUrl: z.string().optional(),
});

// Self-service profile edit — registered before "/:id" so "/me" doesn't get
// swallowed by that wildcard route. Deliberately excludes role/email/password;
// those go through the admin-only "/:id" route or /auth/change-password.
usersRouter.patch(
  "/me",
  asyncHandler(async (req, res) => {
    const body = selfUpdateSchema.parse(req.body);
    const user = await prisma.user.update({
      where: { id: req.auth!.sub },
      data: body,
      select: publicUser,
    });
    res.json(user);
  })
);

usersRouter.patch(
  "/:id",
  requireRole("ADMIN"),
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    await prisma.user
      .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
      .catch(() => {
        throw new HttpError(404, "Not found");
      });
    const body = createUserSchema.partial().parse(req.body);
    const { password, ...rest } = body;
    const user = await prisma.user.update({
      where: { id: req.params.id },
      data: { ...rest, ...(password ? { passwordHash: await bcrypt.hash(password, 12) } : {}) },
      select: publicUser,
    });
    res.json(user);
  })
);

// Activate/deactivate an account. A deactivated user keeps all their records
// but is refused at login (see the status check in routes/auth.ts). Mirrors the
// delete route's safety rails so an org can't lock itself out.
usersRouter.patch(
  "/:id/status",
  requireRole("ADMIN"),
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const { status } = z.object({ status: z.enum(["ACTIVE", "INACTIVE"]) }).parse(req.body);

    const target = await prisma.user
      .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
      .catch(() => {
        throw new HttpError(404, "Not found");
      });

    if (status === "INACTIVE") {
      if (target.id === req.auth!.sub) {
        throw new HttpError(400, "You can't deactivate your own account");
      }
      if (target.role === "ADMIN") {
        const otherActiveAdmins = await prisma.user.count({
          where: { organizationId, role: "ADMIN", status: "ACTIVE", id: { not: target.id } },
        });
        if (otherActiveAdmins === 0) {
          throw new HttpError(400, "Can't deactivate the last active admin — promote another user to Admin first");
        }
      }
      // Sessions outlive the change otherwise: access tokens stay valid for
      // their TTL, so drop refresh tokens to end the session at renewal.
      await prisma.refreshToken.deleteMany({ where: { userId: target.id } });
    }

    const user = await prisma.user.update({
      where: { id: target.id },
      data: { status },
      select: publicUser,
    });
    res.json(user);
  })
);

usersRouter.delete(
  "/:id",
  requireRole("ADMIN"),
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const target = await prisma.user
      .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
      .catch(() => {
        throw new HttpError(404, "Not found");
      });

    if (target.id === req.auth!.sub) {
      throw new HttpError(400, "You can't remove your own account");
    }
    if (target.role === "ADMIN") {
      const otherAdmins = await prisma.user.count({
        where: { organizationId, role: "ADMIN", id: { not: target.id } },
      });
      if (otherAdmins === 0) {
        throw new HttpError(400, "Can't remove the last admin — promote another user to Admin first");
      }
    }

    await prisma.user.delete({ where: { id: target.id } });
    res.status(204).send();
  })
);
