import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./apiClient";
import type {
  Account,
  AuditLogEntry,
  Campaign,
  CaseRow,
  Contact,
  ContactStatus,
  CustomerOnboarding,
  DashboardSummary,
  Deal,
  DealWorkspace,
  Lead,
  LeadMeeting,
  LeadPipelineStage,
  LeadStats,
  ManagerDecision,
  ManagerReview,
  MeetingAnalysis,
  MeetingOutputDetail,
  PagedResponse,
  ReportingSummary,
  RpaBot,
  RpaBotRun,
  SecuritySummary,
  UserRow,
  WorkflowDefinition,
} from "./types";

function toQueryString(params: Record<string, string | number | undefined | null>): string {
  const usp = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") continue;
    usp.set(key, String(value));
  }
  const s = usp.toString();
  return s ? `?${s}` : "";
}

/* ---- generic list + create helpers, one per resource ---- */
function useList<T>(key: string, path: string) {
  return useQuery({ queryKey: [key], queryFn: () => api.get<T[]>(path) });
}

function useCreate<T, TInput>(key: string, path: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: TInput) => api.post<T>(path, input),
    onSuccess: () => qc.invalidateQueries({ queryKey: [key] }),
  });
}

function useDelete(key: string, basePath: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`${basePath}/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: [key] }),
  });
}

export type LeadSearchParams = {
  q?: string;
  status?: string;
  assignedToId?: string;
  sourceChannel?: string;
  industry?: string;
  createdFrom?: string;
  createdTo?: string;
  sort?: string;
  page?: number;
  size?: number;
};

// Polls only while the CURRENT page has an unscored (e.g. CSV-imported) row,
// so a settled page stops refetching and never fights with mid-typing search.
export function useLeads(params: LeadSearchParams) {
  return useQuery({
    queryKey: ["leads", params],
    queryFn: () => api.get<PagedResponse<Lead>>(`/leads${toQueryString(params)}`),
    placeholderData: (previous) => previous,
    refetchInterval: (query) => {
      const content = query.state.data?.content;
      if (!content) return false;
      return content.some((l) => l.aiScore == null) ? 8000 : false;
    },
  });
}

/** Open leads for the pipeline board's lead columns. Under ["leads"], so every
 *  lead mutation that refreshes the list refreshes the board too. */
export const usePipelineLeads = () =>
  useQuery({ queryKey: ["leads", "pipeline"], queryFn: () => api.get<Lead[]>("/leads/pipeline") });

export function useUpdateLeadStage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, stage }: { id: string; stage: LeadPipelineStage }) =>
      api.patch<Lead>(`/leads/${id}/stage`, { stage }),
    onSettled: () => qc.invalidateQueries({ queryKey: ["leads"] }),
  });
}

export const useLeadStats = () =>
  useQuery({ queryKey: ["leads", "stats"], queryFn: () => api.get<LeadStats>("/leads/stats"), refetchInterval: 8000 });

export const useCreateLead = () => useCreate<Lead, Partial<Lead>>("leads", "/leads");
export const useDeleteLead = () => useDelete("leads", "/leads");

// Partial update of a single lead. Invalidating ["leads"] also refreshes the
// stats tiles, whose key is ["leads", "stats"].
export function useUpdateLead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...patch }: { id: string } & Record<string, unknown>) =>
      api.patch<Lead>(`/leads/${id}`, patch),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["leads"] }),
  });
}

export type PastedEmailInput = { from?: string; subject?: string; body: string };

/**
 * The fields a pasted email yields, without creating anything.
 *
 * The wizard validates a lead before creating it, and validation needs the
 * fields the email actually carries — which only the parser knows. So this
 * runs first, its result is validated, and creation follows.
 */
export function useParseEmailLead() {
  return useMutation({
    mutationFn: (input: PastedEmailInput) =>
      api.post<Record<string, unknown>>("/leads/parse-email", input),
  });
}

export type LeadCreationResult = { lead: Lead; missingFields: string[] };
export function useCreateLeadFromEmail() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: PastedEmailInput) => api.post<LeadCreationResult>("/leads/from-email", input),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["leads"] }),
  });
}

export type LeadImportResult = {
  imported: number;
  warnings: { row: number; missingFields: string[] }[];
  failed: { row: number; reason: string }[];
};
export function useImportLeads() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (csv: string) => api.post<LeadImportResult>("/leads/import", { csv }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["leads"] }),
  });
}

export type BulkDeleteResult = { deleted: number };
export function useBulkDeleteLeads() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ids: string[]) => api.post<BulkDeleteResult>("/leads/bulk-delete", { ids }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["leads"] }),
  });
}

/* ---- Lead Output: post-meeting records ---- */

export type MeetingFormInput = { meetingDate: string; meetingTime: string; meetingOutput: string };
export type SaveMeetingInput = MeetingFormInput & {
  aiSummary: string;
  updatedScore: number;
  scoreLabel?: "Hot" | "Warm" | "Cold";
  scoreChangeReason?: string;
};

export type NegotiatedTermsInput = {
  finalAmount?: number;
  paymentTerms?: string;
  deliveryTimeline?: string;
};

/**
 * Records what was agreed during negotiation.
 *
 * The backend accepts this only while the deal is at NEGOTIATION, and blank
 * fields are left alone rather than cleared — so a representative who settles
 * on a price without renegotiating the terms does not wipe them.
 *
 * What is recorded here is what the contract carries: the document assembler
 * reads the deal's own terms, and the contract bot sends none of its own.
 */
export function useRecordNegotiatedTerms(dealId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: NegotiatedTermsInput) =>
      api.post<Deal>(`/deals/${dealId}/negotiated-terms`, input),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["deals"] });
    },
  });
}

/** One deal, fetched on demand. Skipped while the id is absent. */
export const useDeal = (dealId: string | null | undefined) =>
  useQuery({
    queryKey: ["deals", dealId],
    queryFn: () => api.get<Deal>(`/deals/${dealId}`),
    enabled: Boolean(dealId),
  });

export type ScheduleMeetingInput = { meetingDate: string; meetingTime: string; meetingLink: string };
export type ScheduleMeetingResult = {
  leadId: string;
  leadName: string;
  leadEmail: string;
  meetingDate: string;
  meetingTime: string;
  meetingLink: string;
  /** False when no scheduling bot is configured or the platform refused —
   *  the meeting is booked, but the lead has not been told. */
  invitationStarted: boolean;
};

/** Books a meeting and asks the scheduler bot to invite the lead. */
export function useScheduleMeeting(leadId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: ScheduleMeetingInput) =>
      api.post<ScheduleMeetingResult>(`/leads/${leadId}/meetings/schedule`, input),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["leads"] }),
  });
}

export const useLeadMeetings = (leadId: string | undefined) =>
  useQuery({
    queryKey: ["lead-meetings", leadId],
    queryFn: () => api.get<LeadMeeting[]>(`/leads/${leadId}/meetings`),
    enabled: Boolean(leadId),
  });

// Preview only — generates the summary and re-score without saving, so the rep
// can edit the summary first.
export function useAnalyzeMeeting(leadId: string) {
  return useMutation({
    mutationFn: (input: MeetingFormInput) => api.post<MeetingAnalysis>(`/leads/${leadId}/meetings/analyze`, input),
  });
}

// Appends a meeting record and rolls the lead's score forward, so the lead
// lists and stats need refreshing too.
export function useSaveMeeting(leadId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: SaveMeetingInput) => api.post<LeadMeeting>(`/leads/${leadId}/meetings`, input),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["lead-meetings", leadId] });
      qc.invalidateQueries({ queryKey: ["leads"] });
    },
  });
}

export const useAccounts = () => useList<Account>("accounts", "/accounts");
export const useCreateAccount = () => useCreate<Account, Record<string, unknown>>("accounts", "/accounts");

/** PUT replaces every field, so callers send the whole account, not a patch. */
export function useUpdateAccount() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string } & Record<string, unknown>) => api.put<Account>(`/accounts/${id}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["accounts"] }),
  });
}

