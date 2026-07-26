import { z } from "zod";
import { Prisma, type LeadStatus } from "@prisma/client";
import { prisma } from "../lib/prisma.js";
import { crudRouter } from "../lib/crudRouter.js";
import { parseCsv, toCsv } from "../lib/csv.js";
import { getScoringConversations } from "../lib/scoringLog.js";
import { getAiProvider } from "../services/aiProvider.js";
import { asyncHandler, HttpError } from "../middleware/errorHandler.js";
import { triggerBotByName } from "../queue/queues.js";
import {
  accountCreateSchema,
  accountUpdateSchema,
  contactCreateSchema,
  contactUpdateSchema,
  leadCreateSchema,
  leadUpdateSchema,
  dealCreateSchema,
  dealUpdateSchema,
  caseCreateSchema,
  caseUpdateSchema,
  campaignCreateSchema,
  campaignUpdateSchema,
} from "../schemas/crm.js";

// Per-user permission key required for each resource, mirrored by the
// frontend's permission → screen mapping (src/app/components/crm/Sidebar.tsx).
// ADMIN bypasses; everyone else is gated by their granted permissions.
export const accountsRouter = crudRouter(prisma.account, {
  createSchema: accountCreateSchema,
  updateSchema: accountUpdateSchema,
  permission: "accounts",
});

export const contactsRouter = crudRouter(prisma.contact, {
  createSchema: contactCreateSchema,
  updateSchema: contactUpdateSchema,
  permission: "accounts",
});

const LEAD_STATUSES = new Set<LeadStatus>(["NEW", "WARM", "HOT", "COLD"]);

// Shared by the paginated list and the CSV export so both agree on exactly
// which leads match a given set of query params.
function buildLeadWhere(req: import("express").Request): Prisma.LeadWhereInput {
  const organizationId = req.auth!.organizationId;
  const q = typeof req.query.q === "string" ? req.query.q.trim() : "";
  const statusParam = typeof req.query.status === "string" ? req.query.status.toUpperCase() : "";
  const assignedToId = typeof req.query.assignedToId === "string" ? req.query.assignedToId : undefined;
  const sourceChannel = typeof req.query.sourceChannel === "string" ? req.query.sourceChannel : undefined;
  const industry = typeof req.query.industry === "string" ? req.query.industry : undefined;
  const createdFrom = typeof req.query.createdFrom === "string" ? new Date(req.query.createdFrom) : undefined;
  const createdTo = typeof req.query.createdTo === "string" ? new Date(req.query.createdTo) : undefined;

  const createdAt: Prisma.DateTimeFilter = {};
  if (createdFrom && !Number.isNaN(createdFrom.getTime())) createdAt.gte = createdFrom;
  if (createdTo && !Number.isNaN(createdTo.getTime())) createdAt.lte = createdTo;

  return {
    organizationId,
    ...(statusParam && LEAD_STATUSES.has(statusParam as LeadStatus) ? { status: statusParam as LeadStatus } : {}),
    ...(assignedToId ? { assignedToId } : {}),
    ...(sourceChannel ? { sourceChannel } : {}),
    ...(industry ? { industry } : {}),
    ...(Object.keys(createdAt).length ? { createdAt } : {}),
    ...(q
      ? {
          OR: [
            { fullName: { contains: q, mode: "insensitive" } },
            { company: { contains: q, mode: "insensitive" } },
            { product: { contains: q, mode: "insensitive" } },
          ],
        }
      : {}),
  };
}

// sort is "field,dir" (e.g. "createdAt,desc"); default newest first.
function buildLeadOrderBy(req: import("express").Request): Prisma.LeadOrderByWithRelationInput {
  if (typeof req.query.sort === "string" && req.query.sort.includes(",")) {
    const [field, dir] = req.query.sort.split(",");
    if (field) return { [field]: dir === "asc" ? "asc" : "desc" };
  }
  return { createdAt: "desc" };
}

