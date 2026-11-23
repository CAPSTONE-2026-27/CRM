package com.techcrm.crm.marketing.activity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** One entry in a campaign's audit trail: who (AI, a person, or the system) did what, and when. */
@Entity
@Table(name = "mkt_activity")
@Getter
@Setter
public class CampaignActivity {

    public enum ActorType { AI, HUMAN, SYSTEM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10, updatable = false)
    private ActorType actorType;

    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @Column(nullable = false, length = 60, updatable = false)
    private String action;

    @Column(nullable = false, length = 500, updatable = false)
    private String summary;

    @Column(name = "detail_json", columnDefinition = "text", updatable = false)
    private String detailJson;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;
}
