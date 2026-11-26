package com.techcrm.crm.analytics;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.techcrm.crm.analytics.NativeSql.bucket;
import static com.techcrm.crm.analytics.NativeSql.bucketDate;

/**
 * Every analytics aggregate, computed in Postgres.
 *
 * Nothing here loads entity rows: each method returns grouped or scalar
 * results (COUNT/SUM/AVG with FILTER, date_trunc buckets), and the service only
 * reshapes them. Each query is org-scoped first, then owner-scoped through
 * NativeSql.owner for the tables whose visibility follows a sales owner.
 *
 * Column semantics worth knowing when cross-checking numbers:
 * <ul>
 *   <li>A deal's close time is closed_at, falling back to updated_at for deals
 *       closed before V15 introduced closed_at.</li>
 *   <li>A case has no resolved_at column; a RESOLVED/CLOSED case's updated_at
 *       is used as its resolution time.</li>
 *   <li>A lead is "converted" when converted_at is set; that column is never
 *       reset, unlike converted_deal_id which nulls if the deal is deleted.</li>
 * </ul>
 */
@Repository
public class AnalyticsRepository {

    static final String OPEN_DEAL = "d.stage NOT IN ('CLOSED_WON', 'CLOSED_LOST')";
    static final String WON = "d.stage = 'CLOSED_WON'";
    static final String LOST = "d.stage = 'CLOSED_LOST'";
    static final String CLOSED_AT = "COALESCE(d.closed_at, d.updated_at)";
    static final String OPEN_CASE = "c.status IN ('OPEN', 'IN_PROGRESS', 'ESCALATED')";
    static final String RESOLVED_CASE = "c.status IN ('RESOLVED', 'CLOSED')";
    static final String RESOLUTION_HOURS = "EXTRACT(EPOCH FROM (c.updated_at - c.created_at)) / 3600.0";
    static final String QUALIFIED = "l.qualification_status = 'QUALIFIED'";

    private static final String CUR = " >= :cs AND %s < :ce";
    private static final String PREV = " >= :ps AND %s < :pe";

    @PersistenceContext
    private EntityManager em;

    private static String inCurrent(String column) {
        return column + String.format(CUR, column);
    }

    private static String inPrevious(String column) {
        return column + String.format(PREV, column);
    }

    /* ================================================================
     * Overview KPIs — current and previous window in one pass each
     * ================================================================ */

    /** [leadsCur, leadsPrev, qualifiedCur, qualifiedPrev] */
    public Object[] leadKpis(AnalyticsFilter f) {
        return leadsBase(f, "SELECT "
                + "COUNT(*) FILTER (WHERE " + inCurrent("l.created_at") + "), "
                + "COUNT(*) FILTER (WHERE " + inPrevious("l.created_at") + "), "
                + "COUNT(*) FILTER (WHERE " + inCurrent("l.created_at") + " AND " + QUALIFIED + "), "
                + "COUNT(*) FILTER (WHERE " + inPrevious("l.created_at") + " AND " + QUALIFIED + ") "
                + "FROM leads l WHERE l.organization_id = :org AND l.created_at >= :ps AND l.created_at < :ce")
                .single(em);
    }

    /** [openPipelineCur, openPipelinePrev, wonValueCur, wonValuePrev, wonCur,
     *  wonPrev, lostCur, lostPrev, avgScoreCur, avgScorePrev] */
    public Object[] dealKpis(AnalyticsFilter f) {
        return dealsBase(f, "SELECT "
                + "COALESCE(SUM(d.value) FILTER (WHERE " + OPEN_DEAL + " AND " + inCurrent("d.created_at") + "), 0), "
                + "COALESCE(SUM(d.value) FILTER (WHERE " + OPEN_DEAL + " AND " + inPrevious("d.created_at") + "), 0), "
                + "COALESCE(SUM(d.value) FILTER (WHERE " + WON + " AND " + inCurrent(CLOSED_AT) + "), 0), "
                + "COALESCE(SUM(d.value) FILTER (WHERE " + WON + " AND " + inPrevious(CLOSED_AT) + "), 0), "
                + "COUNT(*) FILTER (WHERE " + WON + " AND " + inCurrent(CLOSED_AT) + "), "
                + "COUNT(*) FILTER (WHERE " + WON + " AND " + inPrevious(CLOSED_AT) + "), "
                + "COUNT(*) FILTER (WHERE " + LOST + " AND " + inCurrent(CLOSED_AT) + "), "
                + "COUNT(*) FILTER (WHERE " + LOST + " AND " + inPrevious(CLOSED_AT) + "), "
                + "AVG(d.deal_score) FILTER (WHERE " + inCurrent("d.deal_scored_at") + "), "
                + "AVG(d.deal_score) FILTER (WHERE " + inPrevious("d.deal_scored_at") + ") "
                + "FROM deals d WHERE d.organization_id = :org")
                .single(em);
    }

