package com.techcrm.crm.contract;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contract.document.DocumentStorageService.StoredDocument;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.deal.DealRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The transactional half of contract creation.
 *
 * Separate from {@link ContractService} on purpose. Generating a contract means
 * rendering a DOCX and shelling out to LibreOffice, which takes seconds — far
 * too long to hold a database transaction open, and holding one would also make
 * it impossible to record a failure, since marking the row FAILED inside the
 * transaction that is about to roll back saves nothing. So the orchestration
 * stays untransacted and calls in here for each atomic step. Being a distinct
 * bean is what makes those calls go through the proxy and actually get a
 * transaction — a private method on ContractService would silently get none.
 */
@Service
public class ContractRecordService {

    private static final Logger log = LoggerFactory.getLogger(ContractRecordService.class);

    private final ContractRepository contractRepository;
    private final ContractLineItemRepository lineItemRepository;
    private final DealRepository dealRepository;

    public ContractRecordService(ContractRepository contractRepository,
                                 ContractLineItemRepository lineItemRepository,
                                 DealRepository dealRepository) {
        this.contractRepository = contractRepository;
        this.lineItemRepository = lineItemRepository;
        this.dealRepository = dealRepository;
    }

    /**
     * Inserts the contract and its schedule.
     *
     * The contract number is derived from the id, so it can only be assigned
     * after the insert has allocated one — the same two-step
     * {@code DealService.create} uses for {@code opportunityId}.
     */
    @Transactional
    public Contract createDraft(AuthenticatedUser caller,
                                ContractAssembly assembly,
                                ContractType contractType,
                                String templateKey) {

        Contract contract = new Contract();
        contract.setOrganizationId(caller.organizationId());
        contract.setDealId(assembly.deal().getId());
        contract.setOpportunityId(assembly.deal().getOpportunityId());
        contract.setAccountId(assembly.account().getId());
        contract.setContactId(assembly.contact().getId());
        contract.setOwnerId(assembly.owner().getId());
        contract.setContractType(contractType);
        contract.setTemplateKey(templateKey);
        // Not GENERATED: nothing has been generated yet. attachDocuments
        // promotes it once a document actually exists. See ContractStatus.DRAFTING.
        contract.setStatus(ContractStatus.DRAFTING);
        contract.setCurrency(assembly.currency());
        contract.setTotalAmount(assembly.totalAmount());
        contract.setStartDate(assembly.startDate());
        contract.setEndDate(assembly.endDate());
        contract.setPaymentTerms(assembly.paymentTerms());
        contract.setDeliveryTimeline(assembly.deliveryTimeline());
        contract.setSignerName(assembly.contact().getFullName());
        contract.setSignerEmail(assembly.contact().getEmail());
        contract.setGeneratedBy(caller.userId());

        Contract saved;
        try {
            saved = contractRepository.saveAndFlush(contract);
        } catch (DataIntegrityViolationException e) {
            // uq_contracts_live_per_deal. Two retries of the same bot request
            // arriving together get here; the loser is told the winner exists
            // rather than being handed a constraint violation.
            log.info("Concurrent contract generation for deal {} was rejected by the live-contract index",
                    assembly.deal().getId());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A contract for this deal is already being generated");
        }

        saved.setContractNumber(contractNumber(saved.getId()));
        saved = contractRepository.save(saved);

        List<ContractLineItem> items = new ArrayList<>();
        for (ContractAssembly.ResolvedLineItem resolved : assembly.lineItems()) {
            ContractLineItem item = new ContractLineItem();
            item.setContractId(saved.getId());
            item.setLineNumber(resolved.lineNumber());
            item.setDescription(resolved.description());
            item.setQuantity(resolved.quantity());
            item.setUnitPrice(resolved.unitPrice());
            item.setLineTotal(resolved.lineTotal());
            item.setSource(resolved.source());
            items.add(item);
        }
        lineItemRepository.saveAll(items);

        return saved;
    }

    /** "CTR-000042" — zero-padded so references sort, matching
     *  {@link com.techcrm.crm.deal.DealStages#opportunityReference}. */
    public static String contractNumber(Long contractId) {
        return String.format("CTR-%06d", contractId);
    }

    @Transactional
    public Contract attachDocuments(Long contractId, StoredDocument docx, StoredDocument pdf) {
        Contract contract = contractRepository.findById(contractId).orElseThrow();
        contract.setDocxPath(docx.relativePath());
        contract.setDocxSizeBytes(docx.sizeBytes());
        if (pdf != null) {
            contract.setPdfPath(pdf.relativePath());
            contract.setPdfSizeBytes(pdf.sizeBytes());
        }
        contract.setStatus(ContractStatus.GENERATED);
        contract.setFailureReason(null);
        return contractRepository.save(contract);
    }

    /**
     * Records that generation failed.
     *
     * FAILED is outside the live-contract index, so this also releases the
     * deal's slot — the rep can fix the data and try again without an admin
     * having to delete a row.
     */
    @Transactional
    public void markFailed(Long contractId, String reason) {
        contractRepository.findById(contractId).ifPresent(contract -> {
            contract.setStatus(ContractStatus.FAILED);
            contract.setFailureReason(truncate(reason, 4000));
            contractRepository.save(contract);
        });
    }

    /**
     * Records that the contract was emailed to the customer.
     *
     * The recipient is not written back: a test send to an override address must
     * not replace the real contact on the contract. Who it went to is in the
     * audit entry. Lives here rather than in the email service for the proxying
     * reason in this class's Javadoc: a {@code @Transactional} method called
     * from inside its own bean gets no transaction at all.
     */
    @Transactional
    public Contract markEmailed(Long contractId) {
        Contract contract = contractRepository.findById(contractId).orElseThrow();
        contract.setStatus(ContractStatus.SENT);
        contract.setSentAt(OffsetDateTime.now());
        return contractRepository.save(contract);
    }

    /** Retires the previous contract so a regeneration can take the deal's live
     *  slot. Its documents are left on disk: they may already have been sent to
     *  a customer, and the row still points at them. */
    @Transactional
    public void supersede(Long contractId) {
        contractRepository.findById(contractId).ifPresent(contract -> {
            contract.setStatus(ContractStatus.SUPERSEDED);
            contractRepository.save(contract);
        });
    }

    /**
     * Mirrors the contract's status onto the opportunity.
     *
     * Only {@code contract_status} and {@code contract_signed_at} — never
     * {@code stage}. Moving a deal to Closed Won is a decision the sales
     * executive makes in the pipeline.
     */
    @Transactional
    public void mirrorOntoDeal(Long dealId, ContractStatus status, OffsetDateTime signedAt) {
        Deal deal = dealRepository.findById(dealId).orElse(null);
        if (deal == null) {
            return;
        }
        deal.setContractStatus(status == null ? null : status.name());
        if (signedAt != null) {
            deal.setContractSignedAt(signedAt);
        }
        dealRepository.save(deal);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
