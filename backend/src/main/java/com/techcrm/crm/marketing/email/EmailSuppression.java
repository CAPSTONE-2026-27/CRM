package com.techcrm.crm.marketing.email;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** An address this organization's campaigns must not email again. */
@Entity
@Table(name = "mkt_email_suppressions")
@Getter
@Setter
public class EmailSuppression {

    public enum Reason { UNSUBSCRIBED, HARD_BOUNCE, COMPLAINT, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    /** Lower-cased. */
    @Column(nullable = false, length = 254, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Reason reason;

    @Column(name = "source_message_id")
    private Long sourceMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
