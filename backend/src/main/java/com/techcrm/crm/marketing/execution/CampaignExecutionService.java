package com.techcrm.crm.marketing.execution;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.marketing.abtest.AbTest;
import com.techcrm.crm.marketing.abtest.AbTestRepository;
import com.techcrm.crm.marketing.activity.ActivityService;
import com.techcrm.crm.marketing.api.MarketingDtos.DeliveryEvent;
import com.techcrm.crm.marketing.api.MarketingDtos.Readiness;
import com.techcrm.crm.marketing.approval.ApprovalService;
import com.techcrm.crm.marketing.campaign.*;
import com.techcrm.crm.marketing.content.ContentChannel;
import com.techcrm.crm.marketing.email.EmailSettings;
import com.techcrm.crm.marketing.email.EmailSuppression;
import com.techcrm.crm.marketing.email.SuppressionService;
import com.techcrm.crm.marketing.leads.CampaignLead;
import com.techcrm.crm.marketing.leads.CampaignLeadRepository;
import com.techcrm.crm.marketing.execution.CampaignMessage.Status;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Campaign Execution Service: the lifecycle after approval.
 *
 * <pre>
 * READY_TO_LAUNCH --schedule--> SCHEDULED --(time)--> ACTIVE <--pause/resume--> PAUSED
 *        |                          |                   |
 *        +--------launch------------+-------------------+--(end date)--> COMPLETED
 * any non-terminal state --cancel--> CANCELLED
 * </pre>
 *
 * Launch re-checks readiness on the server: a campaign is never activated on
 * the strength of what the browser last displayed.
 */
@Service
public class CampaignExecutionService {

    private final MarketingCampaignRepository campaigns;
    private final CampaignLeadRepository campaignLeads;
    private final CampaignMessageRepository messages;
    private final AbTestRepository abTests;
    private final ApprovalService approvals;
    private final ActivityService activity;
    private final LeadRepository leads;
    private final SuppressionService suppressions;
    private final MessageEventRepository events;

    public CampaignExecutionService(MarketingCampaignRepository campaigns, CampaignLeadRepository campaignLeads,
                                    CampaignMessageRepository messages, AbTestRepository abTests,
                                    ApprovalService approvals, ActivityService activity, LeadRepository leads,
                                    SuppressionService suppressions, MessageEventRepository events) {
        this.campaigns = campaigns;
        this.campaignLeads = campaignLeads;
        this.messages = messages;
        this.abTests = abTests;
        this.approvals = approvals;
        this.activity = activity;
        this.leads = leads;
        this.suppressions = suppressions;
        this.events = events;
    }