    /** [leadMeetingsCur, leadMeetingsPrev, dealMeetingsCur, dealMeetingsPrev] */
    public Object[] meetingKpis(AnalyticsFilter f) {
        Object[] lead = new NativeSql("SELECT "
                + "COUNT(*) FILTER (WHERE m.meeting_date >= :cd AND m.meeting_date < :cde), "
                + "COUNT(*) FILTER (WHERE m.meeting_date >= :pd AND m.meeting_date < :cd) "
                + "FROM lead_meetings m JOIN leads l ON l.id = m.lead_id "
                + "WHERE m.organization_id = :org AND m.meeting_date >= :pd AND m.meeting_date < :cde")
                .window(f).owner("l.assigned_to_id", f).single(em);
        Object[] deal = new NativeSql("SELECT "
                + "COUNT(*) FILTER (WHERE o.meeting_date >= :cd AND o.meeting_date < :cde), "
                + "COUNT(*) FILTER (WHERE o.meeting_date >= :pd AND o.meeting_date < :cd) "
                + "FROM deal_meeting_outputs o JOIN deals d ON d.id = o.deal_id "
                + "WHERE o.organization_id = :org AND o.meeting_date >= :pd AND o.meeting_date < :cde")
                .window(f).owner("d.owner_id", f).single(em);
        return new Object[]{
                NativeSql.num(lead, 0), NativeSql.num(lead, 1), NativeSql.num(deal, 0), NativeSql.num(deal, 1)};
    }

    /** [sentCur, sentPrev, convertedCur, convertedPrev] */
    public Object[] campaignKpis(AnalyticsFilter f) {
        return new NativeSql("SELECT "
                + "COUNT(*) FILTER (WHERE " + inCurrent("m.sent_at") + "), "
                + "COUNT(*) FILTER (WHERE " + inPrevious("m.sent_at") + "), "
                + "COUNT(*) FILTER (WHERE " + inCurrent("m.converted_at") + "), "
                + "COUNT(*) FILTER (WHERE " + inPrevious("m.converted_at") + ") "
                + "FROM mkt_messages m JOIN mkt_campaigns c ON c.id = m.campaign_id "
                + "WHERE c.organization_id = :org")
                .window(f).single(em);
    }

    /* ================================================================
     * Leads
     * ================================================================ */

    /** [total, aiScored, qualified, assigned, contacted, converted, avgScore,
     *  hot, warm, cold] for leads created in the window. */
    public Object[] leadFunnel(AnalyticsFilter f) {
        return leadsBase(f, "SELECT COUNT(*), COUNT(l.ai_score), "
                + "COUNT(*) FILTER (WHERE " + QUALIFIED + "), "
                + "COUNT(l.assigned_to_id), "
                + "COUNT(*) FILTER (WHERE l.contact_status <> 'NOT_CONTACTED'), "
                + "COUNT(l.converted_at), "
                + "AVG(l.ai_score), "
                + "COUNT(*) FILTER (WHERE l.ai_score >= 75), "
                + "COUNT(*) FILTER (WHERE l.ai_score >= 45 AND l.ai_score < 75), "
                + "COUNT(*) FILTER (WHERE l.ai_score < 45) "
                + "FROM leads l WHERE l.organization_id = :org AND " + inCurrent("l.created_at"))
                .single(em);
    }

    /** [value, count] grouped on a lead column, for leads created in the window. */
    public List<Object[]> leadsGroupedBy(AnalyticsFilter f, String column) {
        return leadsBase(f, "SELECT COALESCE(" + column + ", 'UNKNOWN'), COUNT(*) "
                + "FROM leads l WHERE l.organization_id = :org AND " + inCurrent("l.created_at"))
                .append("GROUP BY 1 ORDER BY 2 DESC").list(em);
    }

