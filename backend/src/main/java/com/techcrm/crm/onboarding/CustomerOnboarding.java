package com.techcrm.crm.onboarding;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** Deal flow step 14 — opened automatically the moment a deal is marked
 *  Closed Won, so nothing has to remember to hand the customer over. */
@Entity
@Table(name = "customer_onboardings")
@Getter
@Setter
public class CustomerOnboarding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "deal_id", nullable = false)
    private Long dealId;

    @Column(name = "opportunity_id", length = 30)
    private String opportunityId;

    @Column(name = "account_id")
    private Long accountId;

    /** INITIATED | IN_PROGRESS | COMPLETED | CANCELLED */
    @Column(nullable = false, length = 20)
    private String status = "INITIATED";

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(columnDefinition = "text")
    private String notes;

    @CreationTimestamp
    @Column(name = "initiated_at", nullable = false, updatable = false)
    private OffsetDateTime initiatedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