    @Transactional
    public void launch(AuthenticatedUser caller, MarketingCampaign c) {
        requireLaunchable(c);
        Activation a = activate(c);
        approvals.approveCampaign(c, caller.userId(), "Launched");
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "CAMPAIGN_LAUNCHED",
                "Launched the campaign: " + a.queued() + " email" + (a.queued() == 1 ? "" : "s") + " queued"
                        + (a.suppressed() > 0 ? ", " + a.suppressed() + " not sent (unsubscribed, bounced, or no/duplicate address)" : ""),
                a.detail());
    }

    @Transactional
    public void schedule(AuthenticatedUser caller, MarketingCampaign c, OffsetDateTime at) {
        requireLaunchable(c);
        if (at.isBefore(OffsetDateTime.now().plusMinutes(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Schedule a time in the future");
        }
        if (c.getEndDate() != null && at.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().isAfter(c.getEndDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The send time is after the campaign end date");
        }
        c.setScheduledAt(at);
        c.setStatus(CampaignStatus.SCHEDULED);
        campaigns.save(c);
        approvals.approveCampaign(c, caller.userId(), "Scheduled for " + at);
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "CAMPAIGN_SCHEDULED",
                "Scheduled the campaign for " + at, Map.of("scheduledAt", at.toString()));
    }

    @Transactional
    public void unschedule(AuthenticatedUser caller, MarketingCampaign c) {
        if (c.getStatus() != CampaignStatus.SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a scheduled campaign can be unscheduled");
        }
        c.setScheduledAt(null);
        c.setStatus(CampaignStatus.READY_TO_LAUNCH);
        campaigns.save(c);
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "CAMPAIGN_UNSCHEDULED",
                "Removed the schedule; the campaign is back in review", null);
        approvals.recompute(c.getId(), caller.userId());
    }

    @Transactional
    public void pause(AuthenticatedUser caller, MarketingCampaign c) {
        transition(c, CampaignStatus.ACTIVE, CampaignStatus.PAUSED);
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "CAMPAIGN_PAUSED",
                "Paused the campaign; queued messages will wait", null);
    }

    @Transactional
    public void resume(AuthenticatedUser caller, MarketingCampaign c) {
        if (c.getEndDate() != null && c.getEndDate().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The campaign end date has passed");
        }
        transition(c, CampaignStatus.PAUSED, CampaignStatus.ACTIVE);
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "CAMPAIGN_RESUMED", "Resumed the campaign", null);
    }

    @Transactional
    public void cancel(AuthenticatedUser caller, MarketingCampaign c, String reason) {
        if (c.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The campaign is already " + c.getStatus().name().toLowerCase());
        }
        if (c.getLeadPipelineStatus() == PipelineStatus.RUNNING || c.getContentPipelineStatus() == PipelineStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for AI processing to finish before cancelling");
        }
        int stopped = messages.moveStatus(c.getId(), List.of(Status.QUEUED, Status.HELD), Status.CANCELLED);
        c.setStatus(CampaignStatus.CANCELLED);
        c.setCompletedAt(OffsetDateTime.now());
        campaigns.save(c);
        approvals.withdrawAll(c);
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), "CAMPAIGN_CANCELLED",
                "Cancelled the campaign" + (stopped > 0 ? "; " + stopped + " unsent messages were withdrawn" : "")
                        + (reason != null && !reason.isBlank() ? ": " + reason : ""), null);
    }

    /** Dispatcher: a scheduled campaign whose time has come. */
    @Transactional
    public void activateScheduled(MarketingCampaign c) {
        Activation a = activate(c);
        activity.system(c.getOrganizationId(), c.getId(), "CAMPAIGN_STARTED",
                "Scheduled campaign started with " + a.queued() + " queued emails"
                        + (a.suppressed() > 0 ? " (" + a.suppressed() + " not sent)" : ""), a.detail());
    }

    /**
     * Dispatcher: the email provider refused to send anything (e.g. bad
     * credentials). Pausing stops every further attempt until a person has
     * fixed the settings and resumes.
     */
    @Transactional
    public void pauseForProviderError(Long campaignId, String reason) {
        campaigns.findForUpdate(campaignId).filter(c -> c.getStatus() == CampaignStatus.ACTIVE).ifPresent(c -> {
            c.setStatus(CampaignStatus.PAUSED);
            campaigns.save(c);
            activity.system(c.getOrganizationId(), c.getId(), "CAMPAIGN_PAUSED_PROVIDER_ERROR",
                    "Sending paused automatically: " + reason + ". Fix the email settings, then resume.",
                    Map.of("reason", reason == null ? "" : reason));
        });
    }

    /** Dispatcher: past the end date, stop sending and close the campaign. */
    @Transactional
    public void complete(MarketingCampaign c) {
        int stopped = messages.moveStatus(c.getId(), List.of(Status.QUEUED, Status.HELD), Status.CANCELLED);
        c.setStatus(CampaignStatus.COMPLETED);
        c.setCompletedAt(OffsetDateTime.now());
        campaigns.save(c);
        activity.system(c.getOrganizationId(), c.getId(), "CAMPAIGN_COMPLETED",
                "Campaign completed at its end date" + (stopped > 0 ? "; " + stopped + " unsent messages were withdrawn" : ""), null);
    }

    /**
     * Records events for one campaign (the authenticated events endpoint).
     * See {@link #apply} for the rules.
     */
    @Transactional
    public int recordEvents(MarketingCampaign c, List<DeliveryEvent> events) {
        int applied = 0;
        for (DeliveryEvent e : events) {
            CampaignMessage m = find(c, e);
            if (m != null && apply(c.getOrganizationId(), m, e.type(), e.occurredAt(), e.bounceType(), e.reason())) applied++;
        }
        return applied;
    }

    /**
     * Records events from the provider webhook, which identifies messages by
     * our Message-ID header or message id. Events for unknown messages are
     * ignored.
     */
    @Transactional
    public int recordWebhookEvents(List<DeliveryEvent> batch) {
        int applied = 0;
        for (DeliveryEvent e : batch) {
            CampaignMessage m = null;
            if (e.providerMessageId() != null) {
                String id = e.providerMessageId().trim();
                m = messages.findFirstByProviderMessageId(id.startsWith("<") ? id : "<" + id + ">").orElse(null);
            }
            if (m == null && e.messageId() != null) {
                try {
                    m = messages.findById(Long.parseLong(e.messageId())).orElse(null);
                } catch (NumberFormatException ignored) {
                    // unknown id: ignored like any other unmatched event
                }
            }
            if (m == null) continue;
            MarketingCampaign c = campaigns.findById(m.getCampaignId()).orElse(null);
            if (c != null && apply(c.getOrganizationId(), m, e.type(), e.occurredAt(), e.bounceType(), e.reason())) applied++;
        }
        return applied;
    }

    /** An open, click or unsubscribe from a signed link in the email. Returns the message, or null if unknown. */
    @Transactional
    public CampaignMessage recordTracked(long messageId, String type) {
        CampaignMessage m = messages.findById(messageId).orElse(null);
        if (m == null) return null;
        MarketingCampaign c = campaigns.findById(m.getCampaignId()).orElse(null);
        if (c != null) apply(c.getOrganizationId(), m, type, null, null, null);
        return m;
    }

    /**
     * Applies one event to a sent message. Idempotent: an event already
     * recorded keeps its first timestamp. Delivery is recorded only from a
     * DELIVERED event, never assumed from acceptance or engagement. Hard
     * bounces, complaints and unsubscribes add the address to the
     * organization's suppression list.
     */
    boolean apply(Long orgId, CampaignMessage m, String type, OffsetDateTime occurredAt, String bounceType, String reason) {
        if (m.getSentAt() == null) return false;
        OffsetDateTime at = occurredAt == null ? OffsetDateTime.now() : occurredAt;
        if (at.isBefore(m.getSentAt())) at = m.getSentAt();
        String detail = null;
        switch (type) {
            case "DELIVERED" -> m.setDeliveredAt(first(m.getDeliveredAt(), at));
            case "OPENED" -> m.setOpenedAt(first(m.getOpenedAt(), at));
            case "CLICKED" -> m.setClickedAt(first(m.getClickedAt(), at));
            case "RESPONDED" -> m.setRespondedAt(first(m.getRespondedAt(), at));
            case "CONVERTED" -> m.setConvertedAt(first(m.getConvertedAt(), at));
            case "BOUNCED" -> {
                boolean hard = !"SOFT".equals(bounceType);
                m.setBouncedAt(first(m.getBouncedAt(), at));
                m.setBounceType(hard ? CampaignMessage.BounceType.HARD : CampaignMessage.BounceType.SOFT);
                m.setFailureReason(reason == null ? (hard ? "Hard bounce" : "Soft bounce") : clip(reason));
                if (hard) suppressions.suppress(orgId, m.getRecipientEmail(), EmailSuppression.Reason.HARD_BOUNCE, m.getId());
                detail = (hard ? "HARD" : "SOFT") + (reason == null ? "" : ": " + reason);
            }
            case "COMPLAINED" -> {
                m.setComplainedAt(first(m.getComplainedAt(), at));
                suppressions.suppress(orgId, m.getRecipientEmail(), EmailSuppression.Reason.COMPLAINT, m.getId());
            }
            case "UNSUBSCRIBED" -> {
                m.setUnsubscribedAt(first(m.getUnsubscribedAt(), at));
                suppressions.suppress(orgId, m.getRecipientEmail(), EmailSuppression.Reason.UNSUBSCRIBED, m.getId());
            }
            default -> {
                return false;
            }
        }
        messages.save(m);
        MessageEvent ev = new MessageEvent(m, type, detail);
        ev.setOccurredAt(at);
        events.save(ev);
        return true;
    }

    /* ================================================================ */

    private void requireLaunchable(MarketingCampaign c) {
        if (c.getStatus() != CampaignStatus.READY_TO_LAUNCH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The campaign isn't ready to launch (status: " + c.getStatus().name().toLowerCase().replace('_', ' ') + ")");
        }
        Readiness r = approvals.readiness(c);
        if (!r.canLaunch()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not ready: " + String.join("; ", r.blockers()));
        }
    }

    private record Activation(int queued, int held, int suppressed, Map<String, Object> detail) {
    }

    /**
     * Freezes the approved audience into one email message per lead and starts
     * sending. Each recipient address is captured now; a lead whose address is
     * missing, suppressed, or already used by another lead in this campaign
     * gets a SUPPRESSED row instead, so nobody is emailed twice and the
     * reason is on record.
     *
     * With an A/B test, the sendable audience is shuffled deterministically
     * (seeded by campaign id, so the split is reproducible for an audit) and
     * split by the configured percentages; the rest of the test audience is
     * held until a variant is chosen.
     */
    private Activation activate(MarketingCampaign c) {
        if (messages.existsByCampaignId(c.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This campaign has already been sent");
        }
        List<CampaignLead> approved = new ArrayList<>(
                campaignLeads.findByCampaignIdAndApprovalStatus(c.getId(), CampaignLead.ApprovalStatus.APPROVED));
        approved.sort(Comparator.comparing(CampaignLead::getId));
        Map<Long, Lead> byId = leads.findAllById(approved.stream().map(CampaignLead::getLeadId).toList()).stream()
                .filter(l -> Objects.equals(l.getOrganizationId(), c.getOrganizationId()))
                .collect(Collectors.toMap(Lead::getId, Function.identity()));
        Map<String, EmailSuppression.Reason> suppressed = suppressions.lookup(c.getOrganizationId(),
                byId.values().stream().map(Lead::getEmail).filter(Objects::nonNull).toList());

        OffsetDateTime now = OffsetDateTime.now();
        List<CampaignMessage> out = new ArrayList<>();
        List<CampaignMessage> sendable = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CampaignLead row : approved) {
            Lead lead = byId.get(row.getLeadId());
            CampaignMessage m = new CampaignMessage();
            m.setCampaignId(c.getId());
            m.setCampaignLeadId(row.getId());
            m.setLeadId(row.getLeadId());
            m.setChannel(ContentChannel.EMAIL);
            m.setQueuedAt(now);
            String email = lead == null || !EmailSettings.isAddress(lead.getEmail()) ? null : EmailSettings.normalise(lead.getEmail());
            m.setRecipientEmail(email);
            String skip = lead == null ? "Lead no longer exists"
                    : email == null ? "No valid email address"
                    : suppressed.containsKey(email) ? "Not sent: " + SuppressionService.describe(suppressed.get(email))
                    : !seen.add(email) ? "Duplicate address: another approved lead in this campaign has the same email"
                    : null;
            if (skip != null) {
                m.setStatus(Status.SUPPRESSED);
                m.setFailureReason(skip);
            } else {
                sendable.add(m);
            }
            out.add(m);
        }

        Collections.shuffle(sendable, new Random(c.getId()));
        Optional<AbTest> ab = abTests.findByCampaignId(c.getId()).filter(AbTest::getEnabled);
        int n = sendable.size();
        int testCount = ab.map(t -> (int) Math.round(n * t.getTestAudiencePct() / 100.0)).orElse(n);
        int aCount = ab.map(t -> (int) Math.round(testCount * t.getSplitAPct() / 100.0)).orElse(n);
        for (int i = 0; i < n; i++) {
            CampaignMessage m = sendable.get(i);
            if (ab.isEmpty() || i < aCount) m.setVariant("A");
            else if (i < testCount) m.setVariant("B");
            else m.setStatus(Status.HELD);
        }
        messages.saveAll(out);
        for (CampaignMessage m : out) {
            if (m.getStatus() == Status.SUPPRESSED) events.save(new MessageEvent(m, "SUPPRESSED", m.getFailureReason()));
        }

        ab.ifPresent(t -> {
            t.setStatus(AbTest.Status.RUNNING);
            t.setStartedAt(now);
            t.setEndsAt(now.plusHours(t.getDurationHours()));
            abTests.save(t);
        });
        c.setStatus(CampaignStatus.ACTIVE);
        c.setLaunchedAt(now);
        campaigns.save(c);

        int queued = (int) out.stream().filter(m -> m.getStatus() == Status.QUEUED).count();
        int held = (int) out.stream().filter(m -> m.getStatus() == Status.HELD).count();
        int skipped = out.size() - queued - held;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("approvedLeads", approved.size());
        detail.put("queued", queued);
        detail.put("heldForAbDecision", held);
        detail.put("notSent", skipped);
        return new Activation(queued, held, skipped, detail);
    }

    private void transition(MarketingCampaign c, CampaignStatus from, CampaignStatus to) {
        if (c.getStatus() != from) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only " + from.name().toLowerCase() + " campaigns can be " + (to == CampaignStatus.PAUSED ? "paused" : "resumed"));
        }
        c.setStatus(to);
        campaigns.save(c);
    }

    private CampaignMessage find(MarketingCampaign c, DeliveryEvent e) {
        try {
            if (e.providerMessageId() != null) {
                String id = e.providerMessageId().trim();
                CampaignMessage m = messages.findFirstByProviderMessageId(id.startsWith("<") ? id : "<" + id + ">").orElse(null);
                if (m != null && m.getCampaignId().equals(c.getId())) return m;
            }
            if (e.messageId() != null) {
                return messages.findByIdAndCampaignId(Long.parseLong(e.messageId()), c.getId()).orElse(null);
            }
            if (e.leadId() != null) {
                return messages.findByCampaignIdAndLeadId(c.getId(), Long.parseLong(e.leadId())).stream()
                        .filter(m -> m.getSentAt() != null)
                        .max(Comparator.comparing(CampaignMessage::getSentAt)).orElse(null);
            }
        } catch (NumberFormatException ignored) {
            // unknown id: ignored like any other unmatched event
        }
        return null;
    }

    private static OffsetDateTime first(OffsetDateTime existing, OffsetDateTime candidate) {
        return existing != null ? existing : candidate;
    }

    private static String clip(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