    /** [source, leads, qualified, converted] */
    public List<Object[]> leadsBySource(AnalyticsFilter f) {
        return leadsBase(f, "SELECT COALESCE(NULLIF(TRIM(l.source_channel), ''), 'Unknown'), COUNT(*), "
                + "COUNT(*) FILTER (WHERE " + QUALIFIED + "), COUNT(l.converted_at) "
                + "FROM leads l WHERE l.organization_id = :org AND " + inCurrent("l.created_at"))
                .append("GROUP BY 1 ORDER BY 2 DESC").list(em);
    }

    /** [bucket, created, qualified, converted] */
    public List<Object[]> leadTrend(AnalyticsFilter f) {
        return leadsBase(f, "SELECT " + bucket("l.created_at", f) + ", COUNT(*), "
                + "COUNT(*) FILTER (WHERE " + QUALIFIED + "), COUNT(l.converted_at) "
                + "FROM leads l WHERE l.organization_id = :org AND " + inCurrent("l.created_at"))
                .append("GROUP BY 1 ORDER BY 1").list(em);
    }

    public List<String> leadSources(AnalyticsFilter f) {
        return new NativeSql("SELECT DISTINCT TRIM(l.source_channel) FROM leads l "
                + "WHERE l.organization_id = :org AND l.source_channel IS NOT NULL AND TRIM(l.source_channel) <> ''")
                .window(f).owner("l.assigned_to_id", f).append("ORDER BY 1")
                .list(em).stream().map(r -> NativeSql.str(r, 0)).toList();
    }

    private NativeSql leadsBase(AnalyticsFilter f, String sql) {
        return new NativeSql(sql).window(f)
                .owner("l.assigned_to_id", f)
                .andEquals("TRIM(l.source_channel)", "source", f.source());
    }

    /* ================================================================
     * Deals
     * ================================================================ */

    /** [stage, count, value] for deals created in the window. */
    public List<Object[]> dealsByStage(AnalyticsFilter f) {
        return dealsBase(f, "SELECT d.stage, COUNT(*), COALESCE(SUM(d.value), 0) FROM deals d "
                + "WHERE d.organization_id = :org AND " + inCurrent("d.created_at"))
                .append("GROUP BY 1").list(em);
    }

    /** [wonCount, wonValue, lostCount, lostValue] for deals closed in the window. */
    public Object[] wonLostTotals(AnalyticsFilter f) {
        return dealsBase(f, "SELECT COUNT(*) FILTER (WHERE " + WON + "), "
                + "COALESCE(SUM(d.value) FILTER (WHERE " + WON + "), 0), "
                + "COUNT(*) FILTER (WHERE " + LOST + "), "
                + "COALESCE(SUM(d.value) FILTER (WHERE " + LOST + "), 0) "
                + "FROM deals d WHERE d.organization_id = :org AND d.stage IN ('CLOSED_WON', 'CLOSED_LOST') AND "
                + inCurrent(CLOSED_AT))
                .single(em);
    }

    /** [bucket, won, lost, wonValue] */
    public List<Object[]> wonLostTrend(AnalyticsFilter f) {
        return dealsBase(f, "SELECT " + bucket(CLOSED_AT, f) + ", "
                + "COUNT(*) FILTER (WHERE " + WON + "), COUNT(*) FILTER (WHERE " + LOST + "), "
                + "COALESCE(SUM(d.value) FILTER (WHERE " + WON + "), 0) "
                + "FROM deals d WHERE d.organization_id = :org AND d.stage IN ('CLOSED_WON', 'CLOSED_LOST') AND "
                + inCurrent(CLOSED_AT))
                .append("GROUP BY 1 ORDER BY 1").list(em);
    }

    /** [bandIndex 0..4, count] of a 0–100 deal column, for deals scored in the window. */
    public List<Object[]> dealBandDistribution(AnalyticsFilter f, String column) {
        String band = "GREATEST(0, LEAST(4, CAST(FLOOR(" + column + " / 20) AS integer)))";
        return dealsBase(f, "SELECT " + band + ", COUNT(*) FROM deals d "
                + "WHERE d.organization_id = :org AND " + column + " IS NOT NULL AND " + inCurrent("d.deal_scored_at"))
                .append("GROUP BY 1 ORDER BY 1").list(em);
    }

