package com.techcrm.crm.contract;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Request/response payloads for the contract module, grouped the same way
 *  {@link com.techcrm.crm.account.AccountDtos} groups the account ones. */
public final class ContractDtos {

    private ContractDtos() {
    }

    /**
     * Input to POST /api/contracts/generate.
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
    public record GenerateContractRequest(
            @NotNull Long dealId,
            /** Force a template instead of letting the resolver choose. */
            @Size(max = 40) String contractType,
            /** Which contact signs. Defaults to the account's primary contact. */
            Long contactId,
            LocalDate startDate,
            LocalDate endDate,
            @Size(max = 300) String paymentTerms,
            @Size(max = 300) String deliveryTimeline,
            /**
             * Supersede the deal's existing live contract and draft a new one.
             * Absent or false makes a retry idempotent — the automation platform
             * gets the contract it already created back, rather than a second one.
             */
            Boolean regenerate
    ) {
    }

    /**
     * The generate response. Exactly the four fields the automation platform's
     * workflow consumes, plus the contract's own reference and type so a human
     * looking at the bot's log can identify the document without a second call.
     */
    public record GenerateContractResponse(
            String contractId,
            String contractNumber,
            String dealId,
            String opportunityId,
            String contractType,
            String status,
            String pdfUrl,
            String docxUrl
    ) {
    }

    /**
     * Optional body of POST /api/contracts/{contractId}/send-email. Every field
     * may be left out.
     */
    public record SendContractEmailRequest(
            /** Send somewhere other than the contract's contact — useful for a test
             *  send, since demo contacts use undeliverable example.com addresses. */
            @Email @Size(max = 255) String recipientEmail,
            @Size(max = 200) String recipientName,
            /** Email the customer again even though this contract was already emailed. */
            Boolean resend
    ) {
    }

    /**
     * Result of POST /api/contracts/{contractId}/send-email.
     *
     * {@code status} is {@code SENT}, or {@code ALREADY_SENT} when a retry found
     * the contract already emailed and nothing was sent twice — in which case
     * the other fields describe that earlier send. {@code attachmentBytes} is the
     * size of the PDF that went out, so a caller can see the whole document was
     * attached.
     */
    public record ContractEmailResponse(
            String contractId,
            String contractNumber,
            String status,
            String sentTo,
            String mailjetMessageId,
            Integer attachmentBytes,
            OffsetDateTime sentAt
    ) {
    }

    /** GET /api/contracts/{contractId}/status — deliberately small, because the
     *  automation platform polls it. */
    public record ContractStatusResponse(
            String contractId,
            String dealId,
            String status,
            OffsetDateTime sentAt,
            OffsetDateTime signedAt,
            OffsetDateTime rejectedAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record ContractLineItemResponse(
            int lineNumber,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            String source
    ) {
        public static ContractLineItemResponse from(ContractLineItem item) {
            return new ContractLineItemResponse(
                    item.getLineNumber(),
                    item.getDescription(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getLineTotal(),
                    item.getSource().name());
        }
    }

    /** GET /api/contracts/{contractId} — the whole record. Ids are serialised as
     *  strings to match the rest of this API. */
    public record ContractResponse(
            String contractId,
            String contractNumber,
            String dealId,
            String opportunityId,
            String accountId,
            String accountName,
            String contactId,
            String contactName,
            String contactEmail,
            String ownerId,
            String salesExecutive,
            String contractType,
            String contractTypeName,
            String templateKey,
            String status,
            String failureReason,
            String currency,
            BigDecimal totalAmount,
            LocalDate startDate,
            LocalDate endDate,
            String paymentTerms,
            String deliveryTimeline,
            List<ContractLineItemResponse> lineItems,
            String pdfUrl,
            String docxUrl,
            String signerName,
            String signerEmail,
            OffsetDateTime sentAt,
            OffsetDateTime signedAt,
            OffsetDateTime rejectedAt,
            String rejectionReason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
