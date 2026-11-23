package com.techcrm.crm.analytics;

import com.techcrm.crm.analytics.AnalyticsAccess.Section;
import com.techcrm.crm.analytics.AnalyticsDtos.*;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.deal.DealStages;
import com.techcrm.crm.user.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.techcrm.crm.analytics.NativeSql.dbl;
import static com.techcrm.crm.analytics.NativeSql.dec;
import static com.techcrm.crm.analytics.NativeSql.num;
import static com.techcrm.crm.analytics.NativeSql.rate;
import static com.techcrm.crm.analytics.NativeSql.str;

/**
 * The analytics sections behind /api/analytics/{overview,leads,deals,team,ai,
 * support,campaigns}. Aggregation happens in AnalyticsRepository; this class
 * applies the access rule, resolves the filter, and turns rows into complete,
 * zero-filled responses.
 */
@Service
public class AnalyticsReportService {

    private static final int AT_RISK_LIMIT = 10;
    private static final List<String> SCORE_BANDS = List.of("0-19", "20-39", "40-59", "60-79", "80-100");
    private static final List<String> RISK_LEVELS = List.of("HIGH", "MEDIUM", "LOW", "UNASSESSED");
    private static final List<String> CASE_STATUSES = List.of("OPEN", "IN_PROGRESS", "ESCALATED", "RESOLVED", "CLOSED");
    private static final List<String> CASE_PRIORITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    private static final List<String> QUALIFICATION = List.of("QUALIFIED", "PENDING", "UNQUALIFIED");
    private static final List<String> LEAD_STATUSES = List.of("HOT", "WARM", "COLD", "NEW");

    private final AnalyticsRepository repository;
    private final ZoneId zone;

    public AnalyticsReportService(AnalyticsRepository repository,
                                  @Value("${app.analytics.zone:Asia/Kolkata}") String zone) {
        this.repository = repository;
        this.zone = ZoneId.of(zone);
    }

    /** Resolves the request into a filter, enforcing the section rule first. */
    public AnalyticsFilter filter(AuthenticatedUser caller, Section section, LocalDate from, LocalDate to,
                                  Long ownerId, String source, String stage) {
        AnalyticsAccess.requireSection(caller, section);
        Long scope = AnalyticsAccess.isScopedToSelf(caller) ? caller.userId() : null;
        return AnalyticsFilter.of(caller.organizationId(), scope, from, to, ownerId, source, stage, zone);
    }

    /* ================================================================ meta */

    @Transactional(readOnly = true)
    public MetaResponse meta(AuthenticatedUser caller) {
        AnalyticsAccess.requireModule(caller);
        Long scope = AnalyticsAccess.isScopedToSelf(caller) ? caller.userId() : null;
        AnalyticsFilter f = AnalyticsFilter.of(caller.organizationId(), scope, null, null, null, null, null, zone);
        boolean sales = AnalyticsAccess.canSee(caller, Section.LEADS) || AnalyticsAccess.canSee(caller, Section.DEALS);
        List<Option> owners = sales
                ? repository.owners(f).stream().map(r -> new Option(str(r, 0), str(r, 1))).toList()
                : List.of();
        List<String> sources = AnalyticsAccess.canSee(caller, Section.LEADS) ? repository.leadSources(f) : List.of();
        List<String> stages = AnalyticsAccess.canSee(caller, Section.DEALS) ? DealStages.ORDERED : List.of();
        return new MetaResponse(AnalyticsAccess.sectionsFor(caller), scope != null, owners, sources, stages,
                zone.getId(), "INR");
    }

    /* ============================================================ overview */