    /** [riskLevel, count] across open deals as they stand now. */
    public List<Object[]> openDealsByRisk(AnalyticsFilter f) {
        return dealsBase(f, "SELECT COALESCE(d.risk_level, 'UNASSESSED'), COUNT(*) FROM deals d "
                + "WHERE d.organization_id = :org AND " + OPEN_DEAL)
                .append("GROUP BY 1").list(em);
    }

    /** [bucket, predictions, avgScore, avgWinProbability] from the prediction history. */
    public List<Object[]> scoreTrend(AnalyticsFilter f) {
        return new NativeSql("SELECT " + bucket("p.predicted_at", f) + ", COUNT(*), AVG(p.deal_score), "
                + "AVG(p.win_probability) FROM deal_predictions p JOIN deals d ON d.id = p.deal_id "
                + "WHERE p.organization_id = :org AND " + inCurrent("p.predicted_at"))
                .window(f).owner("d.owner_id", f).andEquals("d.stage", "stage", f.stage())
                .append("GROUP BY 1 ORDER BY 1").list(em);
    }

    /**
     * Open deals the AI flags as high risk, or — for deals scored before risk
     * levels existed — whose score is below the HIGH-risk threshold that
     * DealPredictionService.riskLevel starts from (45). Largest first.
     *
     * [id, opportunityId, name, accountName, ownerName, stage, value, currency,
     *  dealScore, winProbability, riskLevel, expectedCloseDate]
     */
    public List<Object[]> atRiskDeals(AnalyticsFilter f, int limit) {
        return dealsBase(f, "SELECT d.id, d.opportunity_id, d.name, a.name, u.full_name, d.stage, d.value, "
                + "d.currency, d.deal_score, d.win_probability, d.risk_level, "
                + "to_char(d.expected_close_date AT TIME ZONE '" + f.zone().getId() + "', 'YYYY-MM-DD') "
                + "FROM deals d LEFT JOIN accounts a ON a.id = d.account_id LEFT JOIN users u ON u.id = d.owner_id "
                + "WHERE d.organization_id = :org AND " + OPEN_DEAL
                + " AND (d.risk_level = 'HIGH' OR (d.risk_level IS NULL AND d.deal_score < 45))")
                .append("ORDER BY d.value DESC NULLS LAST, d.deal_score ASC NULLS LAST LIMIT " + limit)
                .list(em);
    }

    private NativeSql dealsBase(AnalyticsFilter f, String sql) {
        return new NativeSql(sql).window(f)
                .owner("d.owner_id", f)
                .andEquals("d.stage", "stage", f.stage());
    }

    /* ================================================================
     * Team
     * ================================================================ */

