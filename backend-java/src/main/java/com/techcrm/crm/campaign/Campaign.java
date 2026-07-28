package com.techcrm.crm.campaign;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "campaigns")
@Getter
@Setter
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;

    /** EMAIL | SMS_EMAIL | MULTI_CHANNEL */
    @Column(nullable = false, length = 30)
    private String channel = "EMAIL";

    @Column(length = 300)
    private String goal;

    private BigDecimal budget;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "start_date")
    private OffsetDateTime startDate;

    @Column(name = "end_date")
    private OffsetDateTime endDate;

    private String segment;
    private String region;

    @Column(name = "estimated_reach")
    private Integer estimatedReach;

    /** DRAFT | SCHEDULED | ACTIVE | COMPLETED */
    @Column(nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "sent_count", nullable = false)
    private Integer sentCount = 0;

    /** 0-100 */
    @Column(name = "open_rate_pct")
    private Integer openRatePct;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
