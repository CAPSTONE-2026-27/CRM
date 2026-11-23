import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, streamCopilotChat, type CopilotMessage } from "./apiClient";
import type {
  Account,
  AuditLogEntry,
  Campaign,
  CoachConversation,
  CoachHistoryMessage,
  CoachStatus,
  Contact,
  ContactStatus,
  CustomerOnboarding,
  DashboardSummary,
  Deal,
  DealWorkspace,
  Lead,
  LeadMeeting,
  LeadStats,
  ManagerDecision,
  ManagerReview,
  MeetingAnalysis,
  MeetingOutputDetail,
  PagedResponse,
  RpaBot,
  RpaBotRun,
  SecuritySummary,
  UserRow,
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

  // Per-column filters from the lead list header row. These are applied by the
  // API against every lead, not against the loaded page — so a filter never
  // hides matches sitting on page 2.
  product?: string;
  qualificationStatus?: string;
  contactStatus?: string;
  scoreMin?: number;
  scoreMax?: number;
  unassigned?: string;
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

/* ---- Qualification: the sales executive's manual verdict ---- */

// The score is the model's, the verdict is the human's. This is the only way
// qualificationStatus ever changes — re-scoring a lead deliberately leaves it
// alone. Invalidating ["leads"] refreshes the list, the badge and the stats.
export function useQualifyLead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ leadId, qualified, note }: { leadId: string; qualified: boolean; note?: string }) =>
      api.post<unknown>(`/leads/${leadId}/qualify`, { qualified, note }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["leads"] }),
  });
}

/* ---- Meeting: post-meeting records ---- */

export type MeetingFormInput = { meetingDate: string; meetingTime: string; meetingOutput: string };
export type SaveMeetingInput = MeetingFormInput & {
  aiSummary: string;
  updatedScore: number;
  scoreLabel?: "Hot" | "Warm" | "Cold";
  scoreChangeReason?: string;
};

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

export const useContacts = () => useList<Contact>("contacts", "/contacts");
export const useCreateContact = () => useCreate<Contact, Partial<Contact>>("contacts", "/contacts");

export const useDeals = () => useList<Deal>("deals", "/deals");

// Polled lazily: the answer only changes when someone starts or stops Ollama.
export const useCoachStatus = () =>
  useQuery({ queryKey: ["coach-status"], queryFn: () => api.get<CoachStatus>("/copilot/status"), staleTime: 30_000 });

/* ---- Deal coach conversations -------------------------------------------
 * Everything lives in the app-wide query cache rather than in the chat
 * screen's state, so leaving the screen loses nothing: a stream keeps writing
 * into the cache while the user is elsewhere, and the screen shows the current
 * state when it mounts again. The server holds the durable copy for reloads and
 * other devices.
 *
 * Keys are scoped by user id, so a second account signing in on the same tab
 * never sees the first one's chats:
 *   ["coach-conversations", user]        the "Past chats" list
 *   ["coach-chat", user, id | "new"]     one conversation ("new" = not yet saved)
 *   ["coach-active", user]               which conversation is open */

export type CoachChatMessage = CopilotMessage & { createdAt?: string };
export type CoachChat = { messages: CoachChatMessage[]; streaming: boolean };

const EMPTY_CHAT: CoachChat = { messages: [], streaming: false };
const NEW_CHAT = "new";

// A question with no answer yet is treated as "still being answered" for this
// long after it was asked — longer than the backend's 180s stream timeout, so a
// reply that is merely slow isn't written off, and a failed one stops polling.
const COACH_PENDING_WINDOW_MS = 200_000;

type UserId = string | number | undefined;
const uid = (userId: UserId) => String(userId ?? "anonymous");
const conversationsKey = (userId: UserId) => ["coach-conversations", uid(userId)];
const chatKey = (userId: UserId, conversationId: string | null) => ["coach-chat", uid(userId), conversationId ?? NEW_CHAT];
const activeKey = (userId: UserId) => ["coach-active", uid(userId)];

/** A saved question whose answer is still being generated server-side. */
export function awaitingSavedAnswer(chat: CoachChat | undefined): boolean {
  if (!chat || chat.streaming) return false;
  const last = chat.messages[chat.messages.length - 1];
  if (!last || last.role !== "user" || !last.createdAt) return false;
  return Date.now() - new Date(last.createdAt).getTime() < COACH_PENDING_WINDOW_MS;
}

export function useCoachConversations(userId: UserId) {
  return useQuery({
    queryKey: conversationsKey(userId),
    enabled: userId != null,
    queryFn: () => api.get<CoachConversation[]>("/copilot/conversations"),
  });
}

/** The open conversation's id (null = a new, unsaved chat) and a setter. */
export function useActiveCoachConversation(userId: UserId): [string | null, (id: string | null) => void] {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: activeKey(userId),
    queryFn: () => null as string | null,
    staleTime: Infinity,
    gcTime: Infinity,
  });
  return [data ?? null, (id) => { qc.setQueryData<string | null>(activeKey(userId), id); }];
}

