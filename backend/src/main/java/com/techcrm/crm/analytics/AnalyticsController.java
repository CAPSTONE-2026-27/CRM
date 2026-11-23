package com.techcrm.crm.analytics;

import com.techcrm.crm.analytics.AnalyticsAccess.Section;
import com.techcrm.crm.analytics.AnalyticsDtos.*;
import com.techcrm.crm.auth.AuthenticatedUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Every section takes the same optional filters:
 * <ul>
 *   <li>from, to — inclusive ISO dates (default: the last 30 days)</li>
 *   <li>ownerId — a sales owner (leads' assignee, deals' owner)</li>
 *   <li>source — lead source channel (leads section)</li>
 *   <li>stage — deal stage (deals section)</li>
 * </ul>
 * A filter a section has no use for is ignored.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final AnalyticsReportService reports;

    public AnalyticsController(AnalyticsService analyticsService, AnalyticsReportService reports) {
        this.analyticsService = analyticsService;
        this.reports = reports;
    }

    @GetMapping("/dashboard")
    public DashboardSummaryResponse dashboard(@AuthenticationPrincipal AuthenticatedUser caller) {
        return analyticsService.dashboard(caller);
    }

    @GetMapping("/security")
    public SecuritySummaryResponse security(@AuthenticationPrincipal AuthenticatedUser caller) {
        return analyticsService.security(caller);
    }

    @GetMapping("/meta")
    public MetaResponse meta(@AuthenticationPrincipal AuthenticatedUser caller) {
        return reports.meta(caller);
    }

    @GetMapping("/overview")
    public OverviewResponse overview(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) Long ownerId) {
        return reports.overview(reports.filter(caller, Section.OVERVIEW, from, to, ownerId, null, null), caller);
    }

    @GetMapping("/leads")
    public LeadsResponse leads(@AuthenticationPrincipal AuthenticatedUser caller,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Long ownerId,
                               @RequestParam(required = false) String source) {
        return reports.leads(reports.filter(caller, Section.LEADS, from, to, ownerId, source, null));
    }

    @GetMapping("/deals")
    public DealsResponse deals(@AuthenticationPrincipal AuthenticatedUser caller,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Long ownerId,
                               @RequestParam(required = false) String stage) {
        return reports.deals(reports.filter(caller, Section.DEALS, from, to, ownerId, null, stage));
    }

    @GetMapping("/team")
    public TeamResponse team(@AuthenticationPrincipal AuthenticatedUser caller,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             @RequestParam(required = false) Long ownerId) {
        return reports.team(reports.filter(caller, Section.TEAM, from, to, ownerId, null, null));
    }

    @GetMapping("/ai")
    public AiResponse ai(@AuthenticationPrincipal AuthenticatedUser caller,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(required = false) Long ownerId) {
        return reports.ai(reports.filter(caller, Section.AI, from, to, ownerId, null, null));
    }

    @GetMapping("/support")
    public SupportResponse support(@AuthenticationPrincipal AuthenticatedUser caller,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.support(reports.filter(caller, Section.SUPPORT, from, to, null, null, null));
    }

    @GetMapping("/campaigns")
    public CampaignsResponse campaigns(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.campaigns(reports.filter(caller, Section.CAMPAIGNS, from, to, null, null, null));
    }
}
