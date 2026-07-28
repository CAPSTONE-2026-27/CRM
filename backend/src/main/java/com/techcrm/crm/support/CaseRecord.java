package com.techcrm.crm.support;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/** A support case. Named CaseRecord rather than Case because {@code case} is
 *  a Java keyword and "Case" alone reads ambiguously next to switch syntax. */
@Entity
@Table(name = "cases")
@Getter
@Setter
public class CaseRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** User-facing sequential number, unique per organization (see CaseService). */
    @Column(name = "case_number", nullable = false)
    private Integer caseNumber;

    @Column(nullable = false, length = 300)
    private String subject;

    private String source;

    /** LOW | MEDIUM | HIGH | CRITICAL */
    @Column(nullable = false, length = 20)
    private String priority = "MEDIUM";

    @Column(name = "sla_deadline")
    private OffsetDateTime slaDeadline;

    /** OPEN | IN_PROGRESS | ESCALATED | RESOLVED | CLOSED */
    @Column(nullable = false, length = 20)
    private String status = "OPEN";

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "assigned_to_id")
    private Long assignedToId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
