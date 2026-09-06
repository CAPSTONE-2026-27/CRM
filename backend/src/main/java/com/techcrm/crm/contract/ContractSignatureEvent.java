package com.techcrm.crm.contract;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * A Documenso webhook delivery we accepted, and the record that makes a
 * redelivery a no-op. See the unique constraint in V18 for why the digest is
 * part of the key.
 */
@Entity
@Table(name = "contract_signature_events")
@Getter
@Setter
public class ContractSignatureEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    /** Documenso's own event name, stored verbatim rather than mapped to an
     *  enum: an unrecognised event still belongs in the audit trail. */
    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "documenso_document_id", length = 80)
    private String documensoDocumentId;

    /** SHA-256 of the raw request body. */
    @Column(name = "payload_digest", nullable = false, length = 64)
    private String payloadDigest;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;
}
