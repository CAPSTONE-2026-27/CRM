package com.techcrm.crm.marketing.campaign;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.marketing.abtest.AbTest;
import com.techcrm.crm.marketing.abtest.AbTestRepository;
import com.techcrm.crm.marketing.abtest.AbTestService;
import com.techcrm.crm.marketing.activity.ActivityService;
import com.techcrm.crm.marketing.api.MarketingDtos.*;
import com.techcrm.crm.marketing.approval.ApprovalService;
import com.techcrm.crm.marketing.approval.MarketingApproval;
import com.techcrm.crm.marketing.approval.MarketingApprovalRepository;
import com.techcrm.crm.marketing.common.MarketingAccess;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.common.UserNames;
import com.techcrm.crm.marketing.email.EmailSettings;
import com.techcrm.crm.marketing.execution.CampaignMessageRepository;
import com.techcrm.crm.marketing.leads.CampaignLeadRepository;
import com.techcrm.crm.marketing.leads.rules.RuleEngine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;

/** Campaign Service: the campaign record itself, from the form to the detail page. */
@Service
public class MarketingCampaignService {

    private final MarketingCampaignRepository campaigns;
    private final CampaignCriterionRepository criteriaRepo;
    private final CampaignLeadRepository campaignLeads;
    private final CampaignMessageRepository messages;
    private final AbTestRepository abTests;
    private final MarketingApprovalRepository approvalRepo;
    private final ApprovalService approvals;
    private final ActivityService activity;
    private final UserNames userNames;

    public MarketingCampaignService(MarketingCampaignRepository campaigns, CampaignCriterionRepository criteriaRepo,
                                    CampaignLeadRepository campaignLeads, CampaignMessageRepository messages,
                                    AbTestRepository abTests, MarketingApprovalRepository approvalRepo,
                                    ApprovalService approvals, ActivityService activity, UserNames userNames) {
        this.campaigns = campaigns;
        this.criteriaRepo = criteriaRepo;
        this.campaignLeads = campaignLeads;
        this.messages = messages;
        this.abTests = abTests;
        this.approvalRepo = approvalRepo;
        this.approvals = approvals;
        this.activity = activity;
        this.userNames = userNames;
    }

    /* ================================================================
     * Queries
     * ================================================================ */