// Paginated + filtered lead list — the frontend Leads screen expects a
// Spring-Data-style page ({ content, page, size, totalElements, totalPages })
// rather than the plain array the generic crudRouter returns.
async function listLeads(req: import("express").Request, res: import("express").Response) {
  const page = Math.max(0, Number(req.query.page) || 0);
  const size = Math.min(100, Math.max(1, Number(req.query.size) || 20));
  const where = buildLeadWhere(req);
  const orderBy = buildLeadOrderBy(req);

  const [content, totalElements] = await Promise.all([
    prisma.lead.findMany({ where, orderBy, skip: page * size, take: size }),
    prisma.lead.count({ where }),
  ]);

  res.json({ content, page, size, totalElements, totalPages: Math.ceil(totalElements / size) });
}

export const leadsRouter = crudRouter(prisma.lead, {
  createSchema: leadCreateSchema,
  updateSchema: leadUpdateSchema,
  permission: "leads",
  list: listLeads,
  collectionRoutes: (router) => {
    router.get(
      "/stats",
      asyncHandler(async (req, res) => {
        const organizationId = req.auth!.organizationId;
        const [totalLeads, aiScored, csvImported, botImported] = await Promise.all([
          prisma.lead.count({ where: { organizationId } }),
          prisma.lead.count({ where: { organizationId, aiScore: { not: null } } }),
          prisma.lead.count({ where: { organizationId, sourceChannel: "CSV import" } }),
          prisma.lead.count({ where: { organizationId, captureMethod: "RPA_BOT_IMPORT" } }),
        ]);
        res.json({ totalLeads, aiScored, csvImported, botImported });
      })
    );

    // Full CSV export of every lead field. Honours the same filters as the
    // list endpoint, so with no query params it exports the whole org.
    router.get(
      "/export",
      asyncHandler(async (req, res) => {
        const rows = await prisma.lead.findMany({
          where: buildLeadWhere(req),
          orderBy: buildLeadOrderBy(req),
          include: { assignedTo: { select: { fullName: true } } },
        });

        const header = [
          "id", "fullName", "company", "industry", "employeeCount", "email", "phone",
          "product", "estimatedDealValue", "sourceChannel", "captureMethod", "status",
          "aiScore", "aiScoreLabel", "aiScoreReason", "assignedTo", "salesTeam",
          "territory", "firstResponseSla", "notes", "createdAt", "updatedAt",
        ];
        const body = rows.map((l) => [
          l.id, l.fullName, l.company, l.industry, l.employeeCount, l.email, l.phone,
          l.product, l.estimatedDealValue?.toString() ?? "", l.sourceChannel, l.captureMethod, l.status,
          l.aiScore, l.aiScoreLabel, l.aiScoreReason, l.assignedTo?.fullName ?? "", l.salesTeam,
          l.territory, l.firstResponseSla, l.notes,
          l.createdAt.toISOString(), l.updatedAt.toISOString(),
        ]);

        const filename = `leads-${new Date().toISOString().slice(0, 10)}.csv`;
        res.setHeader("Content-Type", "text/csv; charset=utf-8");
        res.setHeader("Content-Disposition", `attachment; filename="${filename}"`);
        // Leading BOM so Excel detects UTF-8 (otherwise accented names mojibake).
        res.send("﻿" + toCsv([header, ...body]));
      })
    );
  },
  onCreated: (lead, organizationId) =>
    triggerBotByName(organizationId, "Lead enrichment bot", { leadId: lead.id }),
});

export const dealsRouter = crudRouter(prisma.deal, {
  createSchema: dealCreateSchema,
  updateSchema: dealUpdateSchema,
  permission: "pipeline",
});

export const casesRouter = crudRouter(prisma.case, {
  createSchema: caseCreateSchema,
  updateSchema: caseUpdateSchema,
  permission: "cases",
  onCreated: (caseRow, organizationId) =>
    triggerBotByName(organizationId, "Case routing bot", { caseId: caseRow.id }),
});