    /**
     * The KPI strip, shaped by role: each role gets the figures its sections
     * cover and nothing else, so a support agent is never shown a zero
     * "pipeline" that really means "not yours to see".
     */
    @Transactional(readOnly = true)
    public OverviewResponse overview(AnalyticsFilter f, AuthenticatedUser caller) {
        List<Kpi> kpis = new ArrayList<>();
        boolean leads = AnalyticsAccess.canSee(caller, Section.LEADS);
        boolean deals = AnalyticsAccess.canSee(caller, Section.DEALS);
        boolean support = AnalyticsAccess.canSee(caller, Section.SUPPORT);
        boolean campaigns = AnalyticsAccess.canSee(caller, Section.CAMPAIGNS);

        if (leads) {
            Object[] l = repository.leadKpis(f);
            kpis.add(kpi("totalLeads", "Total leads", "count", num(l, 0), num(l, 1), "Leads created in period"));
            kpis.add(kpi("qualifiedLeads", "Qualified leads", "count", num(l, 2), num(l, 3),
                    "Created in period, now qualified"));
        }
        if (deals) {
            Object[] d = repository.dealKpis(f);
            kpis.add(new Kpi("openPipeline", "Open pipeline value", "currency", dec(d, 0), dec(d, 1),
                    "Open deals created in period"));
            kpis.add(new Kpi("wonRevenue", "Won revenue", "currency", dec(d, 2), dec(d, 3), "Closed won in period"));
            kpis.add(new Kpi("winRate", "Win rate", "percent",
                    pct(num(d, 4), num(d, 4) + num(d, 6)), pct(num(d, 5), num(d, 5) + num(d, 7)),
                    "Won ÷ (won + lost), closed in period"));
            kpis.add(new Kpi("avgDealScore", "Avg deal score", "score", scale1(d, 8), scale1(d, 9),
                    "Deals scored in period"));
            Object[] m = repository.meetingKpis(f);
            kpis.add(kpi("meetingsHeld", "Meetings held", "count", num(m, 0) + num(m, 2), num(m, 1) + num(m, 3),
                    "Lead and deal meetings"));
        }
        if (support) {
            Object[] c = repository.caseKpis(f);
            kpis.add(new Kpi("openTickets", "Open tickets", "count", BigDecimal.valueOf(num(c, 0)), null,
                    "Open, in progress or escalated now"));
            if (caller.role() == Role.SUPPORT_AGENT) {
                kpis.add(kpi("ticketsCreated", "Tickets created", "count", num(c, 1), num(c, 2), "Opened in period"));
                kpis.add(kpi("ticketsResolved", "Tickets resolved", "count", num(c, 3), num(c, 4),
                        "Resolved or closed in period"));
                kpis.add(new Kpi("avgResolution", "Avg resolution time", "hours", scale1(c, 5), scale1(c, 6),
                        "Created → resolved"));
            }
        }
        if (campaigns && !deals) {
            Object[] c = repository.campaignKpis(f);
            kpis.add(kpi("messagesSent", "Campaign messages sent", "count", num(c, 0), num(c, 1), "Sent in period"));
            kpis.add(kpi("campaignConversions", "Campaign conversions", "count", num(c, 2), num(c, 3),
                    "Recipients converted in period"));
        }
        return new OverviewResponse(f.window(), f.scopeOwnerId() != null, kpis);
    }

    /* =============================================================== leads */

    @Transactional(readOnly = true)
    public LeadsResponse leads(AnalyticsFilter f) {
        Object[] t = repository.leadFunnel(f);
        long total = num(t, 0);
        List<FunnelStep> funnel = List.of(
                new FunnelStep("created", "Created", total),
                new FunnelStep("aiScored", "AI scored", num(t, 1)),
                new FunnelStep("qualified", "Qualified", num(t, 2)),
                new FunnelStep("assigned", "Assigned", num(t, 3)),
                new FunnelStep("contacted", "Contacted", num(t, 4)),
                new FunnelStep("converted", "Converted to deal", num(t, 5)));
        List<CountRow> tiers = List.of(
                new CountRow("Hot (75+)", num(t, 7)),
                new CountRow("Warm (45–74)", num(t, 8)),
                new CountRow("Cold (<45)", num(t, 9)),
                new CountRow("Unscored", total - num(t, 1)));

        List<SourceRow> bySource = repository.leadsBySource(f).stream()
                .map(r -> new SourceRow(str(r, 0), num(r, 1), num(r, 2), num(r, 3), rate(num(r, 3), num(r, 1))))
                .toList();

        Map<LocalDate, Object[]> trendRows = byBucket(repository.leadTrend(f));
        List<LeadTrendPoint> trend = f.buckets().stream().map(b -> {
            Object[] r = trendRows.getOrDefault(b, new Object[0]);
            return new LeadTrendPoint(b, num(r, 1), num(r, 2), num(r, 3));
        }).toList();

        return new LeadsResponse(f.window(), total, dbl(t, 6), funnel,
                ordered(repository.leadsGroupedBy(f, "l.status"), LEAD_STATUSES),
                ordered(repository.leadsGroupedBy(f, "l.qualification_status"), QUALIFICATION),
                tiers, bySource, trend);
    }