    /** Tenant-scoped lookup: a campaign in another organization is simply not found. */
    @Transactional(readOnly = true)
    public MarketingCampaign require(AuthenticatedUser caller, Long id) {
        return campaigns.findByIdAndOrganizationId(id, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
    }

    @Transactional(readOnly = true)
    public List<CampaignSummary> list(AuthenticatedUser caller) {
        List<MarketingCampaign> all = campaigns.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId());
        if (all.isEmpty()) return List.of();
        List<Long> ids = all.stream().map(MarketingCampaign::getId).toList();

        Map<Long, long[]> leadCounts = new HashMap<>();
        for (Object[] row : campaignLeads.countsByCampaign(ids)) {
            leadCounts.put((Long) row[0], new long[]{num(row[1]), num(row[2])});
        }
        Map<Long, long[]> msgCounts = new HashMap<>();
        for (Object[] row : messages.countsByCampaign(ids)) {
            msgCounts.put((Long) row[0], new long[]{num(row[1]), num(row[2]), num(row[3]), num(row[4]), num(row[5]),
                    num(row[6]), num(row[7])});
        }
        List<CampaignSummary> out = new ArrayList<>();
        for (MarketingCampaign c : all) {
            long[] l = leadCounts.getOrDefault(c.getId(), new long[2]);
            // [sent, delivered, opened, clicked, converted, bounced, unsubscribed]
            long[] m = msgCounts.getOrDefault(c.getId(), new long[7]);
            out.add(new CampaignSummary(String.valueOf(c.getId()), c.getName(), c.getCampaignType().name(),
                    c.getStatus().name(), c.getStartDate(), c.getEndDate(),
                    CampaignChannels.configured(c).stream().map(Enum::name).toList(),
                    l[0], l[1], m[0], m[1], m[2], m[3], m[4], m[5], m[6],
                    c.getLeadPipelineStatus().name(), c.getContentPipelineStatus().name(),
                    c.getCreatedAt(), c.getUpdatedAt()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public CampaignDetail detail(AuthenticatedUser caller, Long id) {
        return toDetail(caller, require(caller, id));
    }

    /* ================================================================
     * Commands
     * ================================================================ */

    @Transactional
    public MarketingCampaign create(AuthenticatedUser caller, CampaignRequest req) {
        MarketingCampaign c = new MarketingCampaign();
        c.setOrganizationId(caller.organizationId());
        c.setCreatedBy(caller.userId());
        apply(c, req);
        campaigns.save(c);
        replaceCriteria(c.getId(), req.criteria());
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "CAMPAIGN_CREATED",
                "Created campaign \"" + c.getName() + "\"", Map.of("type", c.getCampaignType().name()));
        return c;
    }

    @Transactional
    public MarketingCampaign update(AuthenticatedUser caller, Long id, CampaignRequest req) {
        MarketingCampaign c = require(caller, id);
        if (!CampaignStatus.EDITABLE.contains(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A campaign can't be edited once it is " + pretty(c.getStatus()) + ". Duplicate it to make changes.");
        }
        if (c.getLeadPipelineStatus() == PipelineStatus.RUNNING || c.getContentPipelineStatus() == PipelineStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for AI processing to finish before editing the campaign");
        }
        apply(c, req);
        campaigns.save(c);
        replaceCriteria(c.getId(), req.criteria());
        boolean processed = c.getStatus() != CampaignStatus.DRAFT;
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "CAMPAIGN_UPDATED",
                processed ? "Edited campaign inputs; AI results were produced from the previous inputs and can be re-run"
                        : "Edited campaign draft", null);
        approvals.recompute(c.getId(), caller.userId());
        return c;
    }

    @Transactional
    public void updateStrategy(AuthenticatedUser caller, Long id, StrategyRequest req) {
        MarketingCampaign c = require(caller, id);
        if (c.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This campaign is " + pretty(c.getStatus()));
        }
        c.setStrategyJson(MarketingJson.write(req));
        campaigns.save(c);
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "STRATEGY_UPDATED", "Updated sending strategy", req);
    }

    /**
     * Campaign data validation, the first step after "Create Campaign": the
     * inputs the AI pipelines depend on must be present before any model runs.
     */
    public void validateForSubmission(MarketingCampaign c) {
        validateForSubmission(c.getObjective(), c.getProductName(), c.getDescription(), c.getStartDate(), c.getEndDate());
    }

    public static void validateForSubmission(String objective, String productName, String description,
                                             LocalDate startDate, LocalDate endDate) {
        List<String> problems = new ArrayList<>();
        if (blank(objective)) problems.add("campaign objective");
        if (blank(productName) && blank(description)) problems.add("a product name or campaign description");
        if (startDate == null) problems.add("start date");
        if (endDate == null) problems.add("end date");
        if (!problems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Before AI processing, add: " + String.join(", ", problems));
        }
        if (endDate.isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is in the past");
        }
    }

    @Transactional
    public MarketingCampaign duplicate(AuthenticatedUser caller, Long id) {
        MarketingCampaign src = require(caller, id);
        MarketingCampaign c = new MarketingCampaign();
        c.setOrganizationId(src.getOrganizationId());
        c.setCreatedBy(caller.userId());
        c.setName(clip("Copy of " + src.getName(), 160));
        c.setCampaignType(src.getCampaignType());
        c.setObjective(src.getObjective());
        c.setDescription(src.getDescription());
        c.setStartDate(src.getStartDate());
        c.setEndDate(src.getEndDate());
        c.setProductName(src.getProductName());
        c.setProductDescription(src.getProductDescription());
        c.setMainBenefits(src.getMainBenefits());
        c.setOffer(src.getOffer());
        c.setDiscount(src.getDiscount());
        c.setPricingInfo(src.getPricingInfo());
        c.setSellingPoints(src.getSellingPoints());
        c.setCallToAction(src.getCallToAction());
        c.setLandingPageUrl(src.getLandingPageUrl());
        c.setAudienceJson(src.getAudienceJson());
        c.setContentConfigJson(src.getContentConfigJson());
        c.setRuleMatch(src.getRuleMatch());
        c.setStrategyJson(src.getStrategyJson());
        campaigns.save(c);
        List<Criterion> criteria = criteriaRepo.findByCampaignIdOrderByPosition(src.getId()).stream()
                .map(k -> new Criterion(k.getField(), k.getOperator(), k.getValue())).toList();
        replaceCriteria(c.getId(), criteria);
        abTests.findByCampaignId(src.getId()).ifPresent(t -> {
            AbTest copy = new AbTest();
            copy.setCampaignId(c.getId());
            copy.setEnabled(t.getEnabled());
            copy.setTestElement(t.getTestElement());
            copy.setSplitAPct(t.getSplitAPct());
            copy.setTestAudiencePct(t.getTestAudiencePct());
            copy.setDurationHours(t.getDurationHours());
            copy.setPrimaryMetric(t.getPrimaryMetric());
            copy.setDecisionRule(t.getDecisionRule());
            copy.setMinSamplePerVariant(t.getMinSamplePerVariant());
            abTests.save(copy);
        });
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "CAMPAIGN_DUPLICATED",
                "Duplicated from \"" + src.getName() + "\" (inputs only; AI results and approvals are not copied)",
                Map.of("sourceCampaignId", String.valueOf(src.getId())));
        activity.human(src.getOrganizationId(), src.getId(), caller.userId(), "CAMPAIGN_DUPLICATED",
                "Duplicated as \"" + c.getName() + "\"", Map.of("newCampaignId", String.valueOf(c.getId())));
        return c;
    }

    @Transactional
    public void delete(AuthenticatedUser caller, Long id) {
        MarketingCampaign c = require(caller, id);
        if (c.getStatus() == CampaignStatus.ACTIVE || c.getStatus() == CampaignStatus.PAUSED
                || c.getStatus() == CampaignStatus.SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancel the campaign before deleting it");
        }
        if (c.getLeadPipelineStatus() == PipelineStatus.RUNNING || c.getContentPipelineStatus() == PipelineStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for AI processing to finish before deleting");
        }
        // The per-campaign trail goes with the campaign (ON DELETE CASCADE); the
        // organization audit log keeps the record that it existed and who removed it.
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "CAMPAIGN_DELETED",
                "Deleted campaign \"" + c.getName() + "\" (" + pretty(c.getStatus()) + ")", null);
        campaigns.delete(c);
    }