export const campaignsRouter = crudRouter(prisma.campaign, {
  createSchema: campaignCreateSchema,
  updateSchema: campaignUpdateSchema,
  permission: "marketing",
});

/* ---- CSV lead import ---- */

const importSchema = z.object({ csv: z.string().min(1) });

// Header names accepted in the CSV (case/space/underscore-insensitive) → Lead field.
const CSV_COLUMNS: Record<string, string> = {
  fullname: "fullName",
  name: "fullName",
  company: "company",
  industry: "industry",
  employeecount: "employeeCount",
  email: "email",
  phone: "phone",
  product: "product",
  estimateddealvalue: "estimatedDealValue",
  dealvalue: "estimatedDealValue",
  sourcechannel: "sourceChannel",
  source: "sourceChannel",
  notes: "notes",
};

leadsRouter.post(
  "/import",
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const { csv } = importSchema.parse(req.body);

    const rows = parseCsv(csv);
    if (rows.length < 2) {
      throw new HttpError(400, "CSV must have a header row and at least one data row");
    }

    const headers = rows[0].map((h) => CSV_COLUMNS[h.trim().toLowerCase().replace(/[\s_-]/g, "")] ?? null);
    if (!headers.includes("fullName") || !headers.includes("company")) {
      throw new HttpError(400, "CSV header must include at least 'fullName' (or 'name') and 'company' columns");
    }

    let imported = 0;
    const failed: { row: number; reason: string }[] = [];
    const createdIds: string[] = [];

    for (let i = 1; i < rows.length; i++) {
      const record: Record<string, unknown> = {};
      rows[i].forEach((value, col) => {
        const field = headers[col];
        if (field && value.trim() !== "") record[field] = value.trim();
      });

      if (record.estimatedDealValue != null) {
        const parsedValue = parseFloat(String(record.estimatedDealValue).replace(/[^0-9.]/g, ""));
        if (Number.isFinite(parsedValue)) record.estimatedDealValue = parsedValue;
        else delete record.estimatedDealValue;
      }

      const validation = leadCreateSchema.safeParse(record);
      if (!validation.success) {
        failed.push({ row: i + 1, reason: validation.error.issues.map((iss) => `${iss.path.join(".")}: ${iss.message}`).join("; ") });
        continue;
      }

      const lead = await prisma.lead.create({
        data: { ...validation.data, organizationId, sourceChannel: validation.data.sourceChannel ?? "CSV import" },
      });
      createdIds.push(lead.id);
      imported++;
    }

    // Queue LLM scoring for every imported lead (worker rate-limits provider calls).
    for (const leadId of createdIds) {
      triggerBotByName(organizationId, "Lead enrichment bot", { leadId }).catch((err) =>
        console.error("Failed to queue scoring for imported lead:", err)
      );
    }

    res.status(201).json({ imported, failed });
  })
);

/* ---- Bulk delete ---- */

const bulkDeleteSchema = z.object({ ids: z.array(z.string().min(1)).min(1) });

leadsRouter.post(
  "/bulk-delete",
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const { ids } = bulkDeleteSchema.parse(req.body);
    // organizationId scoping ensures a caller can only delete their own leads.
    const result = await prisma.lead.deleteMany({ where: { id: { in: ids }, organizationId } });
    res.json({ deleted: result.count });
  })
);

/* ---- Create a lead from a pasted email (LLM field extraction) ---- */

const pastedEmailSchema = z.object({
  from: z.string().optional(),
  subject: z.string().optional(),
  body: z.string().min(1),
});

const EMAIL_EXTRACT_PROMPT =
  "You extract CRM lead fields from a sales email. Respond with ONLY a JSON object " +
  "using these keys, omitting any you cannot determine: fullName, company, email, phone, " +
  "product, estimatedDealValue (a number), industry, notes. Do not invent data.";

const asStr = (v: unknown): string =>
  typeof v === "string" ? v.trim() : typeof v === "number" ? String(v) : "";

function numberOrUndef(v: unknown): number | undefined {
  const n = typeof v === "number" ? v : parseFloat(String(v ?? "").replace(/[^0-9.]/g, ""));
  return Number.isFinite(n) ? n : undefined;
}

