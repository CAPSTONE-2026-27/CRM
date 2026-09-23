package com.techcrm.crm.proposal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A generated proposal for one opportunity.
 *
 * References the CRM by id rather than by association, matching
 * {@link com.techcrm.crm.contract.Contract} and the rest of this codebase —
 * foreign keys are plain Long columns, and mixing styles would make the fetch
 * behaviour inconsistent between modules.
 *
 * Where this differs from a contract is the commercial shape. A contract has a
 * term with a start and an end; a proposal has a <em>validity</em> — an offer
 * the customer can accept until a date, after which the pricing is no longer
 * ours to honour. So there is a {@code valid_until} here and no term dates.
 */
@Entity
@Table(name = "proposals")
@Getter
@Setter
public class Proposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** "PRP-000042". Derived from the id, so it is filled by the follow-up
     *  update immediately after the insert allocates one. */
    @Column(name = "proposal_number", length = 30)
    private String proposalNumber;

    /** The CRM's opportunity: {@code deals.id}. Not {@code opportunity_id},
     *  which is a display reference derived from it. */
    @Column(name = "deal_id", nullable = false)
    private Long dealId;

    /** Denormalised copy of {@code deals.opportunity_id} so a proposal stays
     *  readable in logs without a join. */
    @Column(name = "opportunity_id", length = 30)
    private String opportunityId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** The person the proposal was addressed to. Recorded because an account can
     *  gain contacts after the document is drafted. */
    @Column(name = "contact_id")
    private Long contactId;

    /** The deal's owner at generation time — the sales executive named on it. */
    @Column(name = "owner_id")
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "proposal_type", nullable = false, length = 40)
    private ProposalType proposalType;

    @Column(name = "template_key", nullable = false, length = 120)
    private String templateKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProposalStatus status = ProposalStatus.GENERATING;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    /* --------------------------------------------------- commercial terms */

    /** Frozen at generation time. The deal can be re-priced afterwards; the
     *  document the customer was quoted cannot. */
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    /** The offer's expiry. After this the quoted pricing is no longer binding,
     *  which is the whole difference between a proposal and a contract. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "payment_terms", length = 300)
    private String paymentTerms;

    @Column(name = "delivery_timeline", length = 300)
    private String deliveryTimeline;

    /* --------------------------------------------------------- documents */

    /** Relative to {@code contract.storage.root} — proposals share the existing
     *  document store rather than opening a second one. */
    @Column(name = "docx_path", length = 500)
    private String docxPath;

    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    @Column(name = "docx_size_bytes")
    private Long docxSizeBytes;

    @Column(name = "pdf_size_bytes")
    private Long pdfSizeBytes;

    /* --------------------------------------------------------- recipient */

    /* signed_at, rejected_at and rejection_reason are kept for proposals that
       were signed through the former e-signature integration. */

    @Column(name = "signer_name", length = 200)
    private String signerName;

    @Column(name = "signer_email", length = 255)
    private String signerEmail;

    /** When the proposal PDF was emailed to the customer. */
    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "sent_for_signature_at")
    private OffsetDateTime sentForSignatureAt;

    @Column(name = "signed_at")
    private OffsetDateTime signedAt;

    @Column(name = "rejected_at")
    private OffsetDateTime rejectedAt;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "generated_by")
    private Long generatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Whether generation actually finished and left a document behind.
     *
     * The DOCX path is the signal rather than the PDF, because the PDF is
     * optional — {@code contract.libreoffice.enabled=false} is a legitimate
     * configuration — whereas a finished generation always writes the DOCX.
     * Status alone is not enough: the row is written before the document is
     * rendered, so a run that dies in between leaves a status no file backs.
     */
    public boolean hasDocument() {
        return docxPath != null;
    }
}