    /* ================================================================
     * Mapping
     * ================================================================ */

    private void apply(MarketingCampaign c, CampaignRequest req) {
        CampaignType type;
        try {
            type = CampaignType.valueOf(req.campaignType().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown campaign type: " + req.campaignType());
        }
        if (req.startDate() != null && req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date must be on or after the start date");
        }
        Audience audience = req.audience() == null ? Audience.empty() : req.audience();
        if (audience.minDealValue() != null && audience.maxDealValue() != null
                && audience.maxDealValue().compareTo(audience.minDealValue()) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maximum deal value is below the minimum");
        }
        try {
            RuleEngine.validate(req.criteria());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        // Email is the only channel, whatever an older client sends.
        ContentConfig in = req.contentConfig() == null ? ContentConfig.defaults() : req.contentConfig();
        ContentConfig cfg = new ContentConfig(List.of("EMAIL"), in.contentTypes(), in.tone(), in.language(),
                in.writingStyle(), trim(in.cta()), trim(in.additionalInstructions()), trim(in.senderName()),
                EmailSettings.normalise(in.senderEmail()), EmailSettings.normalise(in.replyToEmail()),
                in.personalizationEnabled());

        c.setName(req.name().trim());
        c.setCampaignType(type);
        c.setObjective(trim(req.objective()));
        c.setDescription(trim(req.description()));
        c.setStartDate(req.startDate());
        c.setEndDate(req.endDate());
        c.setProductName(trim(req.productName()));
        c.setProductDescription(trim(req.productDescription()));
        c.setMainBenefits(trim(req.mainBenefits()));
        c.setOffer(trim(req.offer()));
        c.setDiscount(trim(req.discount()));
        c.setPricingInfo(trim(req.pricingInfo()));
        c.setSellingPoints(trim(req.sellingPoints()));
        c.setCallToAction(trim(req.callToAction()));
        c.setLandingPageUrl(trim(req.landingPageUrl()));
        c.setAudienceJson(MarketingJson.write(audience));
        c.setContentConfigJson(MarketingJson.write(cfg));
        c.setRuleMatch(req.ruleMatch() == null ? "ALL" : req.ruleMatch());
    }

    private void replaceCriteria(Long campaignId, List<Criterion> criteria) {
        criteriaRepo.deleteByCampaignId(campaignId);
        if (criteria == null) return;
        int position = 0;
        for (Criterion k : criteria) {
            CampaignCriterion row = new CampaignCriterion();
            row.setCampaignId(campaignId);
            row.setPosition(position++);
            row.setField(k.field().trim().toUpperCase());
            row.setOperator(k.operator().trim().toUpperCase());
            row.setValue(k.value().trim());
            criteriaRepo.save(row);
        }
    }

    public CampaignDetail toDetail(AuthenticatedUser caller, MarketingCampaign c) {
        List<Criterion> criteria = criteriaRepo.findByCampaignIdOrderByPosition(c.getId()).stream()
                .map(k -> new Criterion(k.getField(), k.getOperator(), k.getValue())).toList();
        List<MarketingApproval> history = approvalRepo.findByCampaignIdOrderBySubmittedAtDesc(c.getId());
        Optional<AbTest> ab = abTests.findByCampaignId(c.getId());

        Set<Long> userIds = new HashSet<>();
        userIds.add(c.getCreatedBy());
        history.forEach(a -> {
            userIds.add(a.getSubmittedBy());
            userIds.add(a.getDecidedBy());
        });
        ab.ifPresent(t -> userIds.add(t.getDecidedBy()));
        Map<Long, String> names = userNames.resolve(c.getOrganizationId(), userIds);

        Readiness readiness = approvals.readiness(c);
        List<ApprovalItem> items = history.stream().map(a -> new ApprovalItem(String.valueOf(a.getId()),
                String.valueOf(c.getId()), c.getName(), c.getCampaignType().name(), a.getKind().name(), a.getStatus().name(),
                name(names, a.getSubmittedBy()), a.getSubmittedAt(), name(names, a.getDecidedBy()), a.getDecidedAt(),
                a.getNote(), a.getKind() == MarketingApproval.Kind.LEADS ? readiness.eligiblePending() : 0)).toList();

        return new CampaignDetail(
                String.valueOf(c.getId()), c.getName(), c.getCampaignType().name(), c.getObjective(), c.getDescription(),
                c.getStartDate(), c.getEndDate(), c.getStatus().name(),
                c.getProductName(), c.getProductDescription(), c.getMainBenefits(), c.getOffer(), c.getDiscount(),
                c.getPricingInfo(), c.getSellingPoints(), c.getCallToAction(), c.getLandingPageUrl(),
                MarketingJson.read(c.getAudienceJson(), Audience.class, Audience.empty()),
                MarketingJson.read(c.getContentConfigJson(), ContentConfig.class, ContentConfig.defaults()),
                criteria, c.getRuleMatch(),
                MarketingJson.read(c.getStrategyJson(), StrategyRequest.class, new StrategyRequest(null, null, null)),
                c.getScheduledAt(),
                new PipelineState(c.getLeadPipelineStatus().name(), c.getLeadPipelineDone(), c.getLeadPipelineTotal(),
                        c.getLeadPipelineError(), c.getContentPipelineStatus().name(), c.getContentPipelineError()),
                readiness,
                ab.map(t -> AbTestService.toResponse(t, CampaignChannels.configured(c), names)).orElse(null),
                items,
                name(names, c.getCreatedBy()),
                c.getLaunchedAt(), c.getCompletedAt(), c.getCreatedAt(), c.getUpdatedAt(),
                MarketingAccess.isApprover(caller));
    }

    private static String name(Map<Long, String> names, Long id) {
        if (id == null) return null;
        return names.getOrDefault(id, "User " + id);
    }

    static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static String pretty(CampaignStatus s) {
        return s.name().replace('_', ' ').toLowerCase();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