function emailFromHeader(from?: string): string {
  return from?.match(/[\w.+-]+@[\w.-]+\.\w+/)?.[0] ?? "";
}

function nameFromHeader(from?: string): string {
  if (!from) return "";
  const quoted = from.match(/^\s*"?([^"<]+?)"?\s*</);
  return quoted ? quoted[1].trim() : "";
}

function companyFromEmail(email: string): string {
  if (!email.includes("@")) return "";
  const domain = email.split("@")[1]?.split(".")[0] ?? "";
  const generic = ["gmail", "yahoo", "outlook", "hotmail", "icloud", "proton", "aol", "live"];
  if (!domain || generic.includes(domain.toLowerCase())) return "";
  return domain.charAt(0).toUpperCase() + domain.slice(1);
}

// Best-effort JSON extraction from an LLM response that may wrap the object in prose.
function parseJsonObject(raw: string): Record<string, unknown> {
  const match = raw.match(/\{[\s\S]*\}/);
  if (!match) return {};
  try {
    const parsed = JSON.parse(match[0]);
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

leadsRouter.post(
  "/from-email",
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    const { from, subject, body } = pastedEmailSchema.parse(req.body);

    // Ask the model to extract structured fields. If the AI server is
    // unreachable we still create a minimal lead and flag everything as missing,
    // so the capture flow never hard-fails on model availability.
    let extracted: Record<string, unknown> = {};
    try {
      const ai = getAiProvider();
      const completion = await ai.chat([
        { role: "system", content: EMAIL_EXTRACT_PROMPT },
        { role: "user", content: `From: ${from ?? ""}\nSubject: ${subject ?? ""}\n\nBody:\n${body}` },
      ]);
      extracted = parseJsonObject(completion.choices[0]?.message?.content ?? "");
    } catch (err) {
      console.error("Email lead extraction failed, creating minimal lead:", err);
    }

    const email = asStr(extracted.email) || emailFromHeader(from);
    const fullName = asStr(extracted.fullName) || nameFromHeader(from) || "Unknown";
    const company = asStr(extracted.company) || companyFromEmail(email) || "Unknown";
    const phone = asStr(extracted.phone);
    const product = asStr(extracted.product);
    const industry = asStr(extracted.industry);
    const estimatedDealValue = numberOrUndef(extracted.estimatedDealValue);
    const notes = asStr(extracted.notes) || subject || undefined;

    // Reflects the FINAL resolved lead — fields still needing manual entry.
    // "Unknown" is the last-resort fallback, i.e. we couldn't determine it.
    const missingFields: string[] = [];
    if (fullName === "Unknown") missingFields.push("fullName");
    if (company === "Unknown") missingFields.push("company");
    if (!email) missingFields.push("email");
    if (!phone) missingFields.push("phone");
    if (!product) missingFields.push("product");
    if (estimatedDealValue == null) missingFields.push("estimatedDealValue");

    const lead = await prisma.lead.create({
      data: {
        organizationId,
        fullName,
        company,
        email: email || undefined,
        phone: phone || undefined,
        product: product || undefined,
        industry: industry || undefined,
        estimatedDealValue,
        notes,
        sourceChannel: "Email parsing",
        captureMethod: "EMAIL_PARSING",
      },
    });

    // Same enrichment/scoring the normal create flow triggers.
    triggerBotByName(organizationId, "Lead enrichment bot", { leadId: lead.id }).catch((err) =>
      console.error("Failed to queue scoring for email lead:", err)
    );

    res.status(201).json({ lead, missingFields });
  })
);

/* ---- LLM scoring conversation log (stored in local SQLite, not Postgres) ---- */

leadsRouter.get(
  "/:id/scoring-log",
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    await prisma.lead
      .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
      .catch(() => {
        throw new HttpError(404, "Not found");
      });
    res.json(await getScoringConversations(req.params.id, organizationId));
  })
);