    /**
     * One row per person with sales activity in the window (every SALES_REP is
     * listed even when idle, so a quiet rep shows as zeros rather than
     * vanishing). Meetings are credited to whoever recorded them.
     *
     * [userId, name, role, leadsAssigned, leadsConverted, won, lost, revenue,
     *  meetings, openPipeline]
     */
    public List<Object[]> leaderboard(AnalyticsFilter f) {
        return new NativeSql(
                "WITH ld AS (SELECT l.assigned_to_id AS uid, COUNT(*) AS assigned, COUNT(l.converted_at) AS converted "
                        + "FROM leads l WHERE l.organization_id = :org AND l.assigned_to_id IS NOT NULL AND "
                        + inCurrent("l.created_at") + " GROUP BY 1), "
                        + "dl AS (SELECT d.owner_id AS uid, COUNT(*) FILTER (WHERE " + WON + ") AS won, "
                        + "COUNT(*) FILTER (WHERE " + LOST + ") AS lost, "
                        + "COALESCE(SUM(d.value) FILTER (WHERE " + WON + "), 0) AS revenue "
                        + "FROM deals d WHERE d.organization_id = :org AND d.owner_id IS NOT NULL "
                        + "AND d.stage IN ('CLOSED_WON', 'CLOSED_LOST') AND " + inCurrent(CLOSED_AT) + " GROUP BY 1), "
                        + "op AS (SELECT d.owner_id AS uid, COALESCE(SUM(d.value), 0) AS pipeline FROM deals d "
                        + "WHERE d.organization_id = :org AND d.owner_id IS NOT NULL AND " + OPEN_DEAL + " GROUP BY 1), "
                        + "mt AS (SELECT x.uid, COUNT(*) AS n FROM ("
                        + "SELECT m.recorded_by_id AS uid FROM lead_meetings m WHERE m.organization_id = :org "
                        + "AND m.meeting_date >= :cd AND m.meeting_date < :cde "
                        + "UNION ALL SELECT o.submitted_by_id FROM deal_meeting_outputs o WHERE o.organization_id = :org "
                        + "AND o.meeting_date >= :cd AND o.meeting_date < :cde) x "
                        + "WHERE x.uid IS NOT NULL GROUP BY 1) "
                        + "SELECT u.id, u.full_name, u.role, COALESCE(ld.assigned, 0), COALESCE(ld.converted, 0), "
                        + "COALESCE(dl.won, 0), COALESCE(dl.lost, 0), COALESCE(dl.revenue, 0), COALESCE(mt.n, 0), "
                        + "COALESCE(op.pipeline, 0) "
                        + "FROM users u LEFT JOIN ld ON ld.uid = u.id LEFT JOIN dl ON dl.uid = u.id "
                        + "LEFT JOIN op ON op.uid = u.id LEFT JOIN mt ON mt.uid = u.id "
                        + "WHERE u.organization_id = :org AND u.deleted_at IS NULL "
                        + "AND (u.role = 'SALES_REP' OR ld.uid IS NOT NULL OR dl.uid IS NOT NULL "
                        + "OR op.uid IS NOT NULL OR mt.uid IS NOT NULL)")
                .window(f).andEquals("u.id", "ownerFilter", f.ownerId())
                .append("ORDER BY 8 DESC, 6 DESC, 2").list(em);
    }

    public List<Object[]> owners(AnalyticsFilter f) {
        return new NativeSql("SELECT u.id, u.full_name FROM users u WHERE u.organization_id = :org "
                + "AND u.deleted_at IS NULL AND u.role IN ('SALES_REP', 'MANAGER', 'ADMIN')")
                .window(f).andEquals("u.id", "scopeOwner", f.scopeOwnerId())
                .append("ORDER BY u.full_name").list(em);
    }

    /* ================================================================
     * Meetings & AI
     * ================================================================ */

    /** [bucket, leadMeetings, dealMeetings] */
    public List<Object[]> meetingsTrend(AnalyticsFilter f) {
        NativeSql sql = new NativeSql("SELECT x.b, SUM(x.lm), SUM(x.dm) FROM ("
                + "SELECT " + bucketDate("m.meeting_date", f) + " AS b, 1 AS lm, 0 AS dm "
                + "FROM lead_meetings m JOIN leads l ON l.id = m.lead_id "
                + "WHERE m.organization_id = :org AND m.meeting_date >= :cd AND m.meeting_date < :cde")
                .window(f).owner("l.assigned_to_id", f)
                .append("UNION ALL SELECT " + bucketDate("o.meeting_date", f) + ", 0, 1 "
                        + "FROM deal_meeting_outputs o JOIN deals d ON d.id = o.deal_id "
                        + "WHERE o.organization_id = :org AND o.meeting_date >= :cd AND o.meeting_date < :cde")
                .owner("d.owner_id", f);
        return sql.append(") x GROUP BY 1 ORDER BY 1").list(em);
    }

    /** [source, count] of lead AI analyses (MODEL | REPAIRED | HEURISTIC). */
    public List<Object[]> leadAnalysesBySource(AnalyticsFilter f) {
        return new NativeSql("SELECT a.source, COUNT(*) FROM lead_ai_analysis a JOIN leads l ON l.id = a.lead_id "
                + "WHERE a.organization_id = :org AND " + inCurrent("a.created_at"))
                .window(f).owner("l.assigned_to_id", f).append("GROUP BY 1").list(em);
    }

