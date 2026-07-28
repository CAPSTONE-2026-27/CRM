package com.techcrm.crm.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "accounts")
@Getter
@Setter
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;

    private String industry;

    @Column(name = "annual_revenue")
    private BigDecimal annualRevenue;

    @Column(name = "employee_count")
    private String employeeCount;

    @Column(name = "billing_address", length = 500)
    private String billingAddress;

    @Column(name = "parent_account_id")
    private Long parentAccountId;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "relationship_value")
    private BigDecimal relationshipValue;

    /** 0-100, produced by the AI sentiment pass. */
    @Column(name = "ai_sentiment_score")
    private Integer aiSentimentScore;

    @Column(name = "email_integration_enabled", nullable = false)
    private boolean emailIntegrationEnabled = false;

    @Column(name = "telephony_integration_enabled", nullable = false)
    private boolean telephonyIntegrationEnabled = false;

    @Column(name = "doc_repo_sync_enabled", nullable = false)
    private boolean docRepoSyncEnabled = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
