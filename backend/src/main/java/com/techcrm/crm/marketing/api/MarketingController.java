package com.techcrm.crm.marketing.api;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.LeadRequest;
import com.techcrm.crm.marketing.abtest.AbTestService;
import com.techcrm.crm.marketing.ai.LlmService;
import com.techcrm.crm.marketing.ai.MarketingAiOrchestrator;
import com.techcrm.crm.marketing.analytics.CampaignAnalyticsService;
import com.techcrm.crm.marketing.api.MarketingDtos.*;
import com.techcrm.crm.marketing.approval.ApprovalQueryService;
import com.techcrm.crm.marketing.campaign.CampaignStatus;
import com.techcrm.crm.marketing.campaign.CampaignType;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.campaign.MarketingCampaignService;
import com.techcrm.crm.marketing.common.MarketingAccess;
import com.techcrm.crm.marketing.content.ContentChannel;
import com.techcrm.crm.marketing.content.ContentReviewService;
import com.techcrm.crm.marketing.email.EmailSettings;
import com.techcrm.crm.marketing.execution.CampaignExecutionService;
import com.techcrm.crm.marketing.leads.LeadReviewService;
import com.techcrm.crm.marketing.leads.rules.RuleField;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * REST API for Marketing Automation, under /api/marketing.
 *
 * Authorization is checked in every handler (MarketingAccess), and every
 * campaign lookup is scoped to the caller's organization, so a campaign id
 * from another tenant is indistinguishable from one that doesn't exist.
 * Viewing, creating, editing and generating need the "marketing" permission;
 * every decision that lets AI output reach a customer needs an approver.
 */
@RestController
@RequestMapping("/api/marketing")
public class MarketingController {

    private final MarketingCampaignService campaigns;
    private final MarketingAiOrchestrator orchestrator;
    private final LeadReviewService leadReview;
    private final ContentReviewService contentReview;
    private final AbTestService abTests;
    private final CampaignExecutionService execution;
    private final CampaignAnalyticsService analytics;
    private final ApprovalQueryService approvalQueries;
    private final LlmService llm;
    private final EmailSettings emailSettings;

    public MarketingController(MarketingCampaignService campaigns, MarketingAiOrchestrator orchestrator,
                               LeadReviewService leadReview, ContentReviewService contentReview, AbTestService abTests,
                               CampaignExecutionService execution, CampaignAnalyticsService analytics,
                               ApprovalQueryService approvalQueries, LlmService llm, EmailSettings emailSettings) {
        this.campaigns = campaigns;
        this.orchestrator = orchestrator;
        this.leadReview = leadReview;
        this.contentReview = contentReview;
        this.abTests = abTests;
        this.execution = execution;
        this.analytics = analytics;
        this.approvalQueries = approvalQueries;
        this.llm = llm;
        this.emailSettings = emailSettings;
    }

    /* ================================================================
     * Metadata and dashboard
     * ================================================================ */

    @GetMapping("/meta")
    public MetaResponse meta(@AuthenticationPrincipal AuthenticatedUser caller) {
        MarketingAccess.requireView(caller);
        List<FieldMeta> fields = Arrays.stream(RuleField.values()).map(f -> new FieldMeta(f.name(), f.label(),
                f.kind().name(), f.operators().stream().map(Enum::name).toList(),
                switch (f) {
                    case INTEREST_LEVEL -> RuleField.LEVELS;
                    case CUSTOMER_TYPE -> CUSTOMER_TYPES;
                    case QUALIFICATION_STATUS -> List.of("PENDING", "QUALIFIED", "UNQUALIFIED");
                    default -> List.of();
                }, f.hint())).toList();
        return new MetaResponse(
                Arrays.stream(CampaignType.values()).map(Enum::name).toList(),
                List.of(ContentChannel.EMAIL.name()),
                fields, LeadRequest.PURCHASE_TIMELINES, CUSTOMER_TYPES,
                new AiStatus(llm.modelName(), llm.selfHosted(), llm.externalProviderAllowed(), orchestrator.fallbackEnabled(),
                        llm.lastCallSucceeded(), llm.lastCallAt()),
                emailSettings.status(),
                MarketingAccess.isApprover(caller));
    }