export const useContacts = () => useList<Contact>("contacts", "/contacts");
export const useCreateContact = () => useCreate<Contact, Partial<Contact>>("contacts", "/contacts");

export const useDeals = () => useList<Deal>("deals", "/deals");
export const useCreateDeal = () => useCreate<Deal, Record<string, unknown>>("deals", "/deals");
/** Chooses who a deal's proposals and contracts are addressed to. */
export function useSetPrimaryContact(dealId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (contactId: string | null) =>
      api.put<Deal>(`/deals/${dealId}/primary-contact`, { contactId: contactId == null ? null : Number(contactId) }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["deals"] });
      qc.invalidateQueries({ queryKey: ["deal-workspace", dealId] });
    },
  });
}

export function useUpdateDealStage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, stage, closingReason }: { id: string; stage: string; closingReason?: string }) =>
      api.patch<Deal>(`/deals/${id}`, { stage, closingReason }),
    onSuccess: (_data, variables) => {
      qc.invalidateQueries({ queryKey: ["deals"] });
      qc.invalidateQueries({ queryKey: ["deal-workspace", variables.id] });
      // Closing a deal as won opens an onboarding record server-side.
      qc.invalidateQueries({ queryKey: ["deal-onboarding", variables.id] });
    },
  });
}

/* ---- Lead flow steps 4-6 ---- */

