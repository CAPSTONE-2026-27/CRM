package com.techcrm.crm.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response shapes for /api/analytics/{section}. Every list is complete (zero
 * rows are filled in where the UI needs a fixed set, e.g. pipeline stages and
 * time buckets), and every aggregate over no rows is 0 or null — never an
 * error — so an empty organization renders as empty charts.
 *
 * Rates are percentages 0–100 with one decimal, or null when the denominator
 * is zero ("no closed deals" is not a 0% win rate).
 */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record Window(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                         String granularity, String zone) {
    }

    /** format: count | currency | percent | score | hours. previous is null
     *  for a point-in-time figure that has no history to compare against. */
    public record Kpi(String key, String label, String format, BigDecimal value, BigDecimal previous, String hint) {
    }

    public record CountRow(String key, long count) {
    }

    public record ValueRow(String key, long count, BigDecimal value) {
    }

    /* --------------------------------------------------------------- meta */

    public record Option(String value, String label) {
    }

    public record MetaResponse(List<String> sections, boolean scopedToSelf, List<Option> owners,
                               List<String> sources, List<String> stages, String zone, String currency) {
    }

    /* ----------------------------------------------------------- overview */

    public record OverviewResponse(Window window, boolean scopedToSelf, List<Kpi> kpis) {
    }

    /* -------------------------------------------------------------- leads */

    public record FunnelStep(String key, String label, long count) {
    }

    public record SourceRow(String source, long leads, long qualified, long converted, Double conversionRate) {
    }

    public record LeadTrendPoint(LocalDate bucket, long created, long qualified, long converted) {
    }

    public record LeadsResponse(Window window, long total, Double averageScore, List<FunnelStep> funnel,
                                List<CountRow> byStatus, List<CountRow> byQualification, List<CountRow> scoreTiers,
                                List<SourceRow> bySource, List<LeadTrendPoint> trend) {
    }

    /* -------------------------------------------------------------- deals */

    public record WonLostPoint(LocalDate bucket, long won, long lost, BigDecimal wonValue) {
    }

    public record ScoreTrendPoint(LocalDate bucket, long predictions, Double avgDealScore, Double avgWinProbability) {
    }

    public record AtRiskDeal(String id, String opportunityId, String name, String accountName, String ownerName,
                             String stage, BigDecimal value, String currency, Double dealScore,
                             Double winProbability, String riskLevel, String reason, LocalDate expectedCloseDate) {
    }

    public record DealsResponse(Window window, List<ValueRow> byStage, long wonCount, BigDecimal wonValue,
                                long lostCount, BigDecimal lostValue, Double winRate, BigDecimal avgWonDealSize,
                                List<WonLostPoint> wonLostTrend, List<CountRow> scoreDistribution,
                                List<CountRow> winProbabilityDistribution, List<CountRow> openByRisk,
                                List<ScoreTrendPoint> scoreTrend, List<AtRiskDeal> atRisk) {
    }

    /* --------------------------------------------------------------- team */

    public record RepRow(String userId, String name, String role, long leadsAssigned, long leadsConverted,
                         Double conversionRate, long dealsWon, long dealsLost, BigDecimal revenueWon,
                         Double winRate, long meetings, BigDecimal openPipeline) {
    }

    public record TeamResponse(Window window, List<RepRow> reps) {
    }

    /* ------------------------------------------------------ meetings & AI */

    public record MeetingPoint(LocalDate bucket, long leadMeetings, long dealMeetings) {
    }

    public record AiUsagePoint(LocalDate bucket, long leadAnalyses, long dealAnalyses, long dealPredictions) {
    }

    public record ReviewByRisk(String riskLevel, long approved, long rejected, long overridden) {
    }

    public record AiResponse(Window window, long leadMeetings, long dealMeetings, List<MeetingPoint> meetingsTrend,
                             long leadsCreated, long leadsAiScored, List<CountRow> leadAnalysesBySource,
                             List<CountRow> dealAnalysesByStatus, long dealPredictions,
                             Double avgDealAnalysisLatencyMs, List<AiUsagePoint> aiUsageTrend,
                             List<CountRow> reviewDecisions, List<ReviewByRisk> reviewsByRisk,
                             Double overrideRate) {
    }

    /* ------------------------------------------------------------ support */

    public record SupportPoint(LocalDate bucket, long created, long resolved) {
    }

    public record AssigneeRow(String userId, String name, long open, long resolved) {
    }

    public record SupportResponse(Window window, long openNow, long created, long resolved,
                                  Double avgResolutionHours, Double medianResolutionHours,
                                  long slaBreachedOpen, long resolvedLate, List<CountRow> byStatus,
                                  List<CountRow> byPriority, List<SupportPoint> trend,
                                  List<AssigneeRow> byAssignee) {
    }

    /* ---------------------------------------------------------- campaigns */

    public record CampaignFunnel(long sent, long delivered, long opened, long clicked, long responded,
                                 long converted, long failed, Double openRate, Double clickRate,
                                 Double conversionRate) {
    }

    public record CampaignPoint(LocalDate bucket, long sent, long opened, long clicked, long converted) {
    }

    public record CampaignRow(String id, String name, String type, String status, long sent, long delivered,
                              long opened, long clicked, long converted, Double openRate, Double clickRate,
                              Double conversionRate) {
    }

    public record CampaignsResponse(Window window, long totalCampaigns, List<CountRow> byStatus,
                                    CampaignFunnel funnel, List<CampaignPoint> trend, List<CampaignRow> campaigns) {
    }
}
