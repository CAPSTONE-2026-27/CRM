package com.techcrm.crm.marketing.execution;

import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.marketing.abtest.AbTest;
import com.techcrm.crm.marketing.abtest.AbTestRepository;
import com.techcrm.crm.marketing.abtest.AbTestService;
import com.techcrm.crm.marketing.analytics.CampaignAnalyticsService;
import com.techcrm.crm.marketing.campaign.CampaignStatus;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.campaign.MarketingCampaignRepository;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.content.ContentChannel;
import com.techcrm.crm.marketing.content.ContentVariant;
import com.techcrm.crm.marketing.content.ContentVariantRepository;
import com.techcrm.crm.marketing.email.CampaignEmailComposer;
import com.techcrm.crm.marketing.email.EmailSettings;
import com.techcrm.crm.marketing.email.EmailSuppression;
import com.techcrm.crm.marketing.email.SuppressionService;
import com.techcrm.crm.marketing.execution.CampaignMessage.Status;
import com.techcrm.crm.marketing.execution.DeliveryProvider.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The campaign engine's clock. Each tick, in this order:
 * <ol>
 *   <li>resolves messages left mid-send by a stopped process;</li>
 *   <li>starts scheduled campaigns whose time has come;</li>
 *   <li>sends a batch of queued email per active campaign, if sending is configured;</li>
 *   <li>closes A/B windows that have ended and applies the decision rule;</li>
 *   <li>attributes conversions from leads converted to deals after an email;</li>
 *   <li>completes active campaigns past their end date.</li>
 * </ol>
 *
 * Sending is at-most-once per message. A batch is first claimed (QUEUED to
 * SENDING) and committed; only then is each email handed to the provider,
 * outside any database lock, and its outcome recorded in its own transaction.
 * A message whose outcome was lost (the process stopped mid-send) is marked
 * failed rather than retried, because it may already have gone out.
 */
