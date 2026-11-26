package com.techcrm.crm.marketing.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Request and response bodies for /api/marketing. */
public final class MarketingDtos {

    private MarketingDtos() {
    }

    /* ================================================================
     * Campaign inputs (the form)
     * ================================================================ */

    /** Section C. Descriptive targeting the AI weighs; hard requirements belong in criteria. */
    public record Audience(
            @Size(max = 20) List<@Size(max = 80) String> industries,
            @Size(max = 60) String companySize,
            @Size(max = 20) List<@Size(max = 80) String> locations,
            @Size(max = 10) List<@Size(max = 40) String> customerTypes,
            @Size(max = 20) List<@Size(max = 80) String> leadSources,
            @Size(max = 200) String productInterest,
            @PositiveOrZero BigDecimal minDealValue,
            @PositiveOrZero BigDecimal maxDealValue,
            @Size(max = 40) String purchaseTimeline,
            @Size(max = 200) String customerSegment,
            /* ANY | EXISTING_CUSTOMERS | NEW_LEADS */
            @Pattern(regexp = "ANY|EXISTING_CUSTOMERS|NEW_LEADS") String customerStatus,
            @Size(max = 1000) String otherConditions
    ) {
        public static Audience empty() {
            return new Audience(List.of(), null, List.of(), List.of(), List.of(), null, null, null, null, null, "ANY", null);
        }
    }

    /**
     * Section D, email configuration. channels is kept for stored campaigns and
     * API compatibility; email is the only channel and the server ignores the
     * value. personalization=false asks the AI not to use merge fields (any the
     * Marketing Head adds still resolve, with fallbacks).
     */
    public record ContentConfig(
            @Size(max = 5) List<@Pattern(regexp = "EMAIL|POSTER|SMS|NOTIFICATION|SOCIAL") String> channels,
            @Size(max = 10) List<@Size(max = 60) String> contentTypes,
            @Size(max = 60) String tone,
            @Size(max = 40) String language,
            @Size(max = 60) String writingStyle,
            @Size(max = 120) String cta,
            @Size(max = 1500) String additionalInstructions,
            @Size(max = 120) String senderName,
            @Email @Size(max = 254) String senderEmail,
            @Email @Size(max = 254) String replyToEmail,
            Boolean personalization
    ) {
        public static ContentConfig defaults() {
            return new ContentConfig(List.of("EMAIL"), List.of(), "Professional", "English", "Concise", null, null,
                    null, null, null, true);
        }

        public boolean personalizationEnabled() {
            return personalization == null || personalization;
        }
    }

    /** Section E, one rule. */
    public record Criterion(
            @NotBlank @Size(max = 40) String field,
            @NotBlank @Size(max = 12) String operator,
            @NotBlank @Size(max = 300) String value
    ) {
    }

