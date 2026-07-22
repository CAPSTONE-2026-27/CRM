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
