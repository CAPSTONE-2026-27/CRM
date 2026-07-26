import { Router } from "express";
import { prisma } from "../lib/prisma.js";
import { requireAuth, requirePermission } from "../middleware/auth.js";
import { asyncHandler } from "../middleware/errorHandler.js";

export const auditLogRouter = Router();
auditLogRouter.use(requireAuth);

auditLogRouter.get(
  "/",
  requirePermission("security"),
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const severity = typeof req.query.severity === "string" ? req.query.severity : undefined;
    const rows = await prisma.auditLog.findMany({
      where: { organizationId, ...(severity ? { severity } : {}) },
      include: { actorUser: { select: { fullName: true } } },
      orderBy: { occurredAt: "desc" },
      take: 200,
    });
    // Frontend expects a Spring-Data-style page ({ content, ... }), not a bare array.
    res.json({ content: rows, page: 0, size: rows.length, totalElements: rows.length, totalPages: 1 });
  })
);