    public record CampaignRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank String campaignType,
            @Size(max = 300) String objective,
            @Size(max = 5000) String description,
            LocalDate startDate,
            LocalDate endDate,
            @Size(max = 200) String productName,
            @Size(max = 5000) String productDescription,
            @Size(max = 3000) String mainBenefits,
            @Size(max = 2000) String offer,
            @Size(max = 120) String discount,
            @Size(max = 2000) String pricingInfo,
            @Size(max = 3000) String sellingPoints,
            @Size(max = 200) String callToAction,
            @Size(max = 500) @Pattern(regexp = "https?://\\S+", message = "must start with http:// or https://") String landingPageUrl,
            @Valid Audience audience,
            @Valid ContentConfig contentConfig,
            @Size(max = 20) List<@Valid Criterion> criteria,
            @Pattern(regexp = "ALL|ANY") String ruleMatch
    ) {
    }

    public record StrategyRequest(
            @Size(max = 60) String sendWindow,
            @Size(max = 60) String timezone,
            @Size(max = 1000) String notes
    ) {
    }

    /* ================================================================
     * Campaign outputs
     * ================================================================ */

    public record CampaignSummary(
            String id,
            String name,
            String campaignType,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            List<String> channels,
            long targetLeads,
            long approvedLeads,
            long sent,
            long delivered,
            long opened,
            long clicked,
            long converted,
            long bounced,
            long unsubscribed,
            String leadPipelineStatus,
            String contentPipelineStatus,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record PipelineState(
            String leadStatus,
            int leadDone,
            int leadTotal,
            String leadError,
            String contentStatus,
            String contentError
    ) {
    }

    /** What still stands between the campaign and launch. */
    public record Readiness(
            long evaluatedLeads,
            long eligibleLeads,
            long approvedLeads,
            long eligiblePending,
            List<String> requiredContent,
            List<String> approvedContent,
            boolean leadsReady,
            boolean contentReady,
            boolean canLaunch,
            List<String> blockers
    ) {
    }

    public record CampaignDetail(
            String id,
            String name,
            String campaignType,
            String objective,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String productName,
            String productDescription,
            String mainBenefits,
            String offer,
            String discount,
            String pricingInfo,
            String sellingPoints,
            String callToAction,
            String landingPageUrl,
            Audience audience,
            ContentConfig contentConfig,
            List<Criterion> criteria,
            String ruleMatch,
            StrategyRequest strategy,
            OffsetDateTime scheduledAt,
            PipelineState pipeline,
            Readiness readiness,
            AbTestResponse abTest,
            List<ApprovalItem> approvals,
            String createdBy,
            OffsetDateTime launchedAt,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            boolean viewerIsApprover
    ) {
    }

    /* ================================================================
     * Leads
     * ================================================================ */

    public record RuleResult(String field, String operator, String expected, String actual, String outcome, String label) {

        public RuleResult withExpected(String value) {
            return new RuleResult(field, operator, value, actual, outcome, label);
        }
    }

    public record CampaignLeadResponse(
            String id,
            String leadId,
            String leadName,
            String company,
            String industry,
            String companySize,
            String location,
            String customerType,
            String leadSource,
            String purchaseTimeline,
            BigDecimal estimatedDealValue,
            String productInterest,
            String interestLevel,
            Integer aiScore,
            String aiEligibility,
            Double aiConfidence,
            List<String> positiveFactors,
            List<String> negativeFactors,
            String aiReason,
            List<RuleResult> ruleResults,
            boolean ruleGuardApplied,
            String evaluationSource,
            String modelName,
            OffsetDateTime evaluatedAt,
            String approvalStatus,
            String overrideEligibility,
            String effectiveEligibility,
            String reviewNote,
            String reviewedBy,
            OffsetDateTime reviewedAt
    ) {
    }

    public record LeadDecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVE|REJECT|REMOVE|RESTORE") String action,
            @Size(max = 500) String note
    ) {
    }

    public record BulkLeadDecisionRequest(
            @NotEmpty @Size(max = 1000) List<@NotBlank String> ids,
            @NotBlank @Pattern(regexp = "APPROVE|REJECT|REMOVE|RESTORE") String action,
            @Size(max = 500) String note
    ) {
    }

    public record LeadOverrideRequest(
            @NotBlank @Pattern(regexp = "ELIGIBLE|NOT_ELIGIBLE") String eligibility,
            @NotBlank @Size(min = 3, max = 500) String note
    ) {
    }

    public record BulkResult(int updated, int skipped) {
    }

    /* ================================================================
     * Content
     * ================================================================ */

    public record ContentResponse(
            String id,
            String channel,
            String variant,
            Map<String, String> fields,
            String status,
            String provenance,
            String modelName,
            int generationCount,
            List<String> validationWarnings,
            String reviewNote,
            String reviewedBy,
            OffsetDateTime reviewedAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record ContentUpdateRequest(
            @NotNull @Size(max = 10) Map<@Size(max = 40) String, @Size(max = 5000) String> fields,
            /* true: save as draft (not yet submitted for review) */
            boolean draft
    ) {
    }

    public record ContentDecisionRequest(@Size(max = 500) String note) {
    }

    public record GenerateContentRequest(
            @Size(max = 1) List<@Pattern(regexp = "EMAIL") String> channels,
            @Pattern(regexp = "A|B") String variant,
            @Size(max = 500) String instructions
    ) {
    }

    /* ================================================================
     * A/B testing
     * ================================================================ */

    public record AbTestRequest(
            boolean enabled,
            @NotBlank @Pattern(regexp = "EMAIL_SUBJECT|PREVIEW_TEXT|EMAIL_BODY|CTA|EMAIL_STRUCTURE")
            String testElement,
            @Min(1) @Max(99) int splitAPct,
            @Min(10) @Max(100) int testAudiencePct,
            @Min(1) @Max(720) int durationHours,
            @NotBlank @Pattern(regexp = "OPEN_RATE|CLICK_RATE|CONVERSION_RATE|RESPONSE_RATE") String primaryMetric,
            @NotBlank @Pattern(regexp = "MANUAL|HIGHEST_PRIMARY_METRIC") String decisionRule,
            @Min(1) @Max(1_000_000) int minSamplePerVariant
    ) {
    }

    public record AbTestResponse(
            boolean enabled,
            String testElement,
            String testedChannel,
            int splitAPct,
            int testAudiencePct,
            int durationHours,
            String primaryMetric,
            String decisionRule,
            int minSamplePerVariant,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime endsAt,
            String winnerVariant,
            String decidedBy,
            OffsetDateTime decidedAt,
            String decisionNote
    ) {
    }

    public record WinnerRequest(
            @NotBlank @Pattern(regexp = "A|B") String variant,
            @Size(max = 500) String note
    ) {
    }

    /* ================================================================
     * Execution
     * ================================================================ */

    public record ScheduleRequest(@NotNull OffsetDateTime scheduledAt) {
    }

    public record CancelRequest(@Size(max = 500) String reason) {
    }

    /**
     * One delivery event from the email provider (webhook) or tracking.
     * Identify the message by messageId (ours), providerMessageId (the
     * Message-ID we sent) or leadId. bounceType: HARD | SOFT (BOUNCED only).
     */
    public record DeliveryEvent(
            String messageId,
            @Size(max = 255) String providerMessageId,
            String leadId,
            @NotBlank @Pattern(regexp = "DELIVERED|OPENED|CLICKED|RESPONDED|CONVERTED|BOUNCED|COMPLAINED|UNSUBSCRIBED") String type,
            @Pattern(regexp = "HARD|SOFT") String bounceType,
            @Size(max = 300) String reason,
            OffsetDateTime occurredAt
    ) {
    }

    public record DeliveryEventsRequest(@NotEmpty @Size(max = 1000) List<@Valid DeliveryEvent> events) {
    }

    /* ================================================================
     * Approvals and activity
     * ================================================================ */

    public record ApprovalItem(
            String id,
            String campaignId,
            String campaignName,
            String campaignType,
            String kind,
            String status,
            String submittedBy,
            OffsetDateTime submittedAt,
            String decidedBy,
            OffsetDateTime decidedAt,
            String note,
            long pendingCount
    ) {
    }

    public record ActivityItem(
            String id,
            String campaignId,
            String campaignName,
            String actorType,
            String actorName,
            String action,
            String summary,
            Map<String, Object> detail,
            OffsetDateTime occurredAt
    ) {
    }

    /* ================================================================
     * Analytics
     * ================================================================ */

    /**
     * Email funnel. Definitions (see CampaignAnalyticsService):
     * sent = accepted by the email provider; delivered = confirmed by a
     * provider delivery event (never assumed from acceptance); opened is an
     * estimate (image loading, privacy proxies, scanners); failed = not
     * accepted; suppressed = never attempted (unsubscribed, bounced, no or
     * duplicate address).
     */
    public record Funnel(
            long sent,
            long delivered,
            long bounced,
            long opened,
            long clicked,
            long responded,
            long converted,
            long unsubscribed,
            long failed,
            long suppressed,
            /* Rates in percent, one decimal; null when the denominator is zero or the provider reports nothing. */
            Double deliveryRate,
            Double bounceRate,
            Double openRate,
            Double clickRate,
            Double responseRate,
            Double conversionRate,
            Double unsubscribeRate
    ) {
    }

    public record SeriesPoint(LocalDate date, long sent, long delivered, long opened, long clicked, long converted,
                              long bounced, long unsubscribed) {
    }

    public record VariantPerformance(String variant, Funnel funnel) {
    }

    public record AnalyticsResponse(
            LocalDate from,
            LocalDate to,
            Funnel funnel,
            List<SeriesPoint> series,
            List<VariantPerformance> variants,
            long queued,
            long held,
            long sending
    ) {
    }

    public record DashboardResponse(
            long totalCampaigns,
            long campaignsCreatedLast30d,
            long activeCampaigns,
            long scheduledCampaigns,
            long leadsReached,
            long leadsReachedLast30d,
            long leadsReachedPrev30d,
            long approvalsWaiting,
            long leadDecisionsWaiting,
            Funnel funnel,
            List<ActivityItem> recentActivity
    ) {
    }

    /* ================================================================
     * Metadata for the form
     * ================================================================ */

    public record FieldMeta(String key, String label, String kind, List<String> operators, List<String> options, String hint) {
    }

    public record AiStatus(String model, boolean selfHosted, boolean externalProviderAllowed, boolean fallbackEnabled,
                           Boolean lastCallSucceeded, OffsetDateTime lastCallAt) {
    }

    /**
     * Whether campaign email can actually be sent. configured=false is the
     * "configuration required" state: launching is blocked and missing lists
     * what to set. Credentials are never included.
     */
    public record EmailDeliveryStatus(
            boolean configured,
            String provider,
            List<String> missing,
            boolean trackingEnabled,
            boolean webhookEnabled,
            List<String> verifiedSenders
    ) {
    }

    public record MetaResponse(
            List<String> campaignTypes,
            List<String> channels,
            List<FieldMeta> ruleFields,
            List<String> purchaseTimelines,
            List<String> customerTypes,
            AiStatus ai,
            EmailDeliveryStatus email,
            boolean viewerIsApprover
    ) {
    }

    /* ================================================================
     * Email preview
     * ================================================================ */

    /** The email exactly as a recipient would get it. sampleLead=true when filled with placeholder values. */
    public record EmailPreview(
            String from,
            String replyTo,
            String to,
            String subject,
            String preheader,
            String html,
            String text,
            boolean sampleLead,
            List<String> notes
    ) {
    }
}
