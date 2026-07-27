import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./apiClient";
import type {
  Account,
  AuditLogEntry,
  Campaign,
  CaseRow,
  Contact,
  DashboardSummary,
  Deal,
  Lead,
  LeadStats,
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

export const useAccounts = () => useList<Account>("accounts", "/accounts");
export const useCreateAccount = () => useCreate<Account, Record<string, unknown>>("accounts", "/accounts");

export const useContacts = () => useList<Contact>("contacts", "/contacts");
export const useCreateContact = () => useCreate<Contact, Partial<Contact>>("contacts", "/contacts");

export const useDeals = () => useList<Deal>("deals", "/deals");
export const useCreateDeal = () => useCreate<Deal, Record<string, unknown>>("deals", "/deals");
export function useUpdateDealStage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, stage }: { id: string; stage: string }) => api.patch<Deal>(`/deals/${id}`, { stage }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["deals"] }),
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
