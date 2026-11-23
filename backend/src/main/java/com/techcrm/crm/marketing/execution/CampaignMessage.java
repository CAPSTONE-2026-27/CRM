package com.techcrm.crm.marketing.execution;

import com.techcrm.crm.marketing.content.ContentChannel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One recipient's email for a campaign. This row is both the send queue entry
 * and the performance record: sent_at is when the email provider accepted the
 * message, delivered/bounced/opened/clicked/converted are the timestamps
 * reported back afterwards, and every metric on the dashboards is a count over
 * them. Attempts and outcomes are logged in mkt_message_events.
 */
@Entity
@Table(name = "mkt_messages")
@Getter
@Setter
public class CampaignMessage {

    /**
     * HELD: in the A/B holdout, waiting for the decision to pick its variant.
     * SENDING: claimed by the dispatcher and being handed to the provider.
     * SUPPRESSED: deliberately not sent (unsubscribed, bounced, complained,
     * missing or duplicate address).
     */
    public enum Status { HELD, QUEUED, SENDING, SENT, FAILED, CANCELLED, SUPPRESSED }

    public enum BounceType { HARD, SOFT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "campaign_lead_id", nullable = false, updatable = false)
    private Long campaignLeadId;

    @Column(name = "lead_id", nullable = false, updatable = false)
    private Long leadId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ContentChannel channel;

    @Column(length = 1)
    private String variant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(name = "queued_at", nullable = false)
    private OffsetDateTime queuedAt = OffsetDateTime.now();

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "opened_at")
    private OffsetDateTime openedAt;

    @Column(name = "clicked_at")
    private OffsetDateTime clickedAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    /** The address as it was when the campaign was frozen. */
    @Column(name = "recipient_email", length = 254)
    private String recipientEmail;

    /** The Message-ID header sent, used to match provider webhooks. */
    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    @Column(name = "bounced_at")
    private OffsetDateTime bouncedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "bounce_type", length = 10)
    private BounceType bounceType;

    @Column(name = "complained_at")
    private OffsetDateTime complainedAt;

    @Column(name = "unsubscribed_at")
    private OffsetDateTime unsubscribedAt;
}
