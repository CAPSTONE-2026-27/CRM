import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../../../lib/apiClient";
import type {
  AbTest,
  AbTestRequest,
  ActivityItem,
  AnalyticsResponse,
  ApprovalItem,
  CampaignDetail,
  CampaignLead,
  CampaignRequest,
  CampaignSummary,
  ContentChannel,
  ContentItem,
  DashboardResponse,
  EmailPreview,
  LeadAction,
  MetaResponse,
  Range,
  StrategyRequest,
} from "./types";

const BASE = "/marketing";

// Every key starts with "mkt" so one invalidation refreshes the whole module
// after a decision: counts on the dashboard, the queue, the campaign and its
// tabs all derive from the same records.
const keys = {
  all: ["mkt"] as const,
  meta: ["mkt", "meta"] as const,
  dashboard: ["mkt", "dashboard"] as const,
  campaigns: ["mkt", "campaigns"] as const,
  approvals: ["mkt", "approvals"] as const,
  campaign: (id: string) => ["mkt", "campaign", id] as const,
  leads: (id: string) => ["mkt", "campaign", id, "leads"] as const,
  content: (id: string) => ["mkt", "campaign", id, "content"] as const,
  activity: (id: string) => ["mkt", "campaign", id, "activity"] as const,
  preview: (id: string, contentId: string, updatedAt: string, leadId: string | null) =>
    ["mkt", "campaign", id, "preview", contentId, updatedAt, leadId ?? "sample"] as const,
  analytics: (id: string | null, range: Range) => ["mkt", "analytics", id ?? "org", range] as const,
};

function rangeQuery(range: Range, extra?: Record<string, string>) {
  const p = new URLSearchParams({ range: range.key, ...(extra ?? {}) });
  if (range.key === "custom") {
    if (range.from) p.set("from", range.from);
    if (range.to) p.set("to", range.to);
  }
  return `?${p.toString()}`;
}

const isRunning = (c?: CampaignDetail) =>
  !!c && (c.pipeline.leadStatus === "RUNNING" || c.pipeline.contentStatus === "RUNNING");

/* ---------------- queries ---------------- */

export const useMarketingMeta = () =>
  useQuery({ queryKey: keys.meta, queryFn: () => api.get<MetaResponse>(`${BASE}/meta`), staleTime: 60_000 });

export const useMarketingDashboard = () =>
  useQuery({ queryKey: keys.dashboard, queryFn: () => api.get<DashboardResponse>(`${BASE}/dashboard`), refetchInterval: 30_000 });

export const useCampaignList = () =>
  useQuery({ queryKey: keys.campaigns, queryFn: () => api.get<CampaignSummary[]>(`${BASE}/campaigns`), refetchInterval: 30_000 });

export const useApprovalQueue = () =>
  useQuery({ queryKey: keys.approvals, queryFn: () => api.get<ApprovalItem[]>(`${BASE}/approvals`), refetchInterval: 30_000 });

/** Polls every 2.5s while either AI pipeline is running, so progress stays live. */
export const useCampaign = (id: string | null) =>
  useQuery({
    queryKey: keys.campaign(id ?? ""),
    queryFn: () => api.get<CampaignDetail>(`${BASE}/campaigns/${id}`),
    enabled: !!id,
    refetchInterval: (q) => (isRunning(q.state.data) ? 2500 : false),
  });

export const useCampaignLeads = (id: string, live: boolean) =>
  useQuery({
    queryKey: keys.leads(id),
    queryFn: () => api.get<CampaignLead[]>(`${BASE}/campaigns/${id}/leads`),
    refetchInterval: live ? 4000 : false,
  });

export const useCampaignContent = (id: string, live: boolean) =>
  useQuery({
    queryKey: keys.content(id),
    queryFn: () => api.get<ContentItem[]>(`${BASE}/campaigns/${id}/content`),
    refetchInterval: live ? 3000 : false,
  });

/** The email rendered as a recipient gets it. Keyed on updatedAt so an edit re-renders. */
export const useEmailPreview = (id: string, contentId: string, updatedAt: string, leadId: string | null) =>
  useQuery({
    queryKey: keys.preview(id, contentId, updatedAt, leadId),
    queryFn: () =>
      api.get<EmailPreview>(`${BASE}/campaigns/${id}/content/${contentId}/preview${leadId ? `?leadId=${encodeURIComponent(leadId)}` : ""}`),
    staleTime: 60_000,
  });

export const useCampaignActivity = (id: string) =>
  useQuery({ queryKey: keys.activity(id), queryFn: () => api.get<ActivityItem[]>(`${BASE}/campaigns/${id}/activity`) });

