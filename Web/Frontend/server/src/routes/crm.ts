import { z } from "zod";
import { prisma } from "../lib/prisma.js";
import { crudRouter } from "../lib/crudRouter.js";
import { parseCsv } from "../lib/csv.js";
import { getScoringConversations } from "../lib/scoringLog.js";
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

export const leadsRouter = crudRouter(prisma.lead, {
  createSchema: leadCreateSchema,
  updateSchema: leadUpdateSchema,
  permission: "leads",
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