    /* =============================================================== deals */

    @Transactional(readOnly = true)
    public DealsResponse deals(AnalyticsFilter f) {
        Map<String, Object[]> stageRows = new HashMap<>();
        for (Object[] r : repository.dealsByStage(f)) stageRows.put(str(r, 0), r);
        List<ValueRow> byStage = new ArrayList<>();
        for (String stage : DealStages.ORDERED) {
            Object[] r = stageRows.getOrDefault(stage, new Object[0]);
            byStage.add(new ValueRow(stage, num(r, 1), dec(r, 2)));
        }

        Object[] wl = repository.wonLostTotals(f);
        long won = num(wl, 0);
        long lost = num(wl, 2);
        BigDecimal wonValue = dec(wl, 1);
        BigDecimal avgWon = won == 0 ? null : wonValue.divide(BigDecimal.valueOf(won), 2, RoundingMode.HALF_UP);

        Map<LocalDate, Object[]> wlRows = byBucket(repository.wonLostTrend(f));
        List<WonLostPoint> wonLostTrend = f.buckets().stream().map(b -> {
            Object[] r = wlRows.getOrDefault(b, new Object[0]);
            return new WonLostPoint(b, num(r, 1), num(r, 2), dec(r, 3));
        }).toList();

        Map<LocalDate, Object[]> scoreRows = byBucket(repository.scoreTrend(f));
        List<ScoreTrendPoint> scoreTrend = f.buckets().stream().map(b -> {
            Object[] r = scoreRows.getOrDefault(b, new Object[0]);
            return new ScoreTrendPoint(b, num(r, 1), dbl(r, 2), dbl(r, 3));
        }).toList();

        List<AtRiskDeal> atRisk = repository.atRiskDeals(f, AT_RISK_LIMIT).stream().map(r -> {
            String risk = str(r, 10);
            String reason = risk != null ? "AI risk level: high" : "Low deal score";
            String close = str(r, 11);
            return new AtRiskDeal(str(r, 0), str(r, 1), str(r, 2), str(r, 3), str(r, 4), str(r, 5), dec(r, 6),
                    str(r, 7), dbl(r, 8), dbl(r, 9), risk, reason, close == null ? null : LocalDate.parse(close));
        }).toList();

        return new DealsResponse(f.window(), byStage, won, wonValue, lost, dec(wl, 3), rate(won, won + lost), avgWon,
                wonLostTrend,
                bands(repository.dealBandDistribution(f, "d.deal_score")),
                bands(repository.dealBandDistribution(f, "d.win_probability")),
                ordered(repository.openDealsByRisk(f), RISK_LEVELS),
                scoreTrend, atRisk);
    }

    /* ================================================================ team */

    @Transactional(readOnly = true)
    public TeamResponse team(AnalyticsFilter f) {
        List<RepRow> reps = repository.leaderboard(f).stream().map(r -> {
            long assigned = num(r, 3);
            long converted = num(r, 4);
            long won = num(r, 5);
            long lost = num(r, 6);
            return new RepRow(str(r, 0), str(r, 1), str(r, 2), assigned, converted, rate(converted, assigned),
                    won, lost, dec(r, 7), rate(won, won + lost), num(r, 8), dec(r, 9));
        }).toList();
        return new TeamResponse(f.window(), reps);
    }

    /* ======================================================= meetings & AI */