export function useCoachChat(userId: UserId, conversationId: string | null) {
  return useQuery({
    queryKey: chatKey(userId, conversationId),
    enabled: userId != null,
    queryFn: async (): Promise<CoachChat> => {
      if (conversationId == null) return EMPTY_CHAT;
      const saved = await api.get<CoachHistoryMessage[]>(`/copilot/conversations/${conversationId}/messages`);
      return {
        messages: saved.map((m) => ({ role: m.role, content: m.content, createdAt: m.createdAt })),
        streaming: false,
      };
    },
    // Fetched once per session; after that the cache is the live copy and a
    // background refetch would overwrite an answer that is still streaming in.
    staleTime: Infinity,
    gcTime: Infinity,
    // After a reload mid-answer, the question is saved but the reply isn't
    // yet — poll until the backend finishes and stores it.
    refetchInterval: (query) => (awaitingSavedAnswer(query.state.data) ? 3_000 : false),
  });
}

export function useSendCoachMessage(userId: UserId) {
  const qc = useQueryClient();

  return async (conversationId: string | null, text: string): Promise<{ failed: boolean }> => {
    // Where this answer is written. Starts as the open chat; a new chat moves
    // to its real id once the server has saved it (onConversation below).
    let key = chatKey(userId, conversationId);
    const update = (fn: (chat: CoachChat) => CoachChat) =>
      qc.setQueryData<CoachChat>(key, (chat) => fn(chat ?? EMPTY_CHAT));
    const replaceLast = (fn: (content: string) => string) =>
      update((chat) => {
        const messages = [...chat.messages];
        const last = messages[messages.length - 1];
        messages[messages.length - 1] = { ...last, content: fn(last.content) };
        return { ...chat, messages };
      });

    const current = qc.getQueryData<CoachChat>(key);
    if (current?.streaming) return { failed: false };
    const history = [...(current?.messages ?? []), { role: "user" as const, content: text }];
    let failed = false;

    update(() => ({ messages: [...history, { role: "assistant", content: "" }], streaming: true }));

    await streamCopilotChat(
      // Only role and content go to the server; timestamps are display state.
      { conversationId, messages: history.map(({ role, content }) => ({ role, content })) },
      {
        onConversation: (savedId) => {
          if (conversationId == null) {
            const draft = qc.getQueryData<CoachChat>(key);
            const savedKey = chatKey(userId, savedId);
            qc.setQueryData(savedKey, draft);
            qc.setQueryData(key, EMPTY_CHAT);
            key = savedKey;
            // Follow the new chat — unless the user has already opened another.
            if (qc.getQueryData(activeKey(userId)) == null) qc.setQueryData(activeKey(userId), savedId);
          }
          qc.invalidateQueries({ queryKey: conversationsKey(userId) });
        },
        onDelta: (delta) => replaceLast((content) => content + delta),
        onError: (message) => {
          failed = true;
          replaceLast(() => message);
        },
        onDone: () => undefined,
      }
    ).catch(() => {
      failed = true;
      replaceLast(() => "The assistant is unavailable right now. Please try again.");
    });

    update((chat) => ({ ...chat, streaming: false }));
    // Reorders the list: this chat is now the most recent.
    qc.invalidateQueries({ queryKey: conversationsKey(userId) });
    return { failed };
  };
}

export function useDeleteCoachConversation(userId: UserId) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (conversationId: string) => api.delete<void>(`/copilot/conversations/${conversationId}`),
    onSuccess: (_data, conversationId) => {
      qc.removeQueries({ queryKey: chatKey(userId, conversationId) });
      if (qc.getQueryData(activeKey(userId)) === conversationId) qc.setQueryData(activeKey(userId), null);
      qc.invalidateQueries({ queryKey: conversationsKey(userId) });
    },
  });
}

export const useCreateDeal = () => useCreate<Deal, Record<string, unknown>>("deals", "/deals");
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
};

export type ConvertLeadInput = {
  leadId: string;
  meetingScheduledAt?: string;
  meetingMode?: string;
  meetingParticipants?: string;
};

/** Flow step 6. Creates a deal and an account, so those lists go stale too. */
export function useConvertLead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ leadId, ...body }: ConvertLeadInput) =>
      api.post<ConversionResult>(`/leads/${leadId}/convert`, body),
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

export const useAuditLog = () =>
  useQuery({ queryKey: ["audit-log"], queryFn: () => api.get<PagedResponse<AuditLogEntry>>("/audit-log?size=20") });

export const useDashboardSummary = () => useQuery({ queryKey: ["analytics", "dashboard"], queryFn: () => api.get<DashboardSummary>("/analytics/dashboard") });
export const useSecuritySummary = () => useQuery({ queryKey: ["analytics", "security"], queryFn: () => api.get<SecuritySummary>("/analytics/security") });
