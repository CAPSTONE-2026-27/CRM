package com.techcrm.crm.marketing.execution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Execution log entry for one campaign message: a send attempt and its
 * outcome, or an event reported by the provider or tracking. Never holds the
 * message body or the recipient address.
 */
@Entity
@Table(name = "mkt_message_events")
@Getter
@Setter
@NoArgsConstructor
public class MessageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, updatable = false)
    private Long messageId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    /** e.g. SEND_ACCEPTED, SEND_RETRY, SEND_FAILED, SUPPRESSED, DELIVERED, BOUNCED, OPENED, CLICKED, UNSUBSCRIBED. */
    @Column(nullable = false, length = 30, updatable = false)
    private String type;

    @Column(length = 500, updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt = OffsetDateTime.now();

    public MessageEvent(CampaignMessage m, String type, String detail) {
        this.messageId = m.getId();
        this.campaignId = m.getCampaignId();
        this.type = type;
        this.detail = detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail;
    }
}
