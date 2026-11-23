package com.techcrm.crm.marketing.execution;

import com.techcrm.crm.marketing.execution.CampaignMessage.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CampaignMessageRepository extends JpaRepository<CampaignMessage, Long> {

    List<CampaignMessage> findByCampaignIdAndStatus(Long campaignId, Status status, Pageable page);

    /** Queued messages whose retry delay (if any) has passed, oldest first. */
    @Query("select m from CampaignMessage m where m.campaignId = :campaignId "
            + "and m.status = com.techcrm.crm.marketing.execution.CampaignMessage.Status.QUEUED "
            + "and (m.nextAttemptAt is null or m.nextAttemptAt <= :now) order by m.id")
    List<CampaignMessage> findSendable(@Param("campaignId") Long campaignId, @Param("now") OffsetDateTime now, Pageable page);

    /** Claimed for sending before :before and never resolved (the process stopped mid-send). */
    List<CampaignMessage> findByStatusAndLastAttemptAtBefore(Status status, OffsetDateTime before);

    Optional<CampaignMessage> findFirstByProviderMessageId(String providerMessageId);

    List<CampaignMessage> findByCampaignIdAndStatus(Long campaignId, Status status);

    Optional<CampaignMessage> findByIdAndCampaignId(Long id, Long campaignId);

    List<CampaignMessage> findByCampaignIdAndLeadId(Long campaignId, Long leadId);

    long countByCampaignIdAndStatusIn(Long campaignId, Collection<Status> statuses);

    boolean existsByCampaignId(Long campaignId);

    @Modifying
    @Query("update CampaignMessage m set m.status = :to where m.campaignId = :campaignId and m.status in :from")
    int moveStatus(@Param("campaignId") Long campaignId, @Param("from") Collection<Status> from, @Param("to") Status to);

    /**
     * Funnel counts per variant. Row shape: [variant, sent, delivered, bounced,
     * opened, clicked, responded, converted, unsubscribed, failed, suppressed].
     */
    @Query("select m.variant, "
            + "sum(case when m.sentAt is not null then 1 else 0 end), "
            + "sum(case when m.deliveredAt is not null then 1 else 0 end), "
            + "sum(case when m.bouncedAt is not null then 1 else 0 end), "
            + "sum(case when m.openedAt is not null then 1 else 0 end), "
            + "sum(case when m.clickedAt is not null then 1 else 0 end), "
            + "sum(case when m.respondedAt is not null then 1 else 0 end), "
            + "sum(case when m.convertedAt is not null then 1 else 0 end), "
            + "sum(case when m.unsubscribedAt is not null then 1 else 0 end), "
            + "sum(case when m.status = com.techcrm.crm.marketing.execution.CampaignMessage.Status.FAILED then 1 else 0 end), "
            + "sum(case when m.status = com.techcrm.crm.marketing.execution.CampaignMessage.Status.SUPPRESSED then 1 else 0 end) "
            + "from CampaignMessage m where m.campaignId in :campaignIds group by m.variant")
    List<Object[]> funnelByVariant(@Param("campaignIds") Collection<Long> campaignIds);

    /** Messages with any event on or after :since, for the day-by-day series. */
    @Query("select m from CampaignMessage m where m.campaignId in :campaignIds and (m.sentAt >= :since "
            + "or m.deliveredAt >= :since or m.openedAt >= :since or m.clickedAt >= :since or m.convertedAt >= :since "
            + "or m.bouncedAt >= :since or m.unsubscribedAt >= :since)")
    List<CampaignMessage> activeSince(@Param("campaignIds") Collection<Long> campaignIds, @Param("since") OffsetDateTime since);

    /**
     * Conversion attribution from CRM data: a message's lead that was converted
     * to a deal after the message was sent counts as a conversion. Native
     * because it joins across modules on the leads table.
     */
    @Modifying
    @Query(value = "UPDATE mkt_messages m SET converted_at = l.converted_at FROM leads l "
            + "WHERE l.id = m.lead_id AND m.converted_at IS NULL AND m.sent_at IS NOT NULL "
            + "AND l.converted_at IS NOT NULL AND l.converted_at > m.sent_at", nativeQuery = true)
    int attributeConversions();

    @Query("select count(distinct m.leadId) from CampaignMessage m where m.campaignId in :campaignIds and m.sentAt is not null")
    long countLeadsReached(@Param("campaignIds") Collection<Long> campaignIds);

    @Query("select count(distinct m.leadId) from CampaignMessage m where m.campaignId in :campaignIds "
            + "and m.sentAt >= :from and m.sentAt < :to")
    long countLeadsReachedBetween(@Param("campaignIds") Collection<Long> campaignIds,
                                  @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** Per campaign: [campaignId, sent, delivered, opened, clicked, converted, bounced, unsubscribed]. */
    @Query("select m.campaignId, "
            + "sum(case when m.sentAt is not null then 1 else 0 end), "
            + "sum(case when m.deliveredAt is not null then 1 else 0 end), "
            + "sum(case when m.openedAt is not null then 1 else 0 end), "
            + "sum(case when m.clickedAt is not null then 1 else 0 end), "
            + "sum(case when m.convertedAt is not null then 1 else 0 end), "
            + "sum(case when m.bouncedAt is not null then 1 else 0 end), "
            + "sum(case when m.unsubscribedAt is not null then 1 else 0 end) "
            + "from CampaignMessage m where m.campaignId in :campaignIds group by m.campaignId")
    List<Object[]> countsByCampaign(@Param("campaignIds") Collection<Long> campaignIds);
}
