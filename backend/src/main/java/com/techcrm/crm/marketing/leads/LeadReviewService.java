package com.techcrm.crm.marketing.leads;

import com.fasterxml.jackson.core.type.TypeReference;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.marketing.activity.ActivityService;
import com.techcrm.crm.marketing.api.MarketingDtos.BulkResult;
import com.techcrm.crm.marketing.api.MarketingDtos.CampaignLeadResponse;
import com.techcrm.crm.marketing.api.MarketingDtos.RuleResult;
import com.techcrm.crm.marketing.approval.ApprovalService;
import com.techcrm.crm.marketing.campaign.CampaignStatus;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.campaign.PipelineStatus;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.common.UserNames;
import com.techcrm.crm.marketing.leads.CampaignLead.ApprovalStatus;
import com.techcrm.crm.marketing.leads.CampaignLead.Eligibility;
import com.techcrm.crm.marketing.leads.rules.LeadFacts;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lead Scoring Service, review side: the Marketing Head's decisions on the
 * AI's per-lead evaluations. The AI's own fields are never modified here;
 * overrides and decisions are stored alongside them so both stay visible.
 */
@Service
public class LeadReviewService {

    private final CampaignLeadRepository campaignLeads;
    private final LeadRepository leads;
    private final ApprovalService approvals;
    private final ActivityService activity;
    private final UserNames userNames;

    public LeadReviewService(CampaignLeadRepository campaignLeads, LeadRepository leads, ApprovalService approvals,
                             ActivityService activity, UserNames userNames) {
        this.campaignLeads = campaignLeads;
        this.leads = leads;
        this.approvals = approvals;
        this.activity = activity;
        this.userNames = userNames;
    }

    @Transactional(readOnly = true)
    public List<CampaignLeadResponse> list(MarketingCampaign c) {
        List<CampaignLead> rows = campaignLeads.findByCampaignIdOrderByAiScoreDesc(c.getId());
        Map<Long, Lead> byId = leads.findAllById(rows.stream().map(CampaignLead::getLeadId).toList()).stream()
                .filter(l -> Objects.equals(l.getOrganizationId(), c.getOrganizationId()))
                .collect(Collectors.toMap(Lead::getId, Function.identity()));
        Map<Long, String> names = userNames.resolve(c.getOrganizationId(),
                rows.stream().map(CampaignLead::getReviewedBy).toList());
        return rows.stream().filter(r -> byId.containsKey(r.getLeadId()))
                .map(r -> toResponse(r, byId.get(r.getLeadId()), names)).toList();
    }

    @Transactional
    public CampaignLeadResponse decide(AuthenticatedUser caller, MarketingCampaign c, Long campaignLeadId,
                                       String action, String note) {
        requireReviewable(c);
        CampaignLead row = campaignLeads.findByIdAndCampaignId(campaignLeadId, c.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found in this campaign"));
        Lead lead = requireLead(c, row.getLeadId());
        if ("APPROVE".equals(action)) requireEmailable(row);
        applyDecision(row, action, note, caller.userId());
        campaignLeads.save(row);
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "LEAD_" + past(action),
                verb(action) + " " + lead.getFullName() + " ("
                        + (row.getAiScore() == null ? "" : "AI score " + row.getAiScore() + ", ")
                        + pretty(row.getAiEligibility()) + ")" + (note != null && !note.isBlank() ? ": " + note : ""),
                detail(row, action, note));
        approvals.recompute(c.getId(), caller.userId());
        return toResponse(row, lead, userNames.resolve(c.getOrganizationId(), List.of(caller.userId())));
    }

