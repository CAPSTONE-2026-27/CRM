import { z } from "zod";
import { prisma } from "../lib/prisma.js";
import { crudRouter } from "../lib/crudRouter.js";
import { requireAuth } from "../middleware/auth.js";
import { asyncHandler, HttpError } from "../middleware/errorHandler.js";
import { workflowDefinitionCreateSchema, workflowDefinitionUpdateSchema } from "../schemas/automation.js";
import { runWorkflow } from "../services/workflowEngine.js";

export const workflowsRouter = crudRouter(prisma.workflowDefinition, {
  createSchema: workflowDefinitionCreateSchema,
  updateSchema: workflowDefinitionUpdateSchema,
  permission: "workflow",
});

// Mirrors the F05 "Activate" step — flips the definition live so future
// trigger events actually dispatch its RPA/AI steps.
workflowsRouter.post(
  "/:id/activate",
  requireAuth,
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    await prisma.workflowDefinition
      .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
      .catch(() => {
        throw new HttpError(404, "Not found");
      });
    const updated = await prisma.workflowDefinition.update({ where: { id: req.params.id }, data: { isActive: true } });
    res.json(updated);
  })
);

const testSchema = z.object({
  entityType: z.string().min(1),
  entityId: z.string().min(1),
  label: z.string().min(1),
});

// Mirrors the F05 "Test" step — runs the workflow's real nodes against a
// specified record and returns the execution trace, instead of canned steps.
workflowsRouter.post(
  "/:id/test",
  requireAuth,
  asyncHandler(async (req, res) => {
    const organizationId = req.auth!.organizationId;
    await prisma.workflowDefinition
      .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
      .catch(() => {
        throw new HttpError(404, "Not found");
      });
    const body = testSchema.parse(req.body);
    const trace = await runWorkflow(req.params.id, organizationId, body);
    res.json({ trace });
  })
);