    /** [status, count, latencySum, latencyCount] of deal analyses. */
    public List<Object[]> dealAnalysesByStatus(AnalyticsFilter f) {
        return new NativeSql("SELECT a.status, COUNT(*), COALESCE(SUM(a.latency_ms), 0), COUNT(a.latency_ms) "
                + "FROM deal_analyses a JOIN deals d ON d.id = a.deal_id "
                + "WHERE a.organization_id = :org AND " + inCurrent("a.created_at"))
                .window(f).owner("d.owner_id", f).append("GROUP BY 1").list(em);
    }

    /** [bucket, leadAnalyses, dealAnalyses, dealPredictions] */
    public List<Object[]> aiUsageTrend(AnalyticsFilter f) {
        NativeSql sql = new NativeSql("SELECT x.b, SUM(x.la), SUM(x.da), SUM(x.dp) FROM ("
                + "SELECT " + bucket("a.created_at", f) + " AS b, 1 AS la, 0 AS da, 0 AS dp "
                + "FROM lead_ai_analysis a JOIN leads l ON l.id = a.lead_id "
                + "WHERE a.organization_id = :org AND " + inCurrent("a.created_at"))
                .window(f).owner("l.assigned_to_id", f)
                .append("UNION ALL SELECT " + bucket("a.created_at", f) + ", 0, 1, 0 "
                        + "FROM deal_analyses a JOIN deals d ON d.id = a.deal_id "
                        + "WHERE a.organization_id = :org AND " + inCurrent("a.created_at"))
                .owner("d.owner_id", f)
                .append("UNION ALL SELECT " + bucket("p.predicted_at", f) + ", 0, 0, 1 "
                        + "FROM deal_predictions p JOIN deals d ON d.id = p.deal_id "
                        + "WHERE p.organization_id = :org AND " + inCurrent("p.predicted_at"))
                .owner("d.owner_id", f);
        return sql.append(") x GROUP BY 1 ORDER BY 1").list(em);
    }

    /** [riskLevelAtPrediction, decision, count] of manager reviews in the window. */
    public List<Object[]> reviewsByRisk(AnalyticsFilter f) {
        return new NativeSql("SELECT COALESCE(p.risk_level, 'UNKNOWN'), r.decision, COUNT(*) "
                + "FROM deal_manager_reviews r JOIN deals d ON d.id = r.deal_id "
                + "LEFT JOIN deal_predictions p ON p.id = r.prediction_id "
                + "WHERE r.organization_id = :org AND " + inCurrent("r.created_at"))
                .window(f).owner("d.owner_id", f).append("GROUP BY 1, 2").list(em);
    }

    /* ================================================================
     * Support
     * ================================================================ */

    /** [openNow, created, resolved, avgHours, medianHours, slaBreachedOpen, resolvedLate] */
    public Object[] caseStats(AnalyticsFilter f) {
        String resolvedInWindow = RESOLVED_CASE + " AND " + inCurrent("c.updated_at");
        return new NativeSql("SELECT "
                + "COUNT(*) FILTER (WHERE " + OPEN_CASE + "), "
                + "COUNT(*) FILTER (WHERE " + inCurrent("c.created_at") + "), "
                + "COUNT(*) FILTER (WHERE " + resolvedInWindow + "), "
                + "AVG(" + RESOLUTION_HOURS + ") FILTER (WHERE " + resolvedInWindow + "), "
                + "percentile_cont(0.5) WITHIN GROUP (ORDER BY " + RESOLUTION_HOURS + ") FILTER (WHERE "
                + resolvedInWindow + "), "
                + "COUNT(*) FILTER (WHERE " + OPEN_CASE + " AND c.sla_deadline < now()), "
                + "COUNT(*) FILTER (WHERE " + resolvedInWindow + " AND c.updated_at > c.sla_deadline) "
                + "FROM cases c WHERE c.organization_id = :org")
                .window(f).single(em);
    }