// Invalidating ["leads"] also refreshes the stats tiles, whose key is
// ["leads", "stats"].
function useLeadFlowMutation<TInput>(path: (leadId: string) => string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ leadId, ...body }: { leadId: string } & TInput) => api.post<Lead>(path(leadId), body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["leads"] }),
  });
}

export const useAssignLead = () =>
  useLeadFlowMutation<{ assignedToId: string }>((id) => `/leads/${id}/assign`);

/**
 * Records a person's qualification verdict.
 *
 * For the leads the scoring model never reached — those sit at PENDING, and a
 * lead stuck there is one nobody can act on.
 */
export function useQualifyLead(leadId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ qualified, reason }: { qualified: boolean; reason?: string }) =>
      api.post<Lead>(`/leads/${leadId}/qualify`, { qualified, reason }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["leads"] }),
  });
}

export const useUpdateContactStatus = () =>
  useLeadFlowMutation<{ contactStatus: ContactStatus; contactNotes?: string }>(
    (id) => `/leads/${id}/contact-status`
  );

export type ConversionResult = {
  leadId: string;
  dealId: string;
  opportunityId: string;
  accountId: string;
  accountCreated: boolean;
  contactId: string;
  contactCreated: boolean;
};

/** Flow step 6. Creates a deal and an account, so those lists go stale too.
 *  The lead id is the whole input; the endpoint reads no body. */
/**
 * Asks the opportunity bot to convert a lead.
 *
 * Resolves once the automation has been started, not once the opportunity
 * exists — the automation calls the conversion endpoint itself a moment later.
 * The caches are invalidated anyway, and the deal appears on the next refetch.
 *
 * A 409 means the lead is not ready: already converted, unqualified, or scored
 * as something other than HOT. The message says which.
 */
export function useConvertLeadViaBot() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ leadId }: { leadId: string }) =>
      api.post<ConversionResult | undefined>(`/leads/${leadId}/convert-via-bot`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["leads"] });
      qc.invalidateQueries({ queryKey: ["deals"] });
      qc.invalidateQueries({ queryKey: ["accounts"] });
    },
  });
}

export function useConvertLead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ leadId }: { leadId: string }) => api.post<ConversionResult>(`/leads/${leadId}/convert`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["leads"] });
      qc.invalidateQueries({ queryKey: ["deals"] });
      qc.invalidateQueries({ queryKey: ["accounts"] });
    },
  });
}

/* ---- Deal analysis pipeline (deal flow steps 2-14) ---- */

export const useDealWorkspace = (dealId: string | undefined) =>
  useQuery({
    queryKey: ["deal-workspace", dealId],
    queryFn: () => api.get<DealWorkspace>(`/deals/${dealId}/workspace`),
    enabled: Boolean(dealId),
  });

export type MeetingOutputInput = {
  meetingDate: string;
  meetingTime: string;
  meetingType?: string;
  participants?: string;
  meetingSummary: string;
  customerRequirements?: string;
  keyDiscussionPoints?: string;
  customerQuestions?: string;
  competitorMentioned?: string;
  objections?: string;
  budgetDiscussion?: string;
  timeline?: string;
  nextSteps?: string;
  executiveRemarks?: string;
};

/**
 * Submits a meeting write-up and runs the whole chain — LLM extraction, feature
 * engineering, XGBoost scoring — in one request. Takes several seconds; the
 * form shows the stages progressing rather than a bare spinner.
 */
export function useSubmitMeetingOutput(dealId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: MeetingOutputInput) =>
      api.post<MeetingOutputDetail>(`/deals/${dealId}/meeting-outputs`, input),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["deal-workspace", dealId] });
      // The new prediction is denormalised onto the deal, so the board's
      // score badges are stale too.
      qc.invalidateQueries({ queryKey: ["deals"] });
    },
  });
}

export type ManagerReviewInput = {
  decision: ManagerDecision;
  overriddenAction?: string;
  comments?: string;
};

export function useReviewDeal(dealId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: ManagerReviewInput) => api.post<ManagerReview>(`/deals/${dealId}/review`, input),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["deal-workspace", dealId] });
      qc.invalidateQueries({ queryKey: ["deals"] });
    },
  });
}

