import { Router } from "express";
import { prisma } from "../lib/prisma.js";
import { requireAuth, requirePermission } from "../middleware/auth.js";
import { asyncHandler } from "../middleware/errorHandler.js";

export const analyticsRouter = Router();
analyticsRouter.use(requireAuth);

// Threshold below which an account's AI sentiment score counts as churn risk.
const CHURN_RISK_SENTIMENT_THRESHOLD = 50;

analyticsRouter.get(
  "/dashboard",
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;

    const [totalLeads, pipelineAgg, openCases, botCounts, recentLeads, pipelineByStage, recentBotRuns] =
      await Promise.all([
        prisma.lead.count({ where: { organizationId } }),
        prisma.deal.aggregate({
          where: { organizationId, stage: { notIn: ["CLOSED_WON", "CLOSED_LOST"] } },
          _sum: { value: true },
        }),
        prisma.case.count({ where: { organizationId, status: { notIn: ["RESOLVED", "CLOSED"] } } }),
        prisma.rpaBot.groupBy({ by: ["status"], where: { organizationId }, _count: { _all: true } }),
        prisma.lead.findMany({ where: { organizationId }, orderBy: { createdAt: "desc" }, take: 5 }),
        prisma.deal.groupBy({
          by: ["stage"],
          where: { organizationId, stage: { notIn: ["CLOSED_WON", "CLOSED_LOST"] } },
          _sum: { value: true },
          _count: { _all: true },
        }),
        prisma.rpaBotRun.findMany({
          where: { organizationId },
          include: { bot: { select: { name: true } } },
          orderBy: { startedAt: "desc" },
          take: 5,
        }),
      ]);

    const totalBots = botCounts.reduce((sum, b) => sum + b._count._all, 0);
    const activeBots = botCounts.find((b) => b.status === "RUNNING")?._count._all ?? 0;

    res.json({
      metrics: {
        totalLeads,
        pipelineValue: pipelineAgg._sum.value ?? 0,
        openCases,
        rpaBotsActive: activeBots,
        rpaBotsTotal: totalBots,
      },
      recentLeads,
      pipelineByStage,
      recentBotRuns,
    });
  })
);

analyticsRouter.get(
  "/reporting",
  requirePermission("analytics"),
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const now = new Date();
    const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
    const ninetyDaysAgo = new Date(now.getTime() - 90 * 24 * 60 * 60 * 1000);

    const [revenueMtd, closedWon90d, closedLost90d, avgDealSize, churnRiskCount, revenueTrend] = await Promise.all([
      prisma.deal.aggregate({
        where: { organizationId, stage: "CLOSED_WON", updatedAt: { gte: monthStart } },
        _sum: { value: true },
      }),
      prisma.deal.count({ where: { organizationId, stage: "CLOSED_WON", updatedAt: { gte: ninetyDaysAgo } } }),
      prisma.deal.count({ where: { organizationId, stage: "CLOSED_LOST", updatedAt: { gte: ninetyDaysAgo } } }),
      prisma.deal.aggregate({ where: { organizationId, stage: "CLOSED_WON" }, _avg: { value: true } }),
      prisma.account.count({ where: { organizationId, aiSentimentScore: { lt: CHURN_RISK_SENTIMENT_THRESHOLD } } }),
      prisma.$queryRaw<{ month: Date; revenue: number }[]>`
        SELECT date_trunc('month', "updatedAt") AS month, SUM(value) AS revenue
        FROM "Deal"
        WHERE "organizationId" = ${organizationId} AND stage = 'CLOSED_WON'
          AND "updatedAt" >= ${new Date(now.getFullYear(), now.getMonth() - 5, 1)}
        GROUP BY month
        ORDER BY month ASC
      `,
    ]);

    const totalClosed = closedWon90d + closedLost90d;
    const winRatePct = totalClosed === 0 ? 0 : Math.round((closedWon90d / totalClosed) * 100);

    res.json({
      revenueMtd: revenueMtd._sum.value ?? 0,
      winRatePct,
      avgDealSize: avgDealSize._avg.value ?? 0,
      churnRiskCount,
      revenueTrend,
    });
  })
);

analyticsRouter.get(
  "/security",
  requirePermission("security"),
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const last24h = new Date(Date.now() - 24 * 60 * 60 * 1000);

    const [auditEvents24h, failedLogins24h, totalUsers, mfaUsers] = await Promise.all([
      prisma.auditLog.count({ where: { organizationId, occurredAt: { gte: last24h } } }),
      prisma.auditLog.count({
        where: { organizationId, occurredAt: { gte: last24h }, event: { contains: "Failed login" } },
      }),
      prisma.user.count({ where: { organizationId } }),
      prisma.user.count({ where: { organizationId, mfaEnabled: true } }),
    ]);

    res.json({
      auditEvents24h,
      failedLogins24h,
      mfaCoveragePct: totalUsers === 0 ? 0 : Math.round((mfaUsers / totalUsers) * 100),
    });
  })
);