    @Transactional(readOnly = true)
    public AiResponse ai(AnalyticsFilter f) {
        Map<LocalDate, Object[]> meetingRows = byBucket(repository.meetingsTrend(f));
        long leadMeetings = 0;
        long dealMeetings = 0;
        List<MeetingPoint> meetings = new ArrayList<>();
        for (LocalDate b : f.buckets()) {
            Object[] r = meetingRows.getOrDefault(b, new Object[0]);
            leadMeetings += num(r, 1);
            dealMeetings += num(r, 2);
            meetings.add(new MeetingPoint(b, num(r, 1), num(r, 2)));
        }

        Object[] leadTotals = repository.leadFunnel(f);

        List<CountRow> leadAnalyses = ordered(repository.leadAnalysesBySource(f), List.of("MODEL", "REPAIRED", "HEURISTIC"));

        List<Object[]> analysisRows = repository.dealAnalysesByStatus(f);
        long latencySum = 0;
        long latencyCount = 0;
        for (Object[] r : analysisRows) {
            latencySum += num(r, 2);
            latencyCount += num(r, 3);
        }
        List<CountRow> dealAnalyses = ordered(analysisRows, List.of("SUCCEEDED", "DEGRADED", "FAILED"));

        Map<LocalDate, Object[]> usageRows = byBucket(repository.aiUsageTrend(f));
        long predictions = 0;
        List<AiUsagePoint> usage = new ArrayList<>();
        for (LocalDate b : f.buckets()) {
            Object[] r = usageRows.getOrDefault(b, new Object[0]);
            predictions += num(r, 3);
            usage.add(new AiUsagePoint(b, num(r, 1), num(r, 2), num(r, 3)));
        }

        Map<String, long[]> byRisk = new LinkedHashMap<>();
        for (String level : List.of("HIGH", "MEDIUM", "LOW", "UNKNOWN")) byRisk.put(level, new long[3]);
        long approved = 0;
        long rejected = 0;
        long overridden = 0;
        for (Object[] r : repository.reviewsByRisk(f)) {
            long[] counts = byRisk.computeIfAbsent(str(r, 0), k -> new long[3]);
            long n = num(r, 2);
            switch (String.valueOf(str(r, 1))) {
                case "APPROVED" -> { counts[0] += n; approved += n; }
                case "REJECTED" -> { counts[1] += n; rejected += n; }
                case "OVERRIDDEN" -> { counts[2] += n; overridden += n; }
                default -> { }
            }
        }
        List<ReviewByRisk> reviewsByRisk = byRisk.entrySet().stream()
                .filter(e -> e.getValue()[0] + e.getValue()[1] + e.getValue()[2] > 0)
                .map(e -> new ReviewByRisk(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
        long reviews = approved + rejected + overridden;

        return new AiResponse(f.window(), leadMeetings, dealMeetings, meetings,
                num(leadTotals, 0), num(leadTotals, 1), leadAnalyses, dealAnalyses, predictions,
                latencyCount == 0 ? null : NativeSql.round1((double) latencySum / latencyCount),
                usage,
                List.of(new CountRow("APPROVED", approved), new CountRow("REJECTED", rejected),
                        new CountRow("OVERRIDDEN", overridden)),
                reviewsByRisk, rate(overridden + rejected, reviews));
    }

    /* ============================================================= support */

    @Transactional(readOnly = true)
    public SupportResponse support(AnalyticsFilter f) {
        Object[] s = repository.caseStats(f);
        Map<LocalDate, Object[]> trendRows = byBucket(repository.caseTrend(f));
        List<SupportPoint> trend = f.buckets().stream().map(b -> {
            Object[] r = trendRows.getOrDefault(b, new Object[0]);
            return new SupportPoint(b, num(r, 1), num(r, 2));
        }).toList();
        List<AssigneeRow> byAssignee = repository.casesByAssignee(f).stream()
                .map(r -> new AssigneeRow(str(r, 0), str(r, 1), num(r, 2), num(r, 3))).toList();
        return new SupportResponse(f.window(), num(s, 0), num(s, 1), num(s, 2), dbl(s, 3), dbl(s, 4),
                num(s, 5), num(s, 6),
                ordered(repository.casesGroupedBy(f, "c.status"), CASE_STATUSES),
                ordered(repository.casesGroupedBy(f, "c.priority"), CASE_PRIORITIES),
                trend, byAssignee);
    }

    /* =========================================================== campaigns */

    @Transactional(readOnly = true)
    public CampaignsResponse campaigns(AnalyticsFilter f) {
        List<CountRow> byStatus = repository.campaignsByStatus(f).stream()
                .map(r -> new CountRow(str(r, 0), num(r, 1))).toList();
        long totalCampaigns = byStatus.stream().mapToLong(CountRow::count).sum();

        Object[] fu = repository.campaignFunnel(f);
        long sent = num(fu, 0);
        long delivered = num(fu, 1);
        long base = delivered > 0 ? delivered : sent;
        CampaignFunnel funnel = new CampaignFunnel(sent, delivered, num(fu, 2), num(fu, 3), num(fu, 4), num(fu, 5),
                num(fu, 6), rate(num(fu, 2), base), rate(num(fu, 3), base), rate(num(fu, 5), base));

        Map<LocalDate, Object[]> trendRows = byBucket(repository.campaignTrend(f));
        List<CampaignPoint> trend = f.buckets().stream().map(b -> {
            Object[] r = trendRows.getOrDefault(b, new Object[0]);
            return new CampaignPoint(b, num(r, 1), num(r, 2), num(r, 3), num(r, 4));
        }).toList();

        List<CampaignRow> rows = repository.campaignRows(f).stream().map(r -> {
            long rowSent = num(r, 4);
            long rowDelivered = num(r, 5);
            long rowBase = rowDelivered > 0 ? rowDelivered : rowSent;
            return new CampaignRow(str(r, 0), str(r, 1), str(r, 2), str(r, 3), rowSent, rowDelivered, num(r, 6),
                    num(r, 7), num(r, 8), rate(num(r, 6), rowBase), rate(num(r, 7), rowBase), rate(num(r, 8), rowBase));
        }).toList();

        return new CampaignsResponse(f.window(), totalCampaigns, byStatus, funnel, trend, rows);
    }

    /* ============================================================= helpers */

    private static Kpi kpi(String key, String label, String format, long value, long previous, String hint) {
        return new Kpi(key, label, format, BigDecimal.valueOf(value), BigDecimal.valueOf(previous), hint);
    }

    private static BigDecimal pct(long part, long whole) {
        Double r = rate(part, whole);
        return r == null ? null : BigDecimal.valueOf(r);
    }

    private static BigDecimal scale1(Object[] row, int i) {
        Double d = dbl(row, i);
        return d == null ? null : BigDecimal.valueOf(d);
    }

    /** Rows keyed by their first column, a 'YYYY-MM-DD' bucket. */
    private static Map<LocalDate, Object[]> byBucket(List<Object[]> rows) {
        Map<LocalDate, Object[]> out = new HashMap<>();
        for (Object[] r : rows) {
            String b = str(r, 0);
            if (b != null) out.put(LocalDate.parse(b), r);
        }
        return out;
    }

    /** [key, count] rows in a fixed order with zeros filled in; unknown keys
     *  the vocabulary doesn't list are appended so nothing is silently dropped. */
    private static List<CountRow> ordered(List<Object[]> rows, List<String> vocabulary) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String key : vocabulary) counts.put(key, 0L);
        for (Object[] r : rows) counts.merge(String.valueOf(str(r, 0)), num(r, 1), Long::sum);
        return counts.entrySet().stream().map(e -> new CountRow(e.getKey(), e.getValue())).toList();
    }

    private static List<CountRow> bands(List<Object[]> rows) {
        long[] counts = new long[SCORE_BANDS.size()];
        for (Object[] r : rows) {
            int index = (int) num(r, 0);
            if (index >= 0 && index < counts.length) counts[index] += num(r, 1);
        }
        List<CountRow> out = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) out.add(new CountRow(SCORE_BANDS.get(i), counts[i]));
        return out;
    }
}
