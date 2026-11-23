package com.techcrm.crm.proposal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Request/response payloads for the proposal module, grouped the same way
 *  {@link com.techcrm.crm.contract.ContractDtos} groups the contract ones. */
public final class ProposalDtos {

    private ProposalDtos() {
    }

    /**
     * Input to POST /api/proposals/generate.
     *
     * {@code dealId} is the CRM deal's primary key — the same value
     * {@code GET /api/deals} returns as {@code id} and the lead conversion
     * response returns as {@code dealId}. It is NOT {@code opportunityId}
     * ("OPP-000031"), which is a display reference derived from it, and it is
     * not an account id. Declared as Long so a non-numeric value is rejected
     * with a 400 before any lookup happens; Jackson accepts both {@code 31} and
     * {@code "31"}, which is what the CRM's own string-id responses hand back.
     *
     * Every other field is optional and only overrides a default.
     */
    public record GenerateProposalRequest(
            @NotNull Long dealId,
            /** Force a template instead of letting the resolver choose. */
            @Size(max = 40) String proposalType,
            /** Which contact the proposal is addressed to. Defaults to the
             *  account's primary contact. */
            Long contactId,
            /** When the quotation expires. Defaults to
             *  {@code proposal.default-validity-days} from today. */
            LocalDate validUntil,
            @Size(max = 300) String paymentTerms,
            @Size(max = 300) String deliveryTimeline,
            /**
             * Supersede the deal's existing live proposal and draft a new one.
             * Absent or false makes a retry idempotent — the automation platform
             * gets the proposal it already created back, rather than a second one.
             */
            Boolean regenerate
    ) {
    }

    /**
     * The generate response. The fields the automation platform's workflow
     * consumes, plus the proposal's own reference and type so a human reading
     * the bot's log can identify the document without a second call.
     */
    public record GenerateProposalResponse(
            String proposalId,
            String proposalNumber,
            String dealId,
            String opportunityId,
            String proposalType,
            String status,
            String pdfUrl,
            String docxUrl
    ) {
    }

    /** GET /api/proposals/{proposalId}/status — deliberately small, because the
     *  automation platform polls it. */
    /**
     * Optional body of POST /api/proposals/{proposalId}/send-email. Every field
     * may be left out.
     */
    public record SendProposalEmailRequest(
            /** Send somewhere other than the proposal's contact — useful for a
             *  test send, since demo contacts use undeliverable example.com
             *  addresses. */
            @Email @Size(max = 255) String recipientEmail,
            @Size(max = 200) String recipientName,
            /** Email the customer again even though this proposal was already sent. */
            Boolean resend
    ) {
    }

    /**
     * Result of POST /api/proposals/{proposalId}/send-email.
     *
     * {@code status} is {@code SENT}, or {@code ALREADY_SENT} when a retry found
     * the proposal already emailed and nothing was sent twice — in which case
     * the other fields describe that earlier send. {@code attachmentBytes} is
     * the size of the PDF that went out, so a caller can see the whole document
     * was attached.
     */
    public record ProposalEmailResponse(
            String proposalId,
            String proposalNumber,
            String status,
            String sentTo,
            String mailjetMessageId,
            Integer attachmentBytes,
            OffsetDateTime sentAt
    ) {
    }

    public record ProposalStatusResponse(
            String proposalId,
            String dealId,
            String status,
            OffsetDateTime sentForSignatureAt,
            OffsetDateTime signedAt,
            OffsetDateTime rejectedAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record ProposalLineItemResponse(
            int lineNumber,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            String source
    ) {
        public static ProposalLineItemResponse from(ProposalLineItem item) {
            return new ProposalLineItemResponse(
                    item.getLineNumber(),
                    item.getDescription(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getLineTotal(),
                    item.getSource().name());
        }
    }

    /** GET /api/proposals/{proposalId} — the whole record. Ids are serialised as
     *  strings to match the rest of this API. */
    public record ProposalResponse(
            String proposalId,
            String proposalNumber,
            String dealId,
            String opportunityId,
            String accountId,
            String accountName,
            String contactId,
            String contactName,
            String contactEmail,
            String ownerId,
            String salesExecutive,
            String proposalType,
            String proposalTypeName,
            String templateKey,
            String status,
            String failureReason,
            String currency,
            BigDecimal totalAmount,
            LocalDate validUntil,
            String paymentTerms,
            String deliveryTimeline,
            List<ProposalLineItemResponse> lineItems,
            String pdfUrl,
            String docxUrl,
            String signerName,
            String signerEmail,
            OffsetDateTime sentForSignatureAt,
            OffsetDateTime signedAt,
            OffsetDateTime rejectedAt,
            String rejectionReason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
