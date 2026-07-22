import { z } from "zod";

export const accountCreateSchema = z.object({
  name: z.string().min(1),
  industry: z.string().optional(),
  annualRevenue: z.number().optional(),
  employeeCount: z.string().optional(),
  billingAddress: z.string().optional(),
  parentAccountId: z.string().optional(),
  ownerId: z.string().optional(),
  relationshipValue: z.number().optional(),
  aiSentimentScore: z.number().int().min(0).max(100).optional(),
  emailIntegrationEnabled: z.boolean().optional(),
  telephonyIntegrationEnabled: z.boolean().optional(),
  docRepoSyncEnabled: z.boolean().optional(),
});
export const accountUpdateSchema = accountCreateSchema.partial();

export const contactCreateSchema = z.object({
  accountId: z.string().min(1),
  fullName: z.string().min(1),
  jobTitle: z.string().optional(),
  email: z.string().email().optional(),
  phone: z.string().optional(),
  role: z.enum(["DECISION_MAKER", "CHAMPION", "INFLUENCER", "GATEKEEPER"]).optional(),
  isPrimary: z.boolean().optional(),
  emailNotifications: z.boolean().optional(),
  smsNotifications: z.boolean().optional(),
});
export const contactUpdateSchema = contactCreateSchema.partial();

export const leadCreateSchema = z.object({
  fullName: z.string().min(1),
  company: z.string().min(1),
  industry: z.string().optional(),
  employeeCount: z.string().optional(),
  email: z.string().email().optional(),
  phone: z.string().optional(),
  product: z.string().optional(),
  estimatedDealValue: z.number().optional(),
  sourceChannel: z.string().optional(),
  captureMethod: z.enum(["WEB_FORM", "EMAIL_PARSING", "RPA_BOT_IMPORT"]).optional(),
  notes: z.string().optional(),
  status: z.enum(["NEW", "WARM", "HOT", "COLD"]).optional(),
  assignedToId: z.string().optional(),
  salesTeam: z.string().optional(),
  territory: z.string().optional(),
  firstResponseSla: z.string().optional(),
});
export const leadUpdateSchema = leadCreateSchema.partial().extend({
  aiScore: z.number().int().min(0).max(100).optional(),
  aiScoreLabel: z.string().optional(),
  aiScoreReason: z.string().optional(),
});

export const dealCreateSchema = z.object({
  name: z.string().min(1),
  accountId: z.string().min(1),
  value: z.number(),
  currency: z.string().optional(),
  expectedCloseDate: z.coerce.date().optional(),
  stage: z.enum(["PROSPECTING", "QUALIFICATION", "PROPOSAL", "NEGOTIATION", "CLOSED_WON", "CLOSED_LOST"]).optional(),
  probability: z.number().int().min(0).max(100).optional(),
  ownerId: z.string().optional(),
  forecastCategory: z.string().optional(),
  weightedForecastValue: z.number().optional(),
  bestCaseValue: z.number().optional(),
  autoGenerateProposal: z.boolean().optional(),
  pushToErpOnClose: z.boolean().optional(),
});
export const dealUpdateSchema = dealCreateSchema.partial();

export const caseCreateSchema = z.object({
  subject: z.string().min(1),
  source: z.string().optional(),
  priority: z.enum(["LOW", "MEDIUM", "HIGH", "CRITICAL"]).optional(),
  slaDeadline: z.coerce.date().optional(),
  status: z.enum(["OPEN", "IN_PROGRESS", "ESCALATED", "RESOLVED", "CLOSED"]).optional(),
  accountId: z.string().optional(),
  assignedToId: z.string().optional(),
});
export const caseUpdateSchema = caseCreateSchema.partial();

export const campaignCreateSchema = z.object({
  name: z.string().min(1),
  channel: z.enum(["EMAIL", "SMS_EMAIL", "MULTI_CHANNEL"]).optional(),
  goal: z.string().optional(),
  budget: z.number().optional(),
  ownerId: z.string().optional(),
  startDate: z.coerce.date().optional(),
  endDate: z.coerce.date().optional(),
  segment: z.string().optional(),
  region: z.string().optional(),
  estimatedReach: z.number().int().optional(),
  status: z.enum(["DRAFT", "SCHEDULED", "ACTIVE", "COMPLETED"]).optional(),
  sentCount: z.number().int().optional(),
  openRatePct: z.number().int().min(0).max(100).optional(),
});
export const campaignUpdateSchema = campaignCreateSchema.partial();
