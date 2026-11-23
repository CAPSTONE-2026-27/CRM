package com.techcrm.crm.proposal;

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
 * The transactional half of proposal creation.
 *
 * Separate from {@link ProposalService} on purpose, for the same reason
 * {@code ContractRecordService} is separate from {@code ContractService}:
 * generating a proposal means rendering a DOCX and shelling out to LibreOffice,
 * which takes tens of seconds — far too long to hold a database transaction
 * open, and holding one would make it impossible to record a failure, since
 * marking the row FAILED inside the transaction that is about to roll back saves
 * nothing.
 *
 * So the orchestration stays untransacted and calls in here for each atomic
 * step. Being a distinct bean is what makes those calls go through the proxy and
 * actually get a transaction — a private method on ProposalService would
 * silently get none.
 */
@Service
public class ProposalRecordService {

    private static final Logger log = LoggerFactory.getLogger(ProposalRecordService.class);

    private final ProposalRepository proposalRepository;
    private final ProposalLineItemRepository lineItemRepository;
    private final DealRepository dealRepository;

    public ProposalRecordService(ProposalRepository proposalRepository,
                                 ProposalLineItemRepository lineItemRepository,
                                 DealRepository dealRepository) {
        this.proposalRepository = proposalRepository;
        this.lineItemRepository = lineItemRepository;
        this.dealRepository = dealRepository;
    }

    /**
     * Inserts the proposal and its pricing.
     *
     * The proposal number is derived from the id, so it can only be assigned
     * after the insert has allocated one — the same two-step
     * {@code DealService.create} uses for {@code opportunityId}.
     */
    @Transactional
    public Proposal createDraft(AuthenticatedUser caller,
                                ProposalAssembly assembly,
                                ProposalType proposalType,
                                String templateKey) {

        Proposal proposal = new Proposal();
        proposal.setOrganizationId(caller.organizationId());
        proposal.setDealId(assembly.deal().getId());
        proposal.setOpportunityId(assembly.deal().getOpportunityId());
        proposal.setAccountId(assembly.account().getId());
        proposal.setContactId(assembly.contact().getId());
        proposal.setOwnerId(assembly.owner().getId());
        proposal.setProposalType(proposalType);
        proposal.setTemplateKey(templateKey);
        // Not DRAFT: nothing has been drafted yet. attachDocuments promotes it
        // once a document actually exists. See ProposalStatus.GENERATING.
        proposal.setStatus(ProposalStatus.GENERATING);
        proposal.setCurrency(assembly.currency());
        proposal.setTotalAmount(assembly.totalAmount());
        proposal.setValidUntil(assembly.validUntil());
        proposal.setPaymentTerms(assembly.paymentTerms());
        proposal.setDeliveryTimeline(assembly.deliveryTimeline());
        proposal.setSignerName(assembly.contact().getFullName());
        proposal.setSignerEmail(assembly.contact().getEmail());
        proposal.setGeneratedBy(caller.userId());

        Proposal saved;
        try {
            saved = proposalRepository.saveAndFlush(proposal);
        } catch (DataIntegrityViolationException e) {
            // uq_proposals_live_per_deal. Two retries of the same bot request
            // arriving together get here; the loser is told the winner exists
            // rather than being handed a constraint violation.
            log.info("Concurrent proposal generation for deal {} was rejected by the live-proposal index",
                    assembly.deal().getId());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A proposal for this deal is already being generated");
        }

        saved.setProposalNumber(proposalNumber(saved.getId()));
        saved = proposalRepository.save(saved);

        List<ProposalLineItem> items = new ArrayList<>();
        for (ProposalAssembly.ResolvedLineItem resolved : assembly.lineItems()) {
            ProposalLineItem item = new ProposalLineItem();
            item.setProposalId(saved.getId());
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

    /** "PRP-000042" — zero-padded so references sort, matching
     *  {@code ContractRecordService.contractNumber} and
     *  {@link com.techcrm.crm.deal.DealStages#opportunityReference}. */
    public static String proposalNumber(Long proposalId) {
        return String.format("PRP-%06d", proposalId);
    }

    @Transactional
    public Proposal attachDocuments(Long proposalId, StoredDocument docx, StoredDocument pdf) {
        Proposal proposal = proposalRepository.findById(proposalId).orElseThrow();
        proposal.setDocxPath(docx.relativePath());
        proposal.setDocxSizeBytes(docx.sizeBytes());
        if (pdf != null) {
            proposal.setPdfPath(pdf.relativePath());
            proposal.setPdfSizeBytes(pdf.sizeBytes());
        }
        proposal.setStatus(ProposalStatus.GENERATED);
        proposal.setFailureReason(null);
        return proposalRepository.save(proposal);
    }

    /**
     * Records that generation failed.
     *
     * FAILED is outside the live-proposal index, so this also releases the deal's
     * slot — the rep can fix the data and try again without an admin having to
     * delete a row.
     */
    @Transactional
    public void markFailed(Long proposalId, String reason) {
        proposalRepository.findById(proposalId).ifPresent(proposal -> {
            proposal.setStatus(ProposalStatus.FAILED);
            proposal.setFailureReason(truncate(reason, 4000));
            proposalRepository.save(proposal);
        });
    }

    /** Records that the proposal reached the customer's inbox. Mirrors
     *  {@code ContractRecordService.markEmailed}. */
    @Transactional
    public Proposal markEmailed(Long proposalId) {
        Proposal proposal = proposalRepository.findById(proposalId).orElseThrow();
        proposal.setStatus(ProposalStatus.SENT);
        proposal.setSentAt(OffsetDateTime.now());
        return proposalRepository.save(proposal);
    }

    /** Retires the previous proposal so a regeneration can take the deal's live
     *  slot. Its documents are left on disk: they may already have been sent to
     *  a customer, and the row still points at them. */
    @Transactional
    public void supersede(Long proposalId) {
        proposalRepository.findById(proposalId).ifPresent(proposal -> {
            proposal.setStatus(ProposalStatus.SUPERSEDED);
            proposalRepository.save(proposal);
        });
    }

    /**
     * Mirrors the proposal's status onto the opportunity.
     *
     * Only {@code proposal_status} and {@code proposal_signed_at} — never
     * {@code stage}, for exactly the reason the contract module gives: moving a
     * deal along the pipeline is a decision the sales executive makes, and an
     * event arriving from an external signing service is not authority to make it
     * for them. It is also the reason this module does not move a deal from
     * PROPOSAL to NEGOTIATION when a proposal is signed.
     */
    @Transactional
    public void mirrorOntoDeal(Long dealId, ProposalStatus status, OffsetDateTime signedAt) {
        Deal deal = dealRepository.findById(dealId).orElse(null);
        if (deal == null) {
            return;
        }
        deal.setProposalStatus(status == null ? null : status.name());
        if (signedAt != null) {
            deal.setProposalSignedAt(signedAt);
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