    private static final List<String> CUSTOMER_TYPES = List.of("New Business", "Existing Customer", "Renewal", "Upsell", "Partner");

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(@AuthenticationPrincipal AuthenticatedUser caller) {
        MarketingAccess.requireView(caller);
        return analytics.dashboard(caller);
    }

    @GetMapping("/analytics")
    public AnalyticsResponse orgAnalytics(@AuthenticationPrincipal AuthenticatedUser caller,
                                          @RequestParam(defaultValue = "30d") String range,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(required = false) Long campaignId) {
        MarketingAccess.requireView(caller);
        return analytics.forOrganization(caller, range, from, to, campaignId);
    }

    @GetMapping("/approvals")
    public List<ApprovalItem> approvalQueue(@AuthenticationPrincipal AuthenticatedUser caller) {
        MarketingAccess.requireView(caller);
        return approvalQueries.queue(caller);
    }

    /* ================================================================
     * Campaigns
     * ================================================================ */

    @GetMapping("/campaigns")
    public List<CampaignSummary> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        MarketingAccess.requireView(caller);
        return campaigns.list(caller);
    }

    /** Create Campaign. With submit=true, validates and starts both AI pipelines. */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignDetail> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                 @Valid @RequestBody CampaignRequest request,
                                                 @RequestParam(defaultValue = "false") boolean submit) {
        MarketingAccess.requireView(caller);
        if (submit) MarketingCampaignService.validateForSubmission(request.objective(), request.productName(),
                request.description(), request.startDate(), request.endDate());
        MarketingCampaign c = campaigns.create(caller, request);
        if (submit) orchestrator.submit(c.getId(), caller.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(campaigns.detail(caller, c.getId()));
    }

    @GetMapping("/campaigns/{id}")
    public CampaignDetail get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireView(caller);
        return campaigns.detail(caller, id);
    }

    @PatchMapping("/campaigns/{id}")
    public CampaignDetail update(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                 @Valid @RequestBody CampaignRequest request) {
        MarketingAccess.requireView(caller);
        campaigns.update(caller, id, request);
        return campaigns.detail(caller, id);
    }

    @DeleteMapping("/campaigns/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireApprover(caller);
        campaigns.delete(caller, id);
        return ResponseEntity.noContent().build();
    }

    /** Submit a draft for AI processing (both pipelines, in parallel). */
    @PostMapping("/campaigns/{id}/submit")
    public CampaignDetail submit(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireView(caller);
        MarketingCampaign c = campaigns.require(caller, id);
        if (c.getStatus() != CampaignStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a draft can be submitted; use the re-run actions to process it again");
        }
        campaigns.validateForSubmission(c);
        orchestrator.submit(id, caller.userId());
        return campaigns.detail(caller, id);
    }

    @PostMapping("/campaigns/{id}/duplicate")
    public ResponseEntity<CampaignDetail> duplicate(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireView(caller);
        MarketingCampaign copy = campaigns.duplicate(caller, id);
        return ResponseEntity.status(HttpStatus.CREATED).body(campaigns.detail(caller, copy.getId()));
    }

    @PatchMapping("/campaigns/{id}/strategy")
    public CampaignDetail strategy(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                   @Valid @RequestBody StrategyRequest request) {
        MarketingAccess.requireView(caller);
        campaigns.updateStrategy(caller, id, request);
        return campaigns.detail(caller, id);
    }

    @GetMapping("/campaigns/{id}/activity")
    public List<ActivityItem> activity(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireView(caller);
        return analytics.campaignActivity(campaigns.require(caller, id));
    }

    /* ================================================================
     * Pipeline A: leads
     * ================================================================ */

    /** Analyze Leads: (re)run AI evaluation over the lead database. */
    @PostMapping("/campaigns/{id}/leads/analyze")
    public CampaignDetail analyzeLeads(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireView(caller);
        MarketingCampaign c = campaigns.require(caller, id);
        campaigns.validateForSubmission(c);
        orchestrator.startLeadPipeline(id, caller.userId());
        return campaigns.detail(caller, id);
    }

    /** Get Lead Scores. */
    @GetMapping("/campaigns/{id}/leads")
    public List<CampaignLeadResponse> leads(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireView(caller);
        return leadReview.list(campaigns.require(caller, id));
    }

    /** Approve / Reject / Remove / Restore one lead. */
    @PostMapping("/campaigns/{id}/leads/{campaignLeadId}/decision")
    public CampaignLeadResponse decideLead(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                           @PathVariable Long campaignLeadId, @Valid @RequestBody LeadDecisionRequest request) {
        MarketingAccess.requireApprover(caller);
        return leadReview.decide(caller, campaigns.require(caller, id), campaignLeadId, request.action(), request.note());
    }

    @PostMapping("/campaigns/{id}/leads/decisions")
    public BulkResult decideLeads(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                  @Valid @RequestBody BulkLeadDecisionRequest request) {
        MarketingAccess.requireApprover(caller);
        return leadReview.decideMany(caller, campaigns.require(caller, id), request.ids(), request.action(), request.note());
    }

    @PostMapping("/campaigns/{id}/leads/{campaignLeadId}/override")
    public CampaignLeadResponse overrideLead(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                             @PathVariable Long campaignLeadId, @Valid @RequestBody LeadOverrideRequest request) {
        MarketingAccess.requireApprover(caller);
        return leadReview.override(caller, campaigns.require(caller, id), campaignLeadId, request.eligibility(), request.note());
    }

    /* ================================================================
     * Pipeline B: content
     * ================================================================ */

    @GetMapping("/campaigns/{id}/content")
    public List<ContentResponse> content(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireView(caller);
        return contentReview.list(campaigns.require(caller, id));
    }

    /** Generate Content / Regenerate (optionally per channel, with feedback, or variant B). */
    @PostMapping("/campaigns/{id}/content/generate")
    public CampaignDetail generate(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                   @Valid @RequestBody(required = false) GenerateContentRequest request) {
        MarketingAccess.requireView(caller);
        MarketingCampaign c = campaigns.require(caller, id);
        campaigns.validateForSubmission(c);
        List<ContentChannel> channels = request == null || request.channels() == null ? null
                : request.channels().stream().map(ContentChannel::valueOf).toList();
        orchestrator.startContentPipeline(id, caller.userId(), channels,
                request == null ? "A" : request.variant(), request == null ? null : request.instructions());
        return campaigns.detail(caller, id);
    }

    /** Update Content (edit or save as draft). */
    @PatchMapping("/campaigns/{id}/content/{contentId}")
    public ContentResponse updateContent(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                         @PathVariable Long contentId, @Valid @RequestBody ContentUpdateRequest request) {
        MarketingAccess.requireView(caller);
        return contentReview.update(caller, campaigns.require(caller, id), contentId, request.fields(), request.draft());
    }

    /** Preview the email as a recipient gets it, for a given lead or with sample values. */
    @GetMapping("/campaigns/{id}/content/{contentId}/preview")
    public EmailPreview previewContent(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                       @PathVariable Long contentId, @RequestParam(required = false) Long leadId) {
        MarketingAccess.requireView(caller);
        return contentReview.preview(campaigns.require(caller, id), contentId, leadId);
    }

    @PostMapping("/campaigns/{id}/content/{contentId}/approve")
    public ContentResponse approveContent(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                          @PathVariable Long contentId, @Valid @RequestBody(required = false) ContentDecisionRequest request) {
        MarketingAccess.requireApprover(caller);
        return contentReview.decide(caller, campaigns.require(caller, id), contentId, true, request == null ? null : request.note());
    }

    @PostMapping("/campaigns/{id}/content/{contentId}/reject")
    public ContentResponse rejectContent(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                         @PathVariable Long contentId, @Valid @RequestBody ContentDecisionRequest request) {
        MarketingAccess.requireApprover(caller);
        return contentReview.decide(caller, campaigns.require(caller, id), contentId, false, request.note());
    }

    /* ================================================================
     * A/B testing
     * ================================================================ */

    /** Create / update the A/B test. */
    @PostMapping("/campaigns/{id}/ab-test")
    public AbTestResponse configureAbTest(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                          @Valid @RequestBody AbTestRequest request) {
        MarketingAccess.requireView(caller);
        return abTests.configure(caller, campaigns.require(caller, id), request);
    }

    @PostMapping("/campaigns/{id}/ab-test/winner")
    public AbTestResponse chooseWinner(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                       @Valid @RequestBody WinnerRequest request) {
        MarketingAccess.requireApprover(caller);
        MarketingCampaign c = campaigns.require(caller, id);
        Map<String, Funnel> measured = analytics.funnelsByVariant(List.of(c.getId()));
        return abTests.chooseWinner(caller, c, request.variant(), request.note(), measured);
    }

    /* ================================================================
     * Execution
     * ================================================================ */

    @PostMapping("/campaigns/{id}/launch")
    public CampaignDetail launch(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireApprover(caller);
        execution.launch(caller, campaigns.require(caller, id));
        return campaigns.detail(caller, id);
    }

    @PostMapping("/campaigns/{id}/schedule")
    public CampaignDetail schedule(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                   @Valid @RequestBody ScheduleRequest request) {
        MarketingAccess.requireApprover(caller);
        execution.schedule(caller, campaigns.require(caller, id), request.scheduledAt());
        return campaigns.detail(caller, id);
    }

    @PostMapping("/campaigns/{id}/unschedule")
    public CampaignDetail unschedule(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireApprover(caller);
        execution.unschedule(caller, campaigns.require(caller, id));
        return campaigns.detail(caller, id);
    }

    @PostMapping("/campaigns/{id}/pause")
    public CampaignDetail pause(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireApprover(caller);
        execution.pause(caller, campaigns.require(caller, id));
        return campaigns.detail(caller, id);
    }

    @PostMapping("/campaigns/{id}/resume")
    public CampaignDetail resume(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        MarketingAccess.requireApprover(caller);
        execution.resume(caller, campaigns.require(caller, id));
        return campaigns.detail(caller, id);
    }

    @PostMapping("/campaigns/{id}/cancel")
    public CampaignDetail cancel(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                 @Valid @RequestBody(required = false) CancelRequest request) {
        MarketingAccess.requireApprover(caller);
        execution.cancel(caller, campaigns.require(caller, id), request == null ? null : request.reason());
        return campaigns.detail(caller, id);
    }

    /** Get Campaign Analytics. */
    @GetMapping("/campaigns/{id}/analytics")
    public AnalyticsResponse campaignAnalytics(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                               @RequestParam(defaultValue = "30d") String range,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MarketingAccess.requireView(caller);
        return analytics.forCampaign(campaigns.require(caller, id), range, from, to);
    }

    /**
     * Delivery events from the delivery provider (webhook relay) or tracking.
     * Approver-only: these numbers drive A/B decisions, so not every marketing
     * user may write them.
     */
    @PostMapping("/campaigns/{id}/events")
    public Map<String, Integer> events(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                       @Valid @RequestBody DeliveryEventsRequest request) {
        MarketingAccess.requireApprover(caller);
        return Map.of("applied", execution.recordEvents(campaigns.require(caller, id), request.events()));
    }
}
