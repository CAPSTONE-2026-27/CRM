package com.techcrm.crm.marketing.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.marketing.activity.CampaignActivity;
import com.techcrm.crm.marketing.activity.CampaignActivityRepository;
import com.techcrm.crm.marketing.api.MarketingDtos.*;
import com.techcrm.crm.marketing.approval.MarketingApproval;
import com.techcrm.crm.marketing.approval.MarketingApprovalRepository;
import com.techcrm.crm.marketing.campaign.CampaignStatus;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.campaign.MarketingCampaignRepository;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.common.UserNames;
import com.techcrm.crm.marketing.execution.CampaignMessage;
import com.techcrm.crm.marketing.execution.CampaignMessageRepository;
import com.techcrm.crm.marketing.leads.CampaignLeadRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analytics Service. Every figure is a count over mkt_messages timestamps
 * reported by the email provider, the tracking links and the CRM; nothing is
 * sampled or projected.
 *
 * Definitions:
 * <ul>
 *   <li>Sent: accepted by the email provider. Acceptance is not delivery.</li>
 *   <li>Delivered: confirmed by a provider delivery event. A provider that
 *       reports no deliveries (plain SMTP without webhooks) shows none, and
 *       the delivery rate is then null ("not reported"), not 0%.</li>
 *   <li>Bounced, unsubscribed: from provider events and unsubscribe links.</li>
 *   <li>Opened: an estimate. It counts loads of the tracking image, which
 *       privacy proxies and security scanners can trigger and image blocking
 *       can hide.</li>
 *   <li>Clicked, converted: unique per recipient. Converted = the lead became a
 *       deal in the CRM after the email was sent.</li>
 * </ul>
 * Engagement rates (open, click, response, conversion, unsubscribe) divide by
 * reachable emails: sent minus bounced. Bounce rate divides by sent.
 */
@Service
public class CampaignAnalyticsService {

    private static final int MAX_RANGE_DAYS = 366;

    private final MarketingCampaignRepository campaigns;
    private final CampaignMessageRepository messages;
    private final CampaignLeadRepository campaignLeads;
    private final MarketingApprovalRepository approvals;
    private final CampaignActivityRepository activityRepo;
    private final UserNames userNames;

    public CampaignAnalyticsService(MarketingCampaignRepository campaigns, CampaignMessageRepository messages,
                            CampaignLeadRepository campaignLeads, MarketingApprovalRepository approvals,
                            CampaignActivityRepository activityRepo, UserNames userNames) {
        this.campaigns = campaigns;
        this.messages = messages;
        this.campaignLeads = campaignLeads;
        this.approvals = approvals;
        this.activityRepo = activityRepo;
        this.userNames = userNames;
    }

    /* ================================================================
     * Funnels
     * ================================================================ */

