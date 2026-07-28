import { z } from "zod";

export const rpaBotCreateSchema = z.object({
  name: z.string().min(1),
  platform: z.enum(["UIPATH", "AUTOMATION_ANYWHERE", "BLUE_PRISM"]),
  botType: z.enum(["UNATTENDED", "ATTENDED"]).optional(),
  triggerSource: z.string().optional(),
  credentialVaultRef: z.string().optional(),
  environment: z.string().optional(),
  region: z.string().optional(),
  version: z.string().optional(),
  status: z.enum(["REGISTERED", "SCHEDULED", "RUNNING", "ERROR", "DEPLOYED"]).optional(),
});
export const rpaBotUpdateSchema = rpaBotCreateSchema.partial();

const workflowNodeSchema = z.object({
  type: z.enum(["TRIGGER", "CONDITION", "AI", "RPA", "ACTION", "LOG"]),
  title: z.string(),
  label: z.string(),
  operation: z.string().optional(),
  order: z.number().int(),
});

export const workflowDefinitionCreateSchema = z.object({
  name: z.string().min(1),
  triggerEvent: z.string().optional(),
  scope: z.string().optional(),
  runMode: z.string().optional(),
  isActive: z.boolean().optional(),
  nodes: z.array(workflowNodeSchema),
});
export const workflowDefinitionUpdateSchema = workflowDefinitionCreateSchema.partial();
