package com.techcrm.crm.marketing.approval;

import com.techcrm.crm.marketing.abtest.AbTest;
import com.techcrm.crm.marketing.abtest.AbTestRepository;
import com.techcrm.crm.marketing.activity.ActivityService;
import com.techcrm.crm.marketing.api.MarketingDtos.Readiness;
import com.techcrm.crm.marketing.approval.MarketingApproval.Kind;
import com.techcrm.crm.marketing.approval.MarketingApproval.Status;
import com.techcrm.crm.marketing.email.CampaignEmailComposer;
import com.techcrm.crm.marketing.email.EmailSettings;
import com.techcrm.crm.marketing.campaign.CampaignStatus;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.campaign.MarketingCampaignRepository;
import com.techcrm.crm.marketing.campaign.PipelineStatus;
import com.techcrm.crm.marketing.content.ContentChannel;
import com.techcrm.crm.marketing.content.ContentVariant;
import com.techcrm.crm.marketing.content.ContentVariantRepository;
import com.techcrm.crm.marketing.leads.CampaignLead.ApprovalStatus;
import com.techcrm.crm.marketing.leads.CampaignLeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the human checkpoints: whether a campaign's leads and content are
 * signed off, which approval items are open, and the review status that
 * follows from both.
 *
 * {@link #recompute} is the single place a review status is decided. Every
 * action that can change the answer (a lead decision, a content approval, a
 * pipeline finishing, an A/B change) calls it, and it derives the status from
 * the stored decisions rather than trusting the caller, so the status can
 * never say "ready to launch" while a required approval is missing.
 */
@Service
public class ApprovalService {

    private final MarketingCampaignRepository campaigns;
    private final CampaignLeadRepository campaignLeads;
    private final ContentVariantRepository contents;
    private final AbTestRepository abTests;
    private final MarketingApprovalRepository approvals;
    private final ActivityService activity;
    private final EmailSettings emailSettings;
    private final CampaignEmailComposer emailComposer;

    public ApprovalService(MarketingCampaignRepository campaigns, CampaignLeadRepository campaignLeads,
                           ContentVariantRepository contents, AbTestRepository abTests,
                           MarketingApprovalRepository approvals, ActivityService activity,
                           EmailSettings emailSettings, CampaignEmailComposer emailComposer) {
        this.campaigns = campaigns;
        this.campaignLeads = campaignLeads;
        this.contents = contents;
        this.abTests = abTests;
        this.approvals = approvals;
        this.activity = activity;
        this.emailSettings = emailSettings;
        this.emailComposer = emailComposer;
    }

    @Transactional(readOnly = true)
    public Readiness readiness(MarketingCampaign c) {
        long evaluated = campaignLeads.countByCampaignId(c.getId());
        long eligible = campaignLeads.countEligible(c.getId());
        long approved = campaignLeads.countByCampaignIdAndApprovalStatus(c.getId(), ApprovalStatus.APPROVED);
        long eligiblePending = campaignLeads.countEligiblePending(c.getId());

        List<String> blockers = new ArrayList<>();
        boolean leadsReady;
        if (c.getLeadPipelineStatus() == PipelineStatus.RUNNING) {
            blockers.add("Lead evaluation is still running");
            leadsReady = false;
        } else if (c.getLeadPipelineStatus() != PipelineStatus.COMPLETED) {
            blockers.add(c.getLeadPipelineStatus() == PipelineStatus.FAILED
                    ? "Lead evaluation failed; run it again" : "Leads have not been evaluated yet");
            leadsReady = false;
        } else {
            if (eligiblePending > 0) {
                blockers.add(eligiblePending + " eligible lead" + (eligiblePending == 1 ? " is" : "s are") + " awaiting review");
            }
            if (approved == 0) blockers.add("Approve at least one lead");
            leadsReady = eligiblePending == 0 && approved > 0;
        }

        // Email-only: the approved email, plus variant B when A/B testing is on.
        List<String> required = new ArrayList<>();
        required.add(key(ContentChannel.EMAIL, "A"));
        Optional<AbTest> ab = abTests.findByCampaignId(c.getId()).filter(AbTest::getEnabled);
        if (ab.isPresent()) required.add(key(ContentChannel.EMAIL, "B"));

        Map<String, ContentVariant> byKey = new HashMap<>();
        for (ContentVariant v : contents.findByCampaignIdOrderByChannelAscVariantAsc(c.getId())) {
            byKey.put(key(v.getChannel(), v.getVariant()), v);
        }
        List<String> approvedContent = new ArrayList<>();
        for (String k : required) {
            ContentVariant v = byKey.get(k);
            if (v != null && v.getStatus() == ContentVariant.Status.APPROVED) approvedContent.add(k);
        }
        boolean contentRunning = c.getContentPipelineStatus() == PipelineStatus.RUNNING;
        if (contentRunning) blockers.add("Content generation is still running");
        for (String k : required) {
            if (approvedContent.contains(k)) continue;
            ContentVariant v = byKey.get(k);
            blockers.add(label(k) + (v == null ? " has not been created" : " is not approved"));
        }
        boolean contentReady = !contentRunning && approvedContent.size() == required.size();

        if (c.getEndDate() != null && c.getEndDate().isBefore(LocalDate.now())) {
            blockers.add("The campaign end date has passed");
        }
        // Sending prerequisites. They don't affect the review status, only
        // whether the Marketing Head can schedule or launch.
        if (!emailSettings.configured()) {
            blockers.add("Email sending isn't configured on the server (" + String.join("; ", emailSettings.missing()) + ")");
        }
        blockers.addAll(emailComposer.senderProblems(c));

        boolean canLaunch = leadsReady && contentReady && blockers.isEmpty();
        return new Readiness(evaluated, eligible, approved, eligiblePending, required, approvedContent,
                leadsReady, contentReady, canLaunch, blockers);
    }

    /**
     * Re-derives the review status and the open approval items. A no-op outside
     * the review phase. Locks the campaign row for the duration.
     */
    @Transactional
    public void recompute(Long campaignId, Long actorUserId) {
        MarketingCampaign c = campaigns.findForUpdate(campaignId).orElse(null);
        if (c == null) return;
        if (!CampaignStatus.EDITABLE.contains(c.getStatus())) return;
        boolean neverSubmitted = c.getLeadPipelineStatus() == PipelineStatus.IDLE
                && c.getContentPipelineStatus() == PipelineStatus.IDLE;
        if (c.getStatus() == CampaignStatus.DRAFT && neverSubmitted) return;

        Readiness r = readiness(c);
        boolean running = c.getLeadPipelineStatus() == PipelineStatus.RUNNING
                || c.getContentPipelineStatus() == PipelineStatus.RUNNING;

        CampaignStatus next;
        if (running) next = CampaignStatus.AI_PROCESSING;
        else if (!r.leadsReady()) next = CampaignStatus.PENDING_LEAD_APPROVAL;
        else if (!r.contentReady()) next = CampaignStatus.PENDING_CONTENT_APPROVAL;
        else next = CampaignStatus.READY_TO_LAUNCH;

        // Approval items
        if (c.getLeadPipelineStatus() == PipelineStatus.COMPLETED) {
            if (r.leadsReady()) resolve(c, Kind.LEADS, actorUserId, r.approvedLeads() + " leads approved");
            else open(c, Kind.LEADS);
        } else {
            withdraw(c, Kind.LEADS);
        }
        boolean hasContent = !contents.findByCampaignIdOrderByChannelAscVariantAsc(c.getId()).isEmpty();
        if (hasContent && c.getContentPipelineStatus() != PipelineStatus.RUNNING) {
            if (r.contentReady()) resolve(c, Kind.CONTENT, actorUserId, r.approvedContent().size() + " content items approved");
            else open(c, Kind.CONTENT);
        } else {
            withdraw(c, Kind.CONTENT);
        }
        if (next == CampaignStatus.READY_TO_LAUNCH) open(c, Kind.CAMPAIGN);
        else withdraw(c, Kind.CAMPAIGN);

        if (next != c.getStatus()) {
            CampaignStatus previous = c.getStatus();
            c.setStatus(next);
            campaigns.save(c);
            activity.system(c.getOrganizationId(), c.getId(), "STATUS_CHANGED",
                    "Status changed from " + pretty(previous) + " to " + pretty(next), Map.of("from", previous.name(), "to", next.name()));
        }
    }

    /** Closes the final campaign approval when a Marketing Head schedules or launches. */
    @Transactional
    public void approveCampaign(MarketingCampaign c, Long actorUserId, String note) {
        MarketingApproval item = approvals.findFirstByCampaignIdAndKindAndStatus(c.getId(), Kind.CAMPAIGN, Status.PENDING)
                .orElseGet(() -> newItem(c, Kind.CAMPAIGN));
        item.setStatus(Status.APPROVED);
        item.setDecidedBy(actorUserId);
        item.setDecidedAt(OffsetDateTime.now());
        item.setNote(note);
        approvals.save(item);
    }

    /** Withdraws every open item, e.g. when a campaign is cancelled. */
    @Transactional
    public void withdrawAll(MarketingCampaign c) {
        for (Kind k : Kind.values()) withdraw(c, k);
    }

    private void open(MarketingCampaign c, Kind kind) {
        if (approvals.findFirstByCampaignIdAndKindAndStatus(c.getId(), kind, Status.PENDING).isPresent()) return;
        approvals.save(newItem(c, kind));
    }

    private MarketingApproval newItem(MarketingCampaign c, Kind kind) {
        MarketingApproval item = new MarketingApproval();
        item.setOrganizationId(c.getOrganizationId());
        item.setCampaignId(c.getId());
        item.setKind(kind);
        item.setSubmittedBy(c.getCreatedBy());
        item.setSubmittedAt(OffsetDateTime.now());
        return item;
    }

    private void resolve(MarketingCampaign c, Kind kind, Long actorUserId, String note) {
        approvals.findFirstByCampaignIdAndKindAndStatus(c.getId(), kind, Status.PENDING).ifPresent(item -> {
            item.setStatus(Status.APPROVED);
            item.setDecidedBy(actorUserId);
            item.setDecidedAt(OffsetDateTime.now());
            item.setNote(note);
            approvals.save(item);
        });
    }

    private void withdraw(MarketingCampaign c, Kind kind) {
        approvals.findFirstByCampaignIdAndKindAndStatus(c.getId(), kind, Status.PENDING).ifPresent(item -> {
            item.setStatus(Status.WITHDRAWN);
            item.setDecidedAt(OffsetDateTime.now());
            approvals.save(item);
        });
    }

    public static String key(ContentChannel channel, String variant) {
        return channel.name() + ":" + variant;
    }

    private static String label(String key) {
        return "Email (variant " + key.split(":")[1] + ")";
    }

    private static String pretty(CampaignStatus s) {
        String lower = s.name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
