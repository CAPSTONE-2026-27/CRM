import type { ChatCompletionTool } from "openai/resources/index.js";
import { prisma } from "../lib/prisma.js";
import type { AccessTokenPayload } from "../lib/jwt.js";
import { hasPermission, type PermissionKey } from "../lib/permissions.js";

// Each tool reads a resource, so it's gated by that resource's permission
// key — the copilot can't surface data the caller couldn't fetch via the
// normal API. Mirrors the REST-route permission gates.
const TOOL_PERMISSION: Record<string, PermissionKey> = {
  search_leads: "leads",
  search_pipeline: "pipeline",
  search_cases: "cases",
};

export function toolsForAuth(auth: AccessTokenPayload): ChatCompletionTool[] {
  return copilotTools.filter((tool) => hasPermission(auth, TOOL_PERMISSION[tool.function.name]));
}

// Tool schemas exposed to the model. The model never touches the DB — every
// call here is a typed tool-call -> service-function -> Prisma flow, scoped
// to the caller's organization.
export const copilotTools: ChatCompletionTool[] = [
  {
    type: "function",
    function: {
      name: "search_leads",
      description: "Search CRM leads by name or company, optionally filtered by status.",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string", description: "Name or company substring to search for" },
          status: { type: "string", enum: ["NEW", "WARM", "HOT", "COLD"] },
        },
      },
    },
  },
  {
    type: "function",
    function: {
      name: "search_pipeline",
      description: "Search deals in the sales pipeline by name, account, or stage.",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string", description: "Deal or account name substring" },
          stage: {
            type: "string",
            enum: ["PROSPECTING", "QUALIFICATION", "PROPOSAL", "NEGOTIATION", "CLOSED_WON", "CLOSED_LOST"],
          },
        },
      },
    },
  },
  {
    type: "function",
    function: {
      name: "search_cases",
      description: "Search support cases/tickets by subject, optionally filtered by status or priority.",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string", description: "Subject substring" },
          status: { type: "string", enum: ["OPEN", "IN_PROGRESS", "ESCALATED", "RESOLVED", "CLOSED"] },
        },
      },
    },
  },
];

export async function executeTool(name: string, rawArgs: string, auth: AccessTokenPayload): Promise<unknown> {
  if (!hasPermission(auth, TOOL_PERMISSION[name])) {
    throw new Error(`Tool "${name}" is not available for this user`);
  }
  const organizationId = auth.organizationId;
  const args = rawArgs ? JSON.parse(rawArgs) : {};

  switch (name) {
    case "search_leads":
      return prisma.lead.findMany({
        where: {
          organizationId,
          ...(args.status ? { status: args.status } : {}),
          ...(args.query
            ? { OR: [{ fullName: { contains: args.query, mode: "insensitive" } }, { company: { contains: args.query, mode: "insensitive" } }] }
            : {}),
        },
        take: 10,
      });

    case "search_pipeline":
      return prisma.deal.findMany({
        where: {
          organizationId,
          ...(args.stage ? { stage: args.stage } : {}),
          ...(args.query ? { name: { contains: args.query, mode: "insensitive" } } : {}),
        },
        include: { account: { select: { name: true } } },
        take: 10,
      });

    case "search_cases":
      return prisma.case.findMany({
        where: {
          organizationId,
          ...(args.status ? { status: args.status } : {}),
          ...(args.query ? { subject: { contains: args.query, mode: "insensitive" } } : {}),
        },
        take: 10,
      });

    default:
      throw new Error(`Unknown tool: ${name}`);
  }
}