    /** Per-variant funnels ("A", "B", and "-" for messages without a variant). */
    @Transactional(readOnly = true)
    public Map<String, Funnel> funnelsByVariant(Collection<Long> campaignIds) {
        Map<String, Funnel> out = new LinkedHashMap<>();
        if (campaignIds.isEmpty()) return out;
        for (Object[] row : messages.funnelByVariant(campaignIds)) {
            String variant = row[0] == null ? "-" : (String) row[0];
            out.put(variant, funnel(num(row[1]), num(row[2]), num(row[3]), num(row[4]), num(row[5]), num(row[6]),
                    num(row[7]), num(row[8]), num(row[9]), num(row[10])));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Funnel total(Collection<Long> campaignIds) {
        long[] t = new long[10];
        for (Funnel f : funnelsByVariant(campaignIds).values()) {
            t[0] += f.sent();
            t[1] += f.delivered();
            t[2] += f.bounced();
            t[3] += f.opened();
            t[4] += f.clicked();
            t[5] += f.responded();
            t[6] += f.converted();
            t[7] += f.unsubscribed();
            t[8] += f.failed();
            t[9] += f.suppressed();
        }
        return funnel(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9]);
    }

    public static Funnel funnel(long sent, long delivered, long bounced, long opened, long clicked, long responded,
                                long converted, long unsubscribed, long failed, long suppressed) {
        long reachable = Math.max(0, sent - bounced);
        return new Funnel(sent, delivered, bounced, opened, clicked, responded, converted, unsubscribed, failed, suppressed,
                delivered > 0 ? rate(delivered, sent) : null, rate(bounced, sent),
                rate(opened, reachable), rate(clicked, reachable), rate(responded, reachable), rate(converted, reachable),
                rate(unsubscribed, reachable));
    }

    private static Double rate(long part, long whole) {
        if (whole <= 0) return null;
        return Math.round(1000.0 * part / whole) / 10.0;
    }

    /* ================================================================
     * Campaign and organization analytics
     * ================================================================ */

    @Transactional(readOnly = true)
    public AnalyticsResponse forCampaign(MarketingCampaign c, String range, LocalDate from, LocalDate to) {
        LocalDate[] window = window(range, from, to);
        List<Long> ids = List.of(c.getId());
        Map<String, Funnel> variants = funnelsByVariant(ids);
        List<VariantPerformance> perVariant = variants.entrySet().stream()
                .filter(e -> !"-".equals(e.getKey()))
                .map(e -> new VariantPerformance(e.getKey(), e.getValue())).toList();
        long queued = messages.countByCampaignIdAndStatusIn(c.getId(), List.of(CampaignMessage.Status.QUEUED));
        long held = messages.countByCampaignIdAndStatusIn(c.getId(), List.of(CampaignMessage.Status.HELD));
        long sending = messages.countByCampaignIdAndStatusIn(c.getId(), List.of(CampaignMessage.Status.SENDING));
        return new AnalyticsResponse(window[0], window[1], total(ids), series(ids, window[0], window[1]), perVariant,
                queued, held, sending);
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse forOrganization(AuthenticatedUser caller, String range, LocalDate from, LocalDate to,
                                             Long campaignId) {
        LocalDate[] window = window(range, from, to);
        List<Long> ids = campaignId != null
                ? campaigns.findByIdAndOrganizationId(campaignId, caller.organizationId()).map(c -> List.of(c.getId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"))
                : orgCampaignIds(caller.organizationId());
        return new AnalyticsResponse(window[0], window[1], total(ids), series(ids, window[0], window[1]), List.of(), 0, 0, 0);
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(AuthenticatedUser caller) {
        Long org = caller.organizationId();
        List<MarketingCampaign> all = campaigns.findByOrganizationIdOrderByCreatedAtDesc(org);
        List<Long> ids = all.stream().map(MarketingCampaign::getId).toList();
        OffsetDateTime now = OffsetDateTime.now();

        long created30 = all.stream().filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isAfter(now.minusDays(30))).count();
        long active = all.stream().filter(c -> c.getStatus() == CampaignStatus.ACTIVE).count();
        long scheduled = all.stream().filter(c -> c.getStatus() == CampaignStatus.SCHEDULED).count();

        long reached = ids.isEmpty() ? 0 : messages.countLeadsReached(ids);
        long reached30 = ids.isEmpty() ? 0 : messages.countLeadsReachedBetween(ids, now.minusDays(30), now);
        long reachedPrev = ids.isEmpty() ? 0 : messages.countLeadsReachedBetween(ids, now.minusDays(60), now.minusDays(30));

        long waiting = approvals.countByOrganizationIdAndStatus(org, MarketingApproval.Status.PENDING);
        long leadDecisions = 0;
        if (!ids.isEmpty()) {
            for (Object[] row : campaignLeads.countsByCampaign(ids)) leadDecisions += num(row[3]);
        }

        return new DashboardResponse(all.size(), created30, active, scheduled, reached, reached30, reachedPrev,
                waiting, leadDecisions, total(ids), recentActivity(org, all, 8));
    }

    @Transactional(readOnly = true)
    public List<ActivityItem> recentActivity(Long org, List<MarketingCampaign> campaignsInOrg, int limit) {
        Map<Long, String> names = campaignsInOrg.stream()
                .collect(Collectors.toMap(MarketingCampaign::getId, MarketingCampaign::getName, (a, b) -> a));
        return toItems(org, activityRepo.findByOrganizationIdOrderByOccurredAtDescIdDesc(org, PageRequest.of(0, limit)), names);
    }

    @Transactional(readOnly = true)
    public List<ActivityItem> campaignActivity(MarketingCampaign c) {
        return toItems(c.getOrganizationId(), activityRepo.findByCampaignIdOrderByOccurredAtDescIdDesc(c.getId()),
                Map.of(c.getId(), c.getName()));
    }

    private List<ActivityItem> toItems(Long org, List<CampaignActivity> rows, Map<Long, String> campaignNames) {
        Map<Long, String> users = userNames.resolve(org, rows.stream().map(CampaignActivity::getActorUserId).toList());
        return rows.stream().map(a -> new ActivityItem(String.valueOf(a.getId()), String.valueOf(a.getCampaignId()),
                campaignNames.get(a.getCampaignId()), a.getActorType().name(),
                a.getActorUserId() == null ? null : users.getOrDefault(a.getActorUserId(), "User " + a.getActorUserId()),
                a.getAction(), a.getSummary(),
                MarketingJson.read(a.getDetailJson(), new TypeReference<Map<String, Object>>() { }, null),
                a.getOccurredAt())).toList();
    }

    /* ================================================================
     * Series
     * ================================================================ */

    private List<SeriesPoint> series(List<Long> ids, LocalDate from, LocalDate to) {
        ZoneId zone = ZoneId.systemDefault();
        Map<LocalDate, long[]> buckets = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) buckets.put(d, new long[7]);
        if (!ids.isEmpty()) {
            OffsetDateTime since = from.atStartOfDay(zone).toOffsetDateTime();
            for (CampaignMessage m : messages.activeSince(ids, since)) {
                add(buckets, m.getSentAt(), 0, zone);
                add(buckets, m.getDeliveredAt(), 1, zone);
                add(buckets, m.getOpenedAt(), 2, zone);
                add(buckets, m.getClickedAt(), 3, zone);
                add(buckets, m.getConvertedAt(), 4, zone);
                add(buckets, m.getBouncedAt(), 5, zone);
                add(buckets, m.getUnsubscribedAt(), 6, zone);
            }
        }
        return buckets.entrySet().stream().map(e -> new SeriesPoint(e.getKey(), e.getValue()[0], e.getValue()[1],
                e.getValue()[2], e.getValue()[3], e.getValue()[4], e.getValue()[5], e.getValue()[6])).toList();
    }

    private static void add(Map<LocalDate, long[]> buckets, OffsetDateTime at, int index, ZoneId zone) {
        if (at == null) return;
        long[] b = buckets.get(at.atZoneSameInstant(zone).toLocalDate());
        if (b != null) b[index]++;
    }

    /** "7d" | "30d" | "90d" | "custom" (with from/to). */
    static LocalDate[] window(String range, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        if ("custom".equalsIgnoreCase(range)) {
            if (from == null || to == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A custom range needs both from and to dates");
            }
            if (to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' is before 'from'");
            if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ranges are limited to " + MAX_RANGE_DAYS + " days");
            }
            return new LocalDate[]{from, to};
        }
        int days = switch (range == null ? "30d" : range.toLowerCase()) {
            case "7d" -> 7;
            case "90d" -> 90;
            default -> 30;
        };
        return new LocalDate[]{today.minusDays(days - 1L), today};
    }

    private List<Long> orgCampaignIds(Long org) {
        return campaigns.findByOrganizationIdOrderByCreatedAtDesc(org).stream().map(MarketingCampaign::getId).toList();
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
