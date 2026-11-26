import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { api } from "../../../lib/apiClient";
import type {
  AiResponse,
  AnalyticsFilters,
  CampaignsResponse,
  DealsResponse,
  LeadsResponse,
  MetaResponse,
  OverviewResponse,
  TeamResponse,
} from "./types";

const BASE = "/analytics";

function query(filters: AnalyticsFilters, allowed: (keyof AnalyticsFilters)[]): string {
  const usp = new URLSearchParams();
  for (const key of allowed) {
    const value = filters[key];
    if (value) usp.set(key, value);
  }
  const s = usp.toString();
  return s ? `?${s}` : "";
}

// Each section only sends the filters it understands, so changing the stage
// filter does not refetch (and flash) the Leads tab.
function useSection<T>(path: string, filters: AnalyticsFilters, allowed: (keyof AnalyticsFilters)[], enabled: boolean) {
  const qs = query(filters, allowed);
  return useQuery({
    queryKey: ["analytics", "section", path, qs],
    queryFn: () => api.get<T>(`${BASE}/${path}${qs}`),
    enabled,
    staleTime: 60_000,
    // Keep the previous numbers on screen while a new range loads, dimmed,
    // instead of collapsing every chart back to a skeleton.
    placeholderData: keepPreviousData,
  });
}

export const useAnalyticsMeta = () =>
  useQuery({ queryKey: ["analytics", "meta"], queryFn: () => api.get<MetaResponse>(`${BASE}/meta`), staleTime: 5 * 60_000 });

export const useOverview = (f: AnalyticsFilters, enabled = true) =>
  useSection<OverviewResponse>("overview", f, ["from", "to", "ownerId"], enabled);

export const useLeadsAnalytics = (f: AnalyticsFilters, enabled = true) =>
  useSection<LeadsResponse>("leads", f, ["from", "to", "ownerId", "source"], enabled);

export const useDealsAnalytics = (f: AnalyticsFilters, enabled = true) =>
  useSection<DealsResponse>("deals", f, ["from", "to", "ownerId", "stage"], enabled);

export const useTeamAnalytics = (f: AnalyticsFilters, enabled = true) =>
  useSection<TeamResponse>("team", f, ["from", "to"], enabled);

export const useAiAnalytics = (f: AnalyticsFilters, enabled = true) =>
  useSection<AiResponse>("ai", f, ["from", "to", "ownerId"], enabled);

export const useCampaignAnalytics = (f: AnalyticsFilters, enabled = true) =>
  useSection<CampaignsResponse>("campaigns", f, ["from", "to"], enabled);
