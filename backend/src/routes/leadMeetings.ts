import type { Router } from "express";
import { z } from "zod";
import { prisma } from "../lib/prisma.js";
import { writeAuditLog } from "../lib/audit.js";
import { asyncHandler, HttpError } from "../middleware/errorHandler.js";
import { analyzeMeeting } from "../services/meetingAnalysis.js";

/*
 * Lead Output module — meeting records logged by a sales rep against a lead.
 *
 * Mounted onto the existing leadsRouter, so these inherit its requireAuth +
 * requirePermission("leads") middleware rather than defining a parallel access
 * rule. Paths are two segments ("/:leadId/meetings"), so they never collide
 * with the CRUD router's single-segment "/:id" routes.
 */

const meetingInputSchema = z.object({
  // "YYYY-MM-DD" and "HH:mm" — the native date/time input formats.
  meetingDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Meeting date must be YYYY-MM-DD"),
  meetingTime: z.string().regex(/^\d{2}:\d{2}$/, "Meeting time must be HH:mm"),
  meetingOutput: z.string().trim().min(1, "Meeting output is required"),
});

const saveMeetingSchema = meetingInputSchema.extend({
  // Reviewed (and possibly edited) by the rep before saving.
  aiSummary: z.string().trim().min(1, "Summary is required"),
  previousScore: z.number().int().min(0).max(100).nullable().optional(),
  updatedScore: z.number().int().min(0).max(100),
  scoreLabel: z.enum(["Hot", "Warm", "Cold"]).optional(),
  scoreChangeReason: z.string().optional(),
});

// Fallback when the client didn't carry the model's own label through.
function labelForScore(score: number): "Hot" | "Warm" | "Cold" {
  if (score >= 75) return "Hot";
  if (score >= 45) return "Warm";
  return "Cold";
}

export function registerLeadMeetingRoutes(leadsRouter: Router) {
  async function findLeadOrThrow(leadId: string, organizationId: string) {
    const lead = await prisma.lead.findFirst({ where: { id: leadId, organizationId } });
    if (!lead) throw new HttpError(404, "Lead not found");
    return lead;
  }

  // Meeting history for a lead, newest first. Append-only — nothing here is
  // ever overwritten by a later meeting.
  leadsRouter.get(
    "/:leadId/meetings",
    asyncHandler(async (req, res) => {
      const organizationId = req.auth!.organizationId;
      await findLeadOrThrow(req.params.leadId, organizationId);
      const meetings = await prisma.leadMeeting.findMany({
        where: { leadId: req.params.leadId, organizationId },
        orderBy: [{ meetingDate: "desc" }, { createdAt: "desc" }],
        include: { recordedBy: { select: { fullName: true } } },
      });
      res.json(meetings);
    })
  );

  // Preview step: generate the summary and re-score without persisting, so the
  // rep can review and edit before committing anything.
  leadsRouter.post(
    "/:leadId/meetings/analyze",
    asyncHandler(async (req, res) => {
      const organizationId = req.auth!.organizationId;
      const lead = await findLeadOrThrow(req.params.leadId, organizationId);
      const input = meetingInputSchema.parse(req.body);

      const analysis = await analyzeMeeting({
        lead: {
          fullName: lead.fullName,
          company: lead.company,
          industry: lead.industry,
          product: lead.product,
          estimatedDealValue: lead.estimatedDealValue,
          notes: lead.notes,
          aiScore: lead.aiScore,
        },
        ...input,
      });

      res.json({
        leadId: lead.id,
        leadName: lead.fullName,
        ...input,
        aiSummary: analysis.summary,
        previousScore: lead.aiScore,
        updatedScore: analysis.score,
        scoreDifference: analysis.score - (lead.aiScore ?? analysis.score),
        scoreLabel: analysis.label,
        reasons: analysis.reasons,
      });
    })
  );

  // Save step: appends a new history row and rolls the lead's live score
  // forward to the re-evaluated one.
  leadsRouter.post(
    "/:leadId/meetings",
    asyncHandler(async (req, res) => {
      const organizationId = req.auth!.organizationId;
      const lead = await findLeadOrThrow(req.params.leadId, organizationId);
      const body = saveMeetingSchema.parse(req.body);

      const meetingDate = new Date(`${body.meetingDate}T00:00:00Z`);
      if (Number.isNaN(meetingDate.getTime())) {
        throw new HttpError(400, "Meeting date is not a valid date");
      }

      const label = body.scoreLabel ?? labelForScore(body.updatedScore);
      // `previousScore` is snapshotted from the lead itself, not trusted from
      // the client, so the history can't be rewritten by a crafted request.
      const previousScore = lead.aiScore;

      const [meeting] = await prisma.$transaction([
        prisma.leadMeeting.create({
          data: {
            organizationId,
            leadId: lead.id,
            recordedById: req.auth!.sub,
            meetingDate,
            meetingTime: body.meetingTime,
            meetingOutput: body.meetingOutput,
            aiSummary: body.aiSummary,
            previousScore,
            updatedScore: body.updatedScore,
            scoreChangeReason: body.scoreChangeReason,
            aiModelVersion: process.env.AI_MODEL_NAME ?? null,
          },
          include: { recordedBy: { select: { fullName: true } } },
        }),
        prisma.lead.update({
          where: { id: lead.id },
          data: {
            aiScore: body.updatedScore,
            aiScoreLabel: `${label} lead`,
            aiScoreReason: body.scoreChangeReason || body.aiSummary.slice(0, 500),
            status: label.toUpperCase() as "HOT" | "WARM" | "COLD",
          },
        }),
      ]);

      await writeAuditLog({
        organizationId,
        actorType: "user",
        actorUserId: req.auth!.sub,
        event: "Meeting logged",
        detail:
          `${lead.fullName} (${lead.company}) — score ${previousScore ?? "unscored"} → ${body.updatedScore}`,
        severity: "info",
        relatedEntityType: "Lead",
        relatedEntityId: lead.id,
        aiModelVersion: process.env.AI_MODEL_NAME ?? undefined,
      });

      res.status(201).json(meeting);
    })
  );
}
