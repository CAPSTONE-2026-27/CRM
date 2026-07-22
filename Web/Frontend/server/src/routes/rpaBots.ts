import { Router } from "express";
import { prisma } from "../lib/prisma.js";
import { crudRouter } from "../lib/crudRouter.js";
import { requireAuth, requirePermission } from "../middleware/auth.js";
import { asyncHandler, HttpError } from "../middleware/errorHandler.js";
import { rpaBotCreateSchema, rpaBotUpdateSchema } from "../schemas/automation.js";
import { enqueueBotRun } from "../queue/queues.js";

export const rpaBotsRouter = crudRouter(prisma.rpaBot, {
  createSchema: rpaBotCreateSchema,
  updateSchema: rpaBotUpdateSchema,
  permission: "rpa",
});

// Mirrors the F06 "Deploy bot" wizard's final step.
rpaBotsRouter.post(
  "/:id/deploy",
  requireAuth,
  requirePermission("rpa"),
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const bot = await prisma.rpaBot
      .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
      .catch(() => {
        throw new HttpError(404, "Not found");
      });
    const updated = await prisma.rpaBot.update({ where: { id: bot.id }, data: { status: "DEPLOYED" } });
    res.json(updated);
  })
);

// Manual on-demand run, queued through the same worker path as scheduled/event-triggered runs.
rpaBotsRouter.post(
  "/:id/run",
  requireAuth,
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const bot = await prisma.rpaBot
      .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
      .catch(() => {
        throw new HttpError(404, "Not found");
      });
    await enqueueBotRun(bot.name, { organizationId, botId: bot.id, triggeredBy: "manual", payload: req.body ?? {} });
    res.status(202).json({ queued: true });
  })
);

export const rpaBotRunsRouter = Router();
rpaBotRunsRouter.use(requireAuth);
rpaBotRunsRouter.use(requirePermission("rpa"));

rpaBotRunsRouter.get(
  "/",
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const botId = typeof req.query.botId === "string" ? req.query.botId : undefined;
    const runs = await prisma.rpaBotRun.findMany({
      where: { organizationId, ...(botId ? { botId } : {}) },
      include: { bot: { select: { name: true, platform: true } } },
      orderBy: { startedAt: "desc" },
      take: 100,
    });
    res.json(runs);
  })
);