export const useAnalytics = (campaignId: string | null, range: Range, enabled = true) =>
  useQuery({
    queryKey: keys.analytics(campaignId, range),
    queryFn: () =>
      api.get<AnalyticsResponse>(
        campaignId
          ? `${BASE}/campaigns/${campaignId}/analytics${rangeQuery(range)}`
          : `${BASE}/analytics${rangeQuery(range)}`
      ),
    enabled: enabled && (range.key !== "custom" || (!!range.from && !!range.to)),
    placeholderData: (prev) => prev,
  });

/* ---------------- mutations ---------------- */

function useMkMutation<TVars, TResult>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

export const useCreateCampaign = () =>
  useMkMutation(({ body, submit }: { body: CampaignRequest; submit: boolean }) =>
    api.post<CampaignDetail>(`${BASE}/campaigns?submit=${submit}`, body)
  );

export const useUpdateCampaign = () =>
  useMkMutation(({ id, body }: { id: string; body: CampaignRequest }) =>
    api.patch<CampaignDetail>(`${BASE}/campaigns/${id}`, body)
  );

export const useCampaignCommand = () =>
  useMkMutation(
    ({
      id,
      command,
      body,
    }: {
      id: string;
      command: "submit" | "duplicate" | "launch" | "unschedule" | "pause" | "resume" | "cancel" | "leads/analyze";
      body?: unknown;
    }) => api.post<CampaignDetail>(`${BASE}/campaigns/${id}/${command}`, body)
  );

export const useScheduleCampaign = () =>
  useMkMutation(({ id, scheduledAt }: { id: string; scheduledAt: string }) =>
    api.post<CampaignDetail>(`${BASE}/campaigns/${id}/schedule`, { scheduledAt })
  );

export const useDeleteCampaign = () => useMkMutation((id: string) => api.delete<void>(`${BASE}/campaigns/${id}`));

export const useUpdateStrategy = () =>
  useMkMutation(({ id, body }: { id: string; body: StrategyRequest }) =>
    api.patch<CampaignDetail>(`${BASE}/campaigns/${id}/strategy`, body)
  );

export const useLeadDecision = () =>
  useMkMutation(({ id, leadRowId, action, note }: { id: string; leadRowId: string; action: LeadAction; note?: string }) =>
    api.post<CampaignLead>(`${BASE}/campaigns/${id}/leads/${leadRowId}/decision`, { action, note })
  );

export const useBulkLeadDecision = () =>
  useMkMutation(({ id, ids, action, note }: { id: string; ids: string[]; action: LeadAction; note?: string }) =>
    api.post<{ updated: number; skipped: number }>(`${BASE}/campaigns/${id}/leads/decisions`, { ids, action, note })
  );

export const useLeadOverride = () =>
  useMkMutation(
    ({ id, leadRowId, eligibility, note }: { id: string; leadRowId: string; eligibility: "ELIGIBLE" | "NOT_ELIGIBLE"; note: string }) =>
      api.post<CampaignLead>(`${BASE}/campaigns/${id}/leads/${leadRowId}/override`, { eligibility, note })
  );

export const useGenerateContent = () =>
  useMkMutation(
    ({ id, channels, variant, instructions }: { id: string; channels?: ContentChannel[]; variant?: "A" | "B"; instructions?: string }) =>
      api.post<CampaignDetail>(`${BASE}/campaigns/${id}/content/generate`, { channels, variant: variant ?? "A", instructions })
  );

export const useUpdateContent = () =>
  useMkMutation(
    ({ id, contentId, fields, draft }: { id: string; contentId: string; fields: Record<string, string>; draft: boolean }) =>
      api.patch<ContentItem>(`${BASE}/campaigns/${id}/content/${contentId}`, { fields, draft })
  );

export const useContentDecision = () =>
  useMkMutation(({ id, contentId, approve, note }: { id: string; contentId: string; approve: boolean; note?: string }) =>
    api.post<ContentItem>(`${BASE}/campaigns/${id}/content/${contentId}/${approve ? "approve" : "reject"}`, { note })
  );

export const useConfigureAbTest = () =>
  useMkMutation(({ id, body }: { id: string; body: AbTestRequest }) => api.post<AbTest>(`${BASE}/campaigns/${id}/ab-test`, body));

export const useChooseWinner = () =>
  useMkMutation(({ id, variant, note }: { id: string; variant: "A" | "B"; note?: string }) =>
    api.post<AbTest>(`${BASE}/campaigns/${id}/ab-test/winner`, { variant, note })
  );