    @Transactional
    public BulkResult decideMany(AuthenticatedUser caller, MarketingCampaign c, List<String> rawIds, String action, String note) {
        requireReviewable(c);
        List<Long> ids = new ArrayList<>();
        for (String raw : rawIds) {
            try {
                ids.add(Long.parseLong(raw));
            } catch (NumberFormatException ignored) {
                // an unparseable id simply doesn't match anything
            }
        }
        if (ids.isEmpty()) return new BulkResult(0, rawIds.size());
        List<CampaignLead> rows = new ArrayList<>(campaignLeads.findByCampaignIdAndIdIn(c.getId(), ids));
        // Leads that can't be emailed are never approved, in bulk or otherwise.
        if ("APPROVE".equals(action)) rows.removeIf(r -> r.getEvaluationSource() == CampaignLead.Source.EMAIL_CHECK);
        for (CampaignLead row : rows) {
            applyDecision(row, action, note, caller.userId());
        }
        campaignLeads.saveAll(rows);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", action);
        detail.put("count", rows.size());
        detail.put("campaignLeadIds", rows.stream().map(r -> String.valueOf(r.getId())).toList());
        if (note != null) detail.put("note", note);
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "LEADS_BULK_" + past(action),
                verb(action) + " " + rows.size() + " leads in bulk", detail);
        approvals.recompute(c.getId(), caller.userId());
        return new BulkResult(rows.size(), rawIds.size() - rows.size());
    }

    /**
     * Replaces the AI's eligibility with the Marketing Head's own. The AI's
     * answer is kept untouched beside it, and a note is required so the
     * reason for disagreeing with the model is on record.
     */
    @Transactional
    public CampaignLeadResponse override(AuthenticatedUser caller, MarketingCampaign c, Long campaignLeadId,
                                         String eligibility, String note) {
        requireReviewable(c);
        CampaignLead row = campaignLeads.findByIdAndCampaignId(campaignLeadId, c.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found in this campaign"));
        Lead lead = requireLead(c, row.getLeadId());
        Eligibility target = Eligibility.valueOf(eligibility);
        if (target == Eligibility.ELIGIBLE) requireEmailable(row);
        Eligibility previous = row.effectiveEligibility();
        row.setOverrideEligibility(target == row.getAiEligibility() ? null : target);
        row.setReviewNote(note);
        row.setReviewedBy(caller.userId());
        row.setReviewedAt(OffsetDateTime.now());
        // Overriding to eligible puts the lead in front of the reviewer; to not
        // eligible, it drops out of the send list.
        row.setApprovalStatus(target == Eligibility.ELIGIBLE ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
        campaignLeads.save(row);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("aiEligibility", row.getAiEligibility() == null ? null : row.getAiEligibility().name());
        detail.put("aiScore", row.getAiScore());
        detail.put("from", previous == null ? null : previous.name());
        detail.put("to", target.name());
        detail.put("note", note);
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "LEAD_OVERRIDDEN",
                "Overrode the AI for " + lead.getFullName() + ": " + pretty(row.getAiEligibility()) + " → "
                        + pretty(target) + " (" + note + ")", detail);
        approvals.recompute(c.getId(), caller.userId());
        return toResponse(row, lead, userNames.resolve(c.getOrganizationId(), List.of(caller.userId())));
    }

    private static void applyDecision(CampaignLead row, String action, String note, Long userId) {
        row.setApprovalStatus(switch (action) {
            case "APPROVE" -> ApprovalStatus.APPROVED;
            case "REJECT" -> ApprovalStatus.REJECTED;
            case "REMOVE" -> ApprovalStatus.REMOVED;
            case "RESTORE" -> ApprovalStatus.PENDING;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action " + action);
        });
        if ("RESTORE".equals(action)) {
            row.setReviewedBy(null);
            row.setReviewedAt(null);
            row.setReviewNote(null);
        } else {
            row.setReviewedBy(userId);
            row.setReviewedAt(OffsetDateTime.now());
            if (note != null && !note.isBlank()) row.setReviewNote(note.trim());
        }
    }

    /** A mandatory requirement no reviewer can override: the campaign is email, so the lead must be emailable. */
    private static void requireEmailable(CampaignLead row) {
        if (row.getEvaluationSource() == CampaignLead.Source.EMAIL_CHECK) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This lead can't be emailed (" + row.getAiReason()
                    + ") Fix the lead's email address in the CRM, then run lead evaluation again.");
        }
    }

    private static void requireReviewable(MarketingCampaign c) {
        if (!CampaignStatus.EDITABLE.contains(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Lead decisions are locked once the campaign is " + c.getStatus().name().toLowerCase().replace('_', ' '));
        }
        if (c.getLeadPipelineStatus() == PipelineStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for lead evaluation to finish");
        }
    }

    private Lead requireLead(MarketingCampaign c, Long leadId) {
        return leads.findByIdAndOrganizationId(leadId, c.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead no longer exists"));
    }

    private static Map<String, Object> detail(CampaignLead row, String action, String note) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("campaignLeadId", String.valueOf(row.getId()));
        d.put("leadId", String.valueOf(row.getLeadId()));
        d.put("action", action);
        d.put("aiScore", row.getAiScore());
        d.put("aiEligibility", row.getAiEligibility() == null ? null : row.getAiEligibility().name());
        d.put("evaluationSource", row.getEvaluationSource() == null ? null : row.getEvaluationSource().name());
        if (note != null) d.put("note", note);
        return d;
    }

    static CampaignLeadResponse toResponse(CampaignLead r, Lead lead, Map<Long, String> names) {
        LeadFacts f = LeadFacts.from(lead);
        List<RuleResult> rules = MarketingJson.read(r.getRuleResults(), new TypeReference<List<RuleResult>>() { }, List.of());
        Eligibility effective = r.effectiveEligibility();
        return new CampaignLeadResponse(
                String.valueOf(r.getId()), String.valueOf(lead.getId()), lead.getFullName(), lead.getCompany(),
                f.industry(), f.companySize(), f.location(), f.customerType(), f.leadSource(), f.purchaseTimeline(),
                f.estimatedDealValue(), f.productInterest(), f.interestLevel(),
                r.getAiScore(), r.getAiEligibility() == null ? null : r.getAiEligibility().name(), r.getAiConfidence(),
                MarketingJson.readStringList(r.getPositiveFactors()), MarketingJson.readStringList(r.getNegativeFactors()),
                r.getAiReason(), rules, Boolean.TRUE.equals(r.getRuleGuardApplied()),
                r.getEvaluationSource() == null ? null : r.getEvaluationSource().name(), r.getModelName(), r.getEvaluatedAt(),
                r.getApprovalStatus().name(), r.getOverrideEligibility() == null ? null : r.getOverrideEligibility().name(),
                effective == null ? null : effective.name(), r.getReviewNote(),
                r.getReviewedBy() == null ? null : names.getOrDefault(r.getReviewedBy(), "User " + r.getReviewedBy()),
                r.getReviewedAt());
    }

    private static String verb(String action) {
        return switch (action) {
            case "APPROVE" -> "Approved";
            case "REJECT" -> "Rejected";
            case "REMOVE" -> "Removed";
            default -> "Restored to pending";
        };
    }

    private static String past(String action) {
        return switch (action) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            case "REMOVE" -> "REMOVED";
            default -> "RESTORED";
        };
    }

    private static String pretty(Eligibility e) {
        if (e == null) return "not evaluated";
        return e == Eligibility.ELIGIBLE ? "eligible" : "not eligible";
    }
}