@Component
public class CampaignDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatcher.class);

    /** Retry delays after the 1st, 2nd, 3rd... transient failure. */
    private static final Duration[] BACKOFF = {Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30)};
    /** Consecutive transient failures within one batch after which the rest of the batch waits. */
    private static final int TRANSIENT_STREAK_LIMIT = 3;
    private static final Duration STALE_SENDING = Duration.ofMinutes(10);

    private final MarketingCampaignRepository campaigns;
    private final CampaignMessageRepository messages;
    private final MessageEventRepository events;
    private final ContentVariantRepository contents;
    private final LeadRepository leads;
    private final AbTestRepository abTests;
    private final AbTestService abTestService;
    private final CampaignAnalyticsService analytics;
    private final CampaignExecutionService execution;
    private final DeliveryProvider provider;
    private final CampaignEmailComposer composer;
    private final SuppressionService suppressions;
    private final EmailSettings settings;
    private final TransactionTemplate tx;
    private final int batchSize;

    public CampaignDispatcher(MarketingCampaignRepository campaigns, CampaignMessageRepository messages,
                              MessageEventRepository events, ContentVariantRepository contents, LeadRepository leads,
                              AbTestRepository abTests, AbTestService abTestService, CampaignAnalyticsService analytics,
                              CampaignExecutionService execution, DeliveryProvider provider,
                              CampaignEmailComposer composer, SuppressionService suppressions, EmailSettings settings,
                              PlatformTransactionManager transactionManager,
                              @Value("${marketing.delivery.batch-size:50}") int batchSize) {
        this.campaigns = campaigns;
        this.messages = messages;
        this.events = events;
        this.contents = contents;
        this.leads = leads;
        this.abTests = abTests;
        this.abTestService = abTestService;
        this.analytics = analytics;
        this.execution = execution;
        this.provider = provider;
        this.composer = composer;
        this.suppressions = suppressions;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${marketing.delivery.dispatch-interval-ms:15000}",
            initialDelayString = "${marketing.delivery.initial-delay-ms:30000}")
    public void tick() {
        OffsetDateTime now = OffsetDateTime.now();

        safely("stale-sending", null, this::resolveStaleSends);

        for (MarketingCampaign c : campaigns.findByStatusAndScheduledAtLessThanEqual(CampaignStatus.SCHEDULED, now)) {
            safely("start", c.getId(), () -> tx.executeWithoutResult(s ->
                    campaigns.findForUpdate(c.getId()).filter(x -> x.getStatus() == CampaignStatus.SCHEDULED)
                            .ifPresent(execution::activateScheduled)));
        }

        // Without a configured provider nothing is sent and nothing is marked
        // sent: queued email simply waits, and the UI shows configuration-required.
        if (provider.configured()) {
            for (MarketingCampaign c : campaigns.findByStatusIn(List.of(CampaignStatus.ACTIVE))) {
                safely("dispatch", c.getId(), () -> dispatchBatch(c.getId()));
            }
        }

        for (AbTest t : abTests.findByStatusAndEndsAtLessThanEqual(AbTest.Status.RUNNING, now)) {
            safely("ab-window", t.getCampaignId(), () -> tx.executeWithoutResult(s -> {
                MarketingCampaign c = campaigns.findById(t.getCampaignId()).orElse(null);
                if (c == null) return;
                abTestService.closeWindow(t, c, analytics.funnelsByVariant(List.of(c.getId())));
            }));
        }

        safely("conversions", null, () -> tx.executeWithoutResult(s -> {
            int n = messages.attributeConversions();
            if (n > 0) log.info("Attributed {} campaign conversion(s) from converted leads", n);
        }));

        LocalDate today = LocalDate.now();
        for (MarketingCampaign c : campaigns.findByStatusIn(List.of(CampaignStatus.ACTIVE, CampaignStatus.PAUSED))) {
            if (c.getEndDate() != null && c.getEndDate().isBefore(today)) {
                safely("complete", c.getId(), () -> tx.executeWithoutResult(s ->
                        campaigns.findForUpdate(c.getId()).filter(x -> x.getStatus() == CampaignStatus.ACTIVE
                                || x.getStatus() == CampaignStatus.PAUSED).ifPresent(execution::complete)));
            }
        }
    }

    /* ================================================================
     * Sending
     * ================================================================ */

    private record Claim(MarketingCampaign campaign, List<CampaignMessage> batch) {
    }

    void dispatchBatch(Long campaignId) {
        Claim claim = tx.execute(s -> {
            // Under the campaign lock: a pause committed before this point wins.
            MarketingCampaign c = campaigns.findForUpdate(campaignId).orElse(null);
            if (c == null || c.getStatus() != CampaignStatus.ACTIVE) return null;
            OffsetDateTime now = OffsetDateTime.now();
            List<CampaignMessage> batch = messages.findSendable(campaignId, now, PageRequest.of(0, batchSize));
            for (CampaignMessage m : batch) {
                m.setStatus(Status.SENDING);
                m.setAttempts(m.getAttempts() + 1);
                m.setLastAttemptAt(now);
            }
            messages.saveAll(batch);
            return new Claim(c, batch);
        });
        if (claim == null || claim.batch().isEmpty()) return;
        MarketingCampaign c = claim.campaign();

        Map<String, Map<String, String>> approved = tx.execute(s -> {
            Map<String, Map<String, String>> out = new HashMap<>();
            for (ContentVariant v : contents.findByCampaignIdOrderByChannelAscVariantAsc(campaignId)) {
                if (v.getChannel() == ContentChannel.EMAIL && v.getStatus() == ContentVariant.Status.APPROVED) {
                    out.put(v.getVariant(), MarketingJson.readStringMap(v.getFieldsJson()));
                }
            }
            return out;
        });
        Map<Long, Lead> byId = leads.findAllById(claim.batch().stream().map(CampaignMessage::getLeadId).toList()).stream()
                .filter(l -> Objects.equals(l.getOrganizationId(), c.getOrganizationId()))
                .collect(Collectors.toMap(Lead::getId, Function.identity()));
        Map<String, EmailSuppression.Reason> suppressed = suppressions.lookup(c.getOrganizationId(),
                claim.batch().stream().map(m -> recipient(m, byId.get(m.getLeadId()))).filter(Objects::nonNull).toList());

        Deque<CampaignMessage> pending = new ArrayDeque<>(claim.batch());
        int transientStreak = 0;
        while (!pending.isEmpty()) {
            if (campaigns.currentStatus(campaignId) != CampaignStatus.ACTIVE) {
                release(pending, "Campaign is no longer active");
                return;
            }
            CampaignMessage m = pending.poll();
            Lead lead = byId.get(m.getLeadId());
            String to = recipient(m, lead);

            if (lead == null) {
                finish(m, Status.FAILED, "Lead no longer exists", "SEND_FAILED");
                continue;
            }
            if (to == null) {
                finish(m, Status.SUPPRESSED, "No valid email address", "SUPPRESSED");
                continue;
            }
            EmailSuppression.Reason reason = suppressed.get(to);
            if (reason != null) {
                finish(m, Status.SUPPRESSED, "Not sent: " + SuppressionService.describe(reason), "SUPPRESSED");
                continue;
            }
            Map<String, String> fields = approved.get(m.getVariant() == null ? "A" : m.getVariant());
            if (fields == null) {
                finish(m, Status.FAILED, "No approved email for variant " + m.getVariant(), "SEND_FAILED");
                continue;
            }

            DeliveryProvider.Result result;
            try {
                result = provider.send(composer.compose(c, fields, m, lead, to));
            } catch (RuntimeException e) {
                log.warn("Email provider error for message {}: {}", m.getId(), e.toString());
                result = DeliveryProvider.Result.of(Kind.TRANSIENT, "Unexpected provider error");
            }

            switch (result.kind()) {
                case ACCEPTED -> {
                    transientStreak = 0;
                    accepted(m, result.providerMessageId());
                }
                case PERMANENT -> {
                    transientStreak = 0;
                    rejected(c, m, to, result.reason());
                }
                case TRANSIENT -> {
                    retryOrFail(m, result.reason());
                    if (++transientStreak >= TRANSIENT_STREAK_LIMIT) {
                        release(pending, "Provider unavailable; batch deferred");
                        return;
                    }
                }
                case PROVIDER_ERROR -> {
                    String why = result.reason();
                    pending.addFirst(m);
                    release(pending, why);
                    tx.executeWithoutResult(s -> execution.pauseForProviderError(campaignId, why));
                    return;
                }
            }
        }
    }

    /** The address to send to: frozen at launch, or the lead's current one for older rows. */
    private static String recipient(CampaignMessage m, Lead lead) {
        String raw = m.getRecipientEmail() != null ? m.getRecipientEmail() : lead == null ? null : lead.getEmail();
        return EmailSettings.isAddress(raw) ? EmailSettings.normalise(raw) : null;
    }

    private void accepted(CampaignMessage m, String providerMessageId) {
        tx.executeWithoutResult(s -> {
            m.setStatus(Status.SENT);
            m.setSentAt(OffsetDateTime.now());
            m.setProviderMessageId(providerMessageId);
            m.setFailureReason(null);
            m.setNextAttemptAt(null);
            messages.save(m);
            events.save(new MessageEvent(m, "SEND_ACCEPTED", "Accepted by " + provider.name()
                    + " (attempt " + m.getAttempts() + ")"));
        });
    }

    /** The provider refused this recipient for good: fail it and keep the address off future sends. */
    private void rejected(MarketingCampaign c, CampaignMessage m, String to, String reason) {
        tx.executeWithoutResult(s -> {
            fail(m, reason);
            messages.save(m);
            events.save(new MessageEvent(m, "SEND_REJECTED", reason));
            if (reason != null && (reason.startsWith("Recipient rejected") || reason.startsWith("Invalid recipient"))) {
                suppressions.suppress(c.getOrganizationId(), to, EmailSuppression.Reason.HARD_BOUNCE, m.getId());
            }
        });
    }

    private void retryOrFail(CampaignMessage m, String reason) {
        tx.executeWithoutResult(s -> {
            if (m.getAttempts() >= settings.maxAttempts()) {
                fail(m, "Gave up after " + m.getAttempts() + " attempts: " + reason);
                events.save(new MessageEvent(m, "SEND_FAILED", m.getFailureReason()));
            } else {
                Duration wait = BACKOFF[Math.min(m.getAttempts(), BACKOFF.length) - 1];
                m.setStatus(Status.QUEUED);
                m.setNextAttemptAt(OffsetDateTime.now().plus(wait));
                m.setFailureReason(clip(reason));
                events.save(new MessageEvent(m, "SEND_RETRY", reason + "; retrying in " + wait.toMinutes() + " min"));
            }
            messages.save(m);
        });
    }

    /** Puts claimed-but-unattempted messages back in the queue without using up an attempt. */
    private void release(Collection<CampaignMessage> unsent, String why) {
        if (unsent.isEmpty()) return;
        tx.executeWithoutResult(s -> {
            for (CampaignMessage m : unsent) {
                m.setStatus(Status.QUEUED);
                m.setAttempts(Math.max(0, m.getAttempts() - 1));
            }
            messages.saveAll(unsent);
        });
        log.info("Released {} campaign message(s) back to the queue: {}", unsent.size(), why);
    }

    private void finish(CampaignMessage m, Status status, String reason, String eventType) {
        tx.executeWithoutResult(s -> {
            m.setStatus(status);
            m.setFailureReason(clip(reason));
            messages.save(m);
            events.save(new MessageEvent(m, eventType, reason));
        });
    }

    /**
     * A SENDING message older than a few minutes belongs to a send whose
     * outcome was lost. It may have been delivered, so it is failed rather
     * than retried: a missing email is better than a duplicate.
     */
    void resolveStaleSends() {
        tx.executeWithoutResult(s -> {
            List<CampaignMessage> stale = messages.findByStatusAndLastAttemptAtBefore(Status.SENDING,
                    OffsetDateTime.now().minus(STALE_SENDING));
            for (CampaignMessage m : stale) {
                fail(m, "Send interrupted; the outcome is unknown, so it was not retried to avoid a duplicate");
                events.save(new MessageEvent(m, "SEND_UNKNOWN", m.getFailureReason()));
            }
            messages.saveAll(stale);
            if (!stale.isEmpty()) log.warn("Marked {} interrupted campaign send(s) as failed", stale.size());
        });
    }

    private static void fail(CampaignMessage m, String reason) {
        m.setStatus(Status.FAILED);
        m.setNextAttemptAt(null);
        m.setFailureReason(reason == null ? "Not sent" : clip(reason));
    }

    private static String clip(String reason) {
        return reason == null ? null : reason.length() > 300 ? reason.substring(0, 300) : reason;
    }

    private static void safely(String step, Long campaignId, Runnable work) {
        try {
            work.run();
        } catch (Exception e) {
            log.error("Campaign dispatcher step '{}' failed for campaign {}", step, campaignId, e);
        }
    }
}