    /** [openNow, createdCur, createdPrev, resolvedCur, resolvedPrev, avgHoursCur, avgHoursPrev] */
    public Object[] caseKpis(AnalyticsFilter f) {
        return new NativeSql("SELECT "
                + "COUNT(*) FILTER (WHERE " + OPEN_CASE + "), "
                + "COUNT(*) FILTER (WHERE " + inCurrent("c.created_at") + "), "
                + "COUNT(*) FILTER (WHERE " + inPrevious("c.created_at") + "), "
                + "COUNT(*) FILTER (WHERE " + RESOLVED_CASE + " AND " + inCurrent("c.updated_at") + "), "
                + "COUNT(*) FILTER (WHERE " + RESOLVED_CASE + " AND " + inPrevious("c.updated_at") + "), "
                + "AVG(" + RESOLUTION_HOURS + ") FILTER (WHERE " + RESOLVED_CASE + " AND " + inCurrent("c.updated_at") + "), "
                + "AVG(" + RESOLUTION_HOURS + ") FILTER (WHERE " + RESOLVED_CASE + " AND " + inPrevious("c.updated_at") + ") "
                + "FROM cases c WHERE c.organization_id = :org")
                .window(f).single(em);
    }

    /** [value, count] of cases created in the window, grouped on a case column. */
    public List<Object[]> casesGroupedBy(AnalyticsFilter f, String column) {
        return new NativeSql("SELECT " + column + ", COUNT(*) FROM cases c "
                + "WHERE c.organization_id = :org AND " + inCurrent("c.created_at"))
                .window(f).append("GROUP BY 1").list(em);
    }

    /** [bucket, created, resolved] */
    public List<Object[]> caseTrend(AnalyticsFilter f) {
        return new NativeSql("SELECT x.b, SUM(x.cr), SUM(x.rs) FROM ("
                + "SELECT " + bucket("c.created_at", f) + " AS b, 1 AS cr, 0 AS rs FROM cases c "
                + "WHERE c.organization_id = :org AND " + inCurrent("c.created_at")
                + " UNION ALL SELECT " + bucket("c.updated_at", f) + ", 0, 1 FROM cases c "
                + "WHERE c.organization_id = :org AND " + RESOLVED_CASE + " AND " + inCurrent("c.updated_at")
                + ") x GROUP BY 1 ORDER BY 1")
                .window(f).list(em);
    }

    /** [userId, name, openNow, resolvedInWindow] */
    public List<Object[]> casesByAssignee(AnalyticsFilter f) {
        String resolvedInWindow = RESOLVED_CASE + " AND " + inCurrent("c.updated_at");
        return new NativeSql("SELECT c.assigned_to_id, COALESCE(u.full_name, 'Unassigned'), "
                + "COUNT(*) FILTER (WHERE " + OPEN_CASE + "), COUNT(*) FILTER (WHERE " + resolvedInWindow + ") "
                + "FROM cases c LEFT JOIN users u ON u.id = c.assigned_to_id "
                + "WHERE c.organization_id = :org AND (" + OPEN_CASE + " OR (" + resolvedInWindow + "))")
                .window(f).append("GROUP BY 1, 2 ORDER BY 3 DESC, 4 DESC").list(em);
    }

    /* ================================================================
     * Campaigns (Marketing Automation, mkt_*)
     * ================================================================ */

    /** [status, count] across all of the organization's campaigns. */
    public List<Object[]> campaignsByStatus(AnalyticsFilter f) {
        return new NativeSql("SELECT c.status, COUNT(*) FROM mkt_campaigns c WHERE c.organization_id = :org")
                .window(f).append("GROUP BY 1 ORDER BY 2 DESC").list(em);
    }

    /** Messages sent in the window and what became of them:
     *  [sent, delivered, opened, clicked, responded, converted, failed] */
    public Object[] campaignFunnel(AnalyticsFilter f) {
        String sent = inCurrent("m.sent_at");
        return new NativeSql("SELECT COUNT(*) FILTER (WHERE " + sent + "), "
                + "COUNT(m.delivered_at) FILTER (WHERE " + sent + "), "
                + "COUNT(m.opened_at) FILTER (WHERE " + sent + "), "
                + "COUNT(m.clicked_at) FILTER (WHERE " + sent + "), "
                + "COUNT(m.responded_at) FILTER (WHERE " + sent + "), "
                + "COUNT(m.converted_at) FILTER (WHERE " + sent + "), "
                + "COUNT(*) FILTER (WHERE m.status = 'FAILED' AND " + inCurrent("m.queued_at") + ") "
                + "FROM mkt_messages m JOIN mkt_campaigns c ON c.id = m.campaign_id WHERE c.organization_id = :org")
                .window(f).single(em);
    }

