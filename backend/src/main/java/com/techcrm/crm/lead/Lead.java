package com.techcrm.crm.lead;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "leads")
@Getter
@Setter
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String company;
    private String industry;

    @Column(name = "employee_count")
    private String employeeCount;

    private String email;
    private String phone;
    private String product;

    @Column(name = "estimated_deal_value")
    private BigDecimal estimatedDealValue;

    @Column(name = "source_channel")
    private String sourceChannel;

    @Column(name = "capture_method")
    private String captureMethod;

    @Column(length = 1000)
    private String notes;

    @Column(name = "ai_score")
    private Integer aiScore;

    @Column(name = "ai_score_label")
    private String aiScoreLabel;

    @Column(name = "ai_score_reason", length = 1000)
    private String aiScoreReason;

    @Column(nullable = false, length = 30)
    private String status = "NEW";

    @Column(name = "assigned_to_id")
    private Long assignedToId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}