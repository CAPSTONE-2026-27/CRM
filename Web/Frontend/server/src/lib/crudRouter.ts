import { Router, type Request, type Response } from "express";
import type { ZodSchema } from "zod";
import { asyncHandler, HttpError } from "../middleware/errorHandler.js";
import { requireAuth, requirePermission } from "../middleware/auth.js";
import type { PermissionKey } from "./permissions.js";

// Minimal shape every Prisma model delegate we mount here satisfies.
// Args are `any` (not `unknown`) so concrete Prisma delegates — whose methods
// take model-specific arg types — remain structurally assignable here.
type OrgScopedDelegate = {
  findMany: (args: any) => Promise<unknown[]>;
  findFirstOrThrow: (args: any) => Promise<unknown>;
  create: (args: any) => Promise<unknown>;
  update: (args: any) => Promise<unknown>;
  delete: (args: any) => Promise<unknown>;
};

export function crudRouter(
  delegate: OrgScopedDelegate,
  opts: {
    createSchema: ZodSchema;
    updateSchema: ZodSchema;
    // Per-user permission key required for all access to this resource
    // (read + write). ADMIN bypasses. Omit to allow any authenticated user.
    permission?: PermissionKey;
    orderBy?: Record<string, "asc" | "desc">;
    // Fired after a successful create, e.g. to enqueue an RPA bot run.
    // Failures are logged, not surfaced — a bot trigger must never fail the request.
    onCreated?: (row: any, organizationId: string) => Promise<void>;
    // Replaces the default GET "/" handler — e.g. to return a paginated/filtered
    // response ({ content, page, size, ... }) instead of the full array.
    list?: (req: Request, res: Response) => Promise<unknown>;
    // Registers extra collection-level routes (e.g. GET "/stats") that must be
    // matched *before* the "/:id" routes below, otherwise "/:id" would shadow them.
    collectionRoutes?: (router: Router) => void;
    // Runs after schema validation but before a create or update is persisted.
    // Throw an HttpError to reject the write — used for field-level rules the
    // zod schema can't express, e.g. "only some roles may set this column".
    beforeWrite?: (req: Request, data: Record<string, unknown>) => Promise<void> | void;
  }
) {
  const router = Router();

  router.use(requireAuth);
  if (opts.permission) {
    router.use(requirePermission(opts.permission));
  }

  // Must precede "/:id" so paths like "/stats" aren't captured as an id.
  if (opts.collectionRoutes) {
    opts.collectionRoutes(router);
  }

  router.get(
    "/",
    asyncHandler(
      opts.list ??
        (async (req, res) => {
          const organizationId = req.auth!.organizationId;
          const rows = await delegate.findMany({
            where: { organizationId },
            orderBy: opts.orderBy ?? { createdAt: "desc" },
          });
          res.json(rows);
        })
    )
  );

  router.get(
    "/:id",
    asyncHandler(async (req, res) => {
      const organizationId = req.auth!.organizationId;
      const row = await delegate
        .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
        .catch(() => {
          throw new HttpError(404, "Not found");
        });
      res.json(row);
    })
  );

  router.post(
    "/",
    asyncHandler(async (req, res) => {
      const organizationId = req.auth!.organizationId;
      const data = opts.createSchema.parse(req.body);
      if (opts.beforeWrite) await opts.beforeWrite(req, data as Record<string, unknown>);
      const row = await delegate.create({ data: { ...data, organizationId } });
      if (opts.onCreated) {
        opts.onCreated(row, organizationId).catch((err) => console.error("onCreated hook failed:", err));
      }
      res.status(201).json(row);
    })
  );

  router.patch(
    "/:id",
    asyncHandler(async (req, res) => {
      const organizationId = req.auth!.organizationId;
      await delegate
        .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
        .catch(() => {
          throw new HttpError(404, "Not found");
        });
      const data = opts.updateSchema.parse(req.body);
      if (opts.beforeWrite) await opts.beforeWrite(req, data as Record<string, unknown>);
      const row = await delegate.update({ where: { id: req.params.id }, data });
      res.json(row);
    })
  );

  router.delete(
    "/:id",
    asyncHandler(async (req, res) => {
      const organizationId = req.auth!.organizationId;
      await delegate
        .findFirstOrThrow({ where: { id: req.params.id, organizationId } })
        .catch(() => {
          throw new HttpError(404, "Not found");
        });
      await delegate.delete({ where: { id: req.params.id } });
      res.status(204).send();
    })
  );

  return router;
}
