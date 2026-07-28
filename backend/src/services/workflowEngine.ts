import { prisma } from "../lib/prisma.js";
import { writeAuditLog } from "../lib/audit.js";
import { triggerBotByName } from "../queue/queues.js";

type WorkflowNode = {
  type: "TRIGGER" | "CONDITION" | "AI" | "RPA" | "ACTION" | "LOG";
  title: string;
  label: string;
  operation?: string;
  order: number;
};

type WorkflowContext = {
  entityType: string;
  entityId: string;
  label: string; // human-readable description of the record, for audit detail
};

export type WorkflowStepResult = { node: WorkflowNode; status: "executed" | "skipped"; note: string };

// Executes a persisted workflow's nodes in order. RPA nodes are dispatched to
// a matching bot registry entry by name; AI/action/log nodes are recorded to
// the audit trail. There is no general condition-expression evaluator —
// CONDITION nodes are logged and passed through, matching the wizard's
// free-text trigger fields rather than a structured rule DSL.
export async function runWorkflow(
  workflowId: string,
  organizationId: string,
  context: WorkflowContext
): Promise<WorkflowStepResult[]> {
  const workflow = await prisma.workflowDefinition.findUniqueOrThrow({ where: { id: workflowId } });
  const nodes = (workflow.nodes as unknown as WorkflowNode[]).slice().sort((a, b) => a.order - b.order);

  const results: WorkflowStepResult[] = [];

  for (const node of nodes) {
    switch (node.type) {
      case "TRIGGER":
        results.push({ node, status: "skipped", note: "Trigger node — already fired to start this run" });
        break;

      case "RPA": {
        try {
          await triggerBotByName(organizationId, node.label, { entityType: context.entityType, entityId: context.entityId });
          results.push({ node, status: "executed", note: `Queued bot "${node.label}"` });
        } catch (err) {
          results.push({ node, status: "skipped", note: `No matching bot for "${node.label}"` });
        }
        break;
      }

      case "AI":
      case "ACTION":
      case "LOG":
      case "CONDITION": {
        await writeAuditLog({
          organizationId,
          actorType: "system",
          actorLabel: `Workflow: ${workflow.name}`,
          event: `${node.title} step`,
          detail: `${node.operation ?? node.label} — ${context.label}`,
          severity: "info",
          relatedEntityType: context.entityType,
          relatedEntityId: context.entityId,
        });
        results.push({ node, status: "executed", note: node.operation ?? node.label });
        break;
      }
    }
  }

  return results;
}

// Finds active workflows whose free-text trigger event plausibly matches a
// lead score crossing 80 (the wizard's own example, "Lead score > 80") and
// runs them. A simple substring heuristic, not a rule engine — see runWorkflow.
export async function runScoreTriggeredWorkflows(organizationId: string, lead: { id: string; fullName: string; aiScore: number | null }) {
  if (!lead.aiScore || lead.aiScore <= 80) return;
  const workflows = await prisma.workflowDefinition.findMany({
    where: { organizationId, isActive: true, triggerEvent: { contains: "score", mode: "insensitive" } },
  });
  for (const workflow of workflows) {
    await runWorkflow(workflow.id, organizationId, { entityType: "Lead", entityId: lead.id, label: lead.fullName });
  }
}
