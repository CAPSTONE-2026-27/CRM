package com.techcrm.crm.contract;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A generated contract for one opportunity.
 *
 * References the CRM by id rather than by association: the rest of this codebase
 * maps foreign keys as plain Long columns (see {@link com.techcrm.crm.deal.Deal}
 * and {@link com.techcrm.crm.onboarding.CustomerOnboarding}), and mixing styles
 * would make the fetch behaviour inconsistent between modules.
 */
@Entity
@Table(name = "contracts")
@Getter
@Setter
public class Contract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** "CTR-000042" — derived from {@link #id}, so assigned after the insert. */
    @Column(name = "contract_number", length = 30)
    private String contractNumber;

    /** The CRM deal this contract was generated from. This is the identifier
     *  the automation platform sends as {@code dealId}. */
    @Column(name = "deal_id", nullable = false)
    private Long dealId;

    /** Copy of {@code deals.opportunity_id} ("OPP-000031") at generation time. */
    @Column(name = "opportunity_id", length = 30)
    private String opportunityId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** The contact the document is addressed to, and who Documenso will ask to sign. */
    @Column(name = "contact_id")
    private Long contactId;

    /** The deal's owner — the sales executive named in the document. */
    @Column(name = "owner_id")
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", nullable = false, length = 40)
    private ContractType contractType;

    @Column(name = "template_key", nullable = false, length = 120)
    private String templateKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContractStatus status = ContractStatus.GENERATED;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "payment_terms", length = 300)
    private String paymentTerms;

    @Column(name = "delivery_timeline", length = 300)
    private String deliveryTimeline;

    /* ---- Stored documents. Paths are relative to contract.storage.root. ---- */

    @Column(name = "docx_path", length = 500)
    private String docxPath;

    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    @Column(name = "docx_size_bytes")
    private Long docxSizeBytes;

    @Column(name = "pdf_size_bytes")
    private Long pdfSizeBytes;

    /* ---- Documenso ---- */

    @Column(name = "documenso_document_id", length = 80)
    private String documensoDocumentId;

    @Column(name = "documenso_recipient_id", length = 80)
    private String documensoRecipientId;

    @Column(name = "sign_url", length = 1000)
    private String signUrl;

    @Column(name = "signer_name", length = 200)
    private String signerName;

    @Column(name = "signer_email", length = 255)
    private String signerEmail;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

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
}
