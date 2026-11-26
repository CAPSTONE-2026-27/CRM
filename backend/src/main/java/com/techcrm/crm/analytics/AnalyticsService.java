package com.techcrm.crm.analytics;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.deal.DealAccess;
import com.techcrm.crm.deal.DealStages;
import com.techcrm.crm.lead.LeadMapper;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.lead.LeadResponse;
import com.techcrm.crm.rpa.RpaBot;
import com.techcrm.crm.rpa.RpaBotRepository;
import com.techcrm.crm.rpa.RpaBotRun;
import com.techcrm.crm.rpa.RpaBotRunRepository;
import com.techcrm.crm.rpa.RpaDtos.RpaBotRunResponse;
import com.techcrm.crm.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.techcrm.crm.analytics.NativeSql.dec;
import static com.techcrm.crm.analytics.NativeSql.num;
import static com.techcrm.crm.analytics.NativeSql.str;

/**
 * The home dashboard and the Security & audit headline figures. The sectioned
 * analytics module lives in AnalyticsReportService.
 */
@Service
public class AnalyticsService {

    private final AnalyticsRepository analyticsRepository;
    private final LeadRepository leadRepository;
    private final RpaBotRunRepository rpaBotRunRepository;
    private final RpaBotRepository rpaBotRepository;

    public AnalyticsService(AnalyticsRepository analyticsRepository, LeadRepository leadRepository,
                            RpaBotRunRepository rpaBotRunRepository, RpaBotRepository rpaBotRepository) {
        this.analyticsRepository = analyticsRepository;
        this.leadRepository = leadRepository;
        this.rpaBotRunRepository = rpaBotRunRepository;
        this.rpaBotRepository = rpaBotRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse dashboard(AuthenticatedUser caller) {
        Long org = caller.organizationId();
        // Same visibility rule as LeadService and DealAccess: ADMIN/MANAGER see
        // the whole org, SALES_REP/SUPPORT_AGENT see only what's assigned to
        // them — so "Total leads" means "leads assigned to me" for a rep and
        // "leads in my org" for an admin.
        Long scopeOwner = DealAccess.isScopedToOwnDeals(caller) ? caller.userId() : null;

        List<LeadResponse> recentLeads = (scopeOwner != null
                ? leadRepository.findTop5ByOrganizationIdAndAssignedToIdOrderByCreatedAtDesc(org, scopeOwner)
                : leadRepository.findTop5ByOrganizationIdOrderByCreatedAtDesc(org))
                .stream().map(LeadMapper::toResponse).toList();

        Map<String, Object[]> stageRows = new HashMap<>();
        for (Object[] row : analyticsRepository.pipelineByStage(org, scopeOwner)) stageRows.put(str(row, 0), row);
        BigDecimal openPipeline = BigDecimal.ZERO;
        List<PipelineStageSummary> pipeline = DealStages.ORDERED.stream()
                .filter(stageRows::containsKey)
                .map(stage -> {
                    Object[] row = stageRows.get(stage);
                    return new PipelineStageSummary(stage, new PipelineStageSummary.Sum(dec(row, 2)),
                            new PipelineStageSummary.Count(num(row, 1)));
                }).toList();
        for (PipelineStageSummary s : pipeline) {
            if (!DealStages.isClosed(s.stage())) openPipeline = openPipeline.add(s.sum().value());
        }

        Object[] bots = analyticsRepository.rpaBotCounts(org);
        DashboardMetrics metrics = new DashboardMetrics(
                analyticsRepository.countLeads(org, scopeOwner),
                openPipeline,
                analyticsRepository.countOpenCases(org),
                num(bots, 0),
                num(bots, 1));

        return new DashboardSummaryResponse(metrics, recentLeads, pipeline, recentBotRuns(org));
    }

    private List<RpaBotRunResponse> recentBotRuns(Long org) {
        List<RpaBotRun> runs = rpaBotRunRepository.findTop5ByOrganizationIdOrderByStartedAtDesc(org);
        if (runs.isEmpty()) return List.of();
        Map<Long, RpaBot> bots = rpaBotRepository.findAllById(runs.stream().map(RpaBotRun::getBotId).distinct().toList())
                .stream().filter(b -> org.equals(b.getOrganizationId()))
                .collect(Collectors.toMap(RpaBot::getId, Function.identity()));
        return runs.stream().map(r -> RpaBotRunResponse.from(r, bots.get(r.getBotId()))).toList();
    }

    /** Gated like the Security & audit screen: admins always, anyone else
     *  only with the "security" screen permission. */
    @Transactional(readOnly = true)
    public SecuritySummaryResponse security(AuthenticatedUser caller) {
        boolean allowed = caller.role() == Role.ADMIN
                || (caller.permissions() != null && caller.permissions().contains("security"));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to Security & audit.");
        }
        Object[] counts = analyticsRepository.securityCounts(caller.organizationId());
        return new SecuritySummaryResponse(num(counts, 0), num(counts, 1), 0);
    }
}