    /** [id, name, type, status, sent, delivered, opened, clicked, converted] */
    public List<Object[]> campaignRows(AnalyticsFilter f) {
        return new NativeSql("SELECT c.id, c.name, c.campaign_type, c.status, COUNT(*), COUNT(m.delivered_at), "
                + "COUNT(m.opened_at), COUNT(m.clicked_at), COUNT(m.converted_at) "
                + "FROM mkt_campaigns c JOIN mkt_messages m ON m.campaign_id = c.id "
                + "WHERE c.organization_id = :org AND " + inCurrent("m.sent_at"))
                .window(f).append("GROUP BY c.id, c.name, c.campaign_type, c.status ORDER BY 5 DESC LIMIT 50")
                .list(em);
    }

    /** [bucket, sent, opened, clicked, converted], each on its own event time. */
    public List<Object[]> campaignTrend(AnalyticsFilter f) {
        String[] eventColumns = {"m.sent_at", "m.opened_at", "m.clicked_at", "m.converted_at"};
        StringBuilder union = new StringBuilder();
        for (int i = 0; i < eventColumns.length; i++) {
            if (i > 0) union.append(" UNION ALL ");
            union.append("SELECT ").append(bucket(eventColumns[i], f)).append(" AS b");
            for (int s = 0; s < eventColumns.length; s++) {
                union.append(", ").append(s == i ? 1 : 0).append(" AS s").append(s + 1);
            }
            union.append(" FROM mkt_messages m JOIN mkt_campaigns c ON c.id = m.campaign_id ")
                    .append("WHERE c.organization_id = :org AND ").append(inCurrent(eventColumns[i]));
        }
        return new NativeSql("SELECT x.b, SUM(x.s1), SUM(x.s2), SUM(x.s3), SUM(x.s4) FROM ("
                + union + ") x GROUP BY 1 ORDER BY 1")
                .window(f).list(em);
    }

    /* ================================================================
     * Home dashboard (/api/analytics/dashboard)
     * ================================================================ */

    /** [totalLeads] visible to the caller. */
    public long countLeads(Long organizationId, Long scopeOwnerId) {
        NativeSql sql = new NativeSql("SELECT COUNT(*) FROM leads l WHERE l.organization_id = :org")
                .param("org", organizationId).andEquals("l.assigned_to_id", "scopeOwner", scopeOwnerId);
        return NativeSql.num(sql.single(em), 0);
    }

    /** [stage, count, value] across every deal visible to the caller. */
    public List<Object[]> pipelineByStage(Long organizationId, Long scopeOwnerId) {
        return new NativeSql("SELECT d.stage, COUNT(*), COALESCE(SUM(d.value), 0) FROM deals d "
                + "WHERE d.organization_id = :org")
                .param("org", organizationId).andEquals("d.owner_id", "scopeOwner", scopeOwnerId)
                .append("GROUP BY 1").list(em);
    }

    public long countOpenCases(Long organizationId) {
        return NativeSql.num(new NativeSql("SELECT COUNT(*) FROM cases c WHERE c.organization_id = :org AND "
                + OPEN_CASE).param("org", organizationId).single(em), 0);
    }

    /** [active, total] — a bot is active once deployed, scheduled or running. */
    public Object[] rpaBotCounts(Long organizationId) {
        return new NativeSql("SELECT COUNT(*) FILTER (WHERE b.status IN ('DEPLOYED', 'SCHEDULED', 'RUNNING')), "
                + "COUNT(*) FROM rpa_bots b WHERE b.organization_id = :org")
                .param("org", organizationId).single(em);
    }

    /* ================================================================
     * Security summary (/api/analytics/security)
     * ================================================================ */

    /** [auditEvents24h, failedLogins24h] */
    public Object[] securityCounts(Long organizationId) {
        return new NativeSql("SELECT "
                + "(SELECT COUNT(*) FROM audit_log a WHERE a.organization_id = :org "
                + "AND a.occurred_at >= now() - INTERVAL '24 hours'), "
                + "(SELECT COUNT(*) FROM login_history h WHERE h.organization_id = :org AND h.success = false "
                + "AND h.occurred_at >= now() - INTERVAL '24 hours')")
                .param("org", organizationId).single(em);
    }
}
