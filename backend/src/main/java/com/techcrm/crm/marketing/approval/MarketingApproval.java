package com.techcrm.crm.marketing.approval;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A checkpoint in the approval queue. LEADS and CONTENT open when the matching
 * AI pipeline finishes; CAMPAIGN opens once both are signed off and closes
 * when the campaign is scheduled or launched.
 */
@Entity
@Table(name = "mkt_approvals")
@Getter
@Setter
public class MarketingApproval {

    public enum Kind { LEADS, CONTENT, CAMPAIGN }

    public enum Status { PENDING, APPROVED, REJECTED, WITHDRAWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt = OffsetDateTime.now();

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(length = 500)
    private String note;
}