export function useScheduleDealMeeting(dealId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { meetingScheduledAt: string; meetingMode?: string; meetingParticipants?: string }) =>
      api.post<Deal>(`/deals/${dealId}/schedule-meeting`, input),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["deal-workspace", dealId] });
      qc.invalidateQueries({ queryKey: ["deals"] });
    },
  });
}

// Returns null until the deal is Closed Won — the absence is the answer, not
// an error, so a 200 with a null body is expected here.
export const useDealOnboarding = (dealId: string | undefined) =>
  useQuery({
    queryKey: ["deal-onboarding", dealId],
    queryFn: () => api.get<CustomerOnboarding | null>(`/deals/${dealId}/onboarding`),
    enabled: Boolean(dealId),
  });

export function useUpdateOnboarding(dealId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { status: string; notes?: string }) =>
      api.patch<CustomerOnboarding>(`/deals/${dealId}/onboarding`, input),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["deal-onboarding", dealId] }),
  });
}

export const useCases = () => useList<CaseRow>("cases", "/cases");
export const useCreateCase = () => useCreate<CaseRow, Partial<CaseRow>>("cases", "/cases");

export const useCampaigns = () => useList<Campaign>("campaigns", "/campaigns");
export const useCreateCampaign = () => useCreate<Campaign, Record<string, unknown>>("campaigns", "/campaigns");

export type UserSearchParams = { q?: string; role?: string; department?: string; status?: string };
export function useUsers(params: UserSearchParams = {}) {
  return useQuery({
    queryKey: ["users", params],
    queryFn: () => api.get<UserRow[]>(`/users${toQueryString(params)}`),
  });
}
export const useCreateUser = () =>
  useCreate<UserRow, Record<string, unknown>>("users", "/users");
export const useDeleteUser = () => useDelete("users", "/users");
export function useUpdateUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...patch }: { id: string } & Record<string, unknown>) => api.patch<UserRow>(`/users/${id}`, patch),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["users"] }),
  });
}

export function useSetUserStatus() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: "ACTIVE" | "INACTIVE" }) =>
      api.patch<UserRow>(`/users/${id}/status`, { status }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["users"] }),
  });
}

export type ResetPasswordResult = { temporaryPassword: string };
export function useResetUserPassword() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, newPassword }: { id: string; newPassword?: string }) =>
      api.post<ResetPasswordResult>(`/users/${id}/reset-password`, newPassword ? { newPassword } : undefined),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["users"] }),
  });
}

export const useRpaBots = () => useList<RpaBot>("rpa-bots", "/rpa-bots");
export const useCreateRpaBot = () => useCreate<RpaBot, Record<string, unknown>>("rpa-bots", "/rpa-bots");
export const useDeleteRpaBot = () => useDelete("rpa-bots", "/rpa-bots");
export function useDeployRpaBot() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post<RpaBot>(`/rpa-bots/${id}/deploy`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["rpa-bots"] }),
  });
}

export const useRpaBotRuns = () => useList<RpaBotRun>("rpa-bot-runs", "/rpa-bot-runs");

export type SapAutomation = {
  name: string;
  trigger: string;
  platform: string;
  /** Whether a definition id is configured — a bot without one starts nothing. */
  configured: boolean;
  definitionId: string | null;
};

/** The SAP automations this CRM starts, read from configuration. */
export const useSapAutomations = () =>
  useQuery({
    queryKey: ["rpa-bots", "automations"],
    queryFn: () => api.get<SapAutomation[]>("/rpa-bots/automations"),
  });

export const useWorkflows = () => useList<WorkflowDefinition>("workflows", "/workflows");
export const useCreateWorkflow = () => useCreate<WorkflowDefinition, Record<string, unknown>>("workflows", "/workflows");
export function useActivateWorkflow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post<WorkflowDefinition>(`/workflows/${id}/activate`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["workflows"] }),
  });
}

export const useAuditLog = () =>
  useQuery({ queryKey: ["audit-log"], queryFn: () => api.get<PagedResponse<AuditLogEntry>>("/audit-log?size=20") });

export const useDashboardSummary = () => useQuery({ queryKey: ["analytics", "dashboard"], queryFn: () => api.get<DashboardSummary>("/analytics/dashboard") });
export const useReportingSummary = () => useQuery({ queryKey: ["analytics", "reporting"], queryFn: () => api.get<ReportingSummary>("/analytics/reporting") });
export const useSecuritySummary = () => useQuery({ queryKey: ["analytics", "security"], queryFn: () => api.get<SecuritySummary>("/analytics/security") });
