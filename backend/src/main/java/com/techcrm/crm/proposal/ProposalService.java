package com.techcrm.crm.proposal;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contact.Contact;
import com.techcrm.crm.contact.ContactRepository;
import com.techcrm.crm.contract.document.ContractDocumentException;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.document.DocumentStorageService.StoredDocument;
import com.techcrm.crm.contract.document.DocxGenerationService;
import com.techcrm.crm.contract.document.PdfConversionService;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.proposal.ProposalDtos.GenerateProposalRequest;
import com.techcrm.crm.proposal.ProposalDtos.GenerateProposalResponse;
import com.techcrm.crm.proposal.ProposalDtos.ProposalLineItemResponse;
import com.techcrm.crm.proposal.ProposalDtos.ProposalResponse;
import com.techcrm.crm.proposal.ProposalDtos.ProposalStatusResponse;
import com.techcrm.crm.proposal.template.ProposalTemplateService;
import com.techcrm.crm.user.User;
import com.techcrm.crm.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The proposal generation flow, start to finish.
 *
 * <pre>
 *   deal -> validate -> assemble -> pick template -> DOCX -> PDF -> store -> row
 * </pre>
 *
 * Not {@code @Transactional}: rendering and converting a document takes tens of
 * seconds and involves an external process, and a transaction spanning that
 * would both hold a connection far too long and make recording a failure
 * impossible. Each database step goes through {@link ProposalRecordService}
 * instead.
 *
 * The document machinery — docx4j rendering, LibreOffice conversion, on-disk
 * storage — is the contract module's, used directly rather than reimplemented.
 * Those three services know nothing about contracts; they take bytes and a
 * document number.
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    private final ProposalRepository proposalRepository;
    private final ProposalLineItemRepository lineItemRepository;
    private final ProposalDataAssembler assembler;
    private final ProposalTemplateService templateService;
    private final DocxGenerationService docxGenerationService;
    private final PdfConversionService pdfConversionService;
    private final DocumentStorageService storageService;
    private final ProposalRecordService recordService;
    private final ProposalDocumentGenerator documentGenerator;
    private final ProposalProperties properties;
    private final AccountRepository accountRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public ProposalService(ProposalRepository proposalRepository,
                           ProposalLineItemRepository lineItemRepository,
                           ProposalDataAssembler assembler,
                           ProposalTemplateService templateService,
                           DocxGenerationService docxGenerationService,
                           PdfConversionService pdfConversionService,
                           DocumentStorageService storageService,
                           ProposalRecordService recordService,
                           ProposalDocumentGenerator documentGenerator,
                           ProposalProperties properties,
                           AccountRepository accountRepository,
                           ContactRepository contactRepository,
                           UserRepository userRepository,
                           AuditLogService auditLogService) {
        this.proposalRepository = proposalRepository;
        this.lineItemRepository = lineItemRepository;
        this.assembler = assembler;
        this.templateService = templateService;
        this.docxGenerationService = docxGenerationService;
        this.pdfConversionService = pdfConversionService;
        this.storageService = storageService;
        this.recordService = recordService;
        this.documentGenerator = documentGenerator;
        this.properties = properties;
        this.accountRepository = accountRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * What {@link #generate} did, which the controller turns into a status code.
     *
     * Three outcomes rather than a boolean, because there are now three: work
     * was started, work was finished, or a retry was absorbed. Carrying it in the
     * status line rather than the body means a caller that only looks at the code
     * still knows whether to poll.
     */
    public enum Disposition {
        /** Reserved and queued; documents are being rendered. 202. */
        ACCEPTED,
        /** Rendered inline and finished. 201. */
        CREATED,
        /** A live proposal already existed and was returned unchanged. 200. */
        EXISTING
    }

    public record GenerationOutcome(GenerateProposalResponse response, Disposition disposition) {

        /** True when this call produced a new proposal, either way round. */
        public boolean created() {
            return disposition != Disposition.EXISTING;
        }
    }

    /**
     * Generates a proposal for a deal.
     *
     * Idempotent by default. The automation platform retries on timeout, and a
     * retry that produced a second quotation for the same opportunity would mean
     * two different prices in the customer's inbox — so an existing live proposal
     * is returned as-is unless the caller explicitly asks for a regeneration.
     */
    public GenerationOutcome generate(AuthenticatedUser caller, GenerateProposalRequest request) {
        Deal deal = assembler.requireDeal(caller, request.dealId());

        Optional<Proposal> existing = proposalRepository.findLiveForDeal(deal.getId(), caller.organizationId());
        boolean regenerate = Boolean.TRUE.equals(request.regenerate());

        // Only a proposal that actually produced a document is a valid answer to
        // a retry. Status on its own is not enough: the row is written before the
        // document is rendered, so a run that died in between leaves a record
        // that would otherwise be handed back as a success for ever, with null
        // document URLs and nothing but a manual regenerate to get past it.
        if (existing.isPresent() && !regenerate && existing.get().hasDocument()) {
            log.info("Returning existing proposal {} for deal {} (idempotent generate)",
                    existing.get().getProposalNumber(), deal.getId());
            return new GenerationOutcome(toGenerateResponse(existing.get()), Disposition.EXISTING);
        }

        if (existing.isPresent()) {
            Proposal previous = existing.get();
            // A signed proposal is the customer's acceptance of a price.
            // Replacing it silently would leave the CRM quoting one figure while
            // the customer has agreed to another.
            if (previous.getStatus() == ProposalStatus.SIGNED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Proposal " + previous.getProposalNumber()
                                + " for this deal is already signed and cannot be regenerated");
            }
            // SENT_FOR_SIGNATURE and VIEWED used to be refused here until the
            // customer decided. That decision arrived through the e-signature
            // integration, which is gone, so it can never arrive now; an explicit
            // regenerate is the only way such a proposal can be replaced.
            if (!previous.hasDocument()) {
                log.warn("Proposal {} for deal {} is {} with no document behind it; "
                                + "superseding the abandoned attempt and generating again",
                        previous.getProposalNumber(), deal.getId(), previous.getStatus());
            }
            recordService.supersede(previous.getId());
            auditLogService.record(caller.organizationId(), caller.userId(), "PROPOSAL_SUPERSEDED",
                    "PROPOSAL", String.valueOf(previous.getId()),
                    "Superseded by regeneration for deal " + deal.getId());
        }

        ProposalAssembly assembly = assembler.assemble(caller, deal, request);
        ProposalType proposalType = templateService.resolve(assembly, request.proposalType());
        String templateKey = templateService.templateKey(proposalType);

        Proposal proposal = recordService.createDraft(caller, assembly, proposalType, templateKey);
        log.info("Drafting proposal {} ({}) for deal {} / {}",
                proposal.getProposalNumber(), proposalType, deal.getId(), deal.getOpportunityId());

        if (properties.isAsyncGeneration()) {
            // Placeholders are built here, on the request thread, while the
            // entities in the assembly are still attached. Only plain values
            // cross to the background task.
            documentGenerator.generate(new ProposalDocumentGenerator.DocumentJob(
                    proposal.getId(),
                    proposal.getProposalNumber(),
                    deal.getId(),
                    caller.organizationId(),
                    caller.userId(),
                    proposalType,
                    ProposalPlaceholders.build(proposal, assembly),
                    toContractLineItems(assembly)));

            log.info("Proposal {} queued for generation; returning 202", proposal.getProposalNumber());
            return new GenerationOutcome(toGenerateResponse(proposal), Disposition.ACCEPTED);
        }

        try {
            Proposal completed = renderAndStore(proposal, assembly, proposalType);
            recordService.mirrorOntoDeal(deal.getId(), completed.getStatus(), null);

            auditLogService.record(caller.organizationId(), caller.userId(), "PROPOSAL_GENERATED",
                    "PROPOSAL", String.valueOf(completed.getId()),
                    "Generated " + proposalType.name() + " for deal " + deal.getId()
                            + " (" + deal.getOpportunityId() + ")");

            return new GenerationOutcome(toGenerateResponse(completed), Disposition.CREATED);

        } catch (ContractDocumentException e) {
            // The cause carries a docx4j trace or a LibreOffice stderr dump.
            // It goes to the log and to the proposal row, never to the caller.
            log.error("Proposal {} could not be produced", proposal.getProposalNumber(), e);
            recordService.markFailed(proposal.getId(), e.getMessage());
            recordService.mirrorOntoDeal(deal.getId(), ProposalStatus.FAILED, null);
            auditLogService.record(caller.organizationId(), caller.userId(), "PROPOSAL_GENERATION_FAILED",
                    "PROPOSAL", String.valueOf(proposal.getId()), e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Proposal " + proposal.getProposalNumber() + " could not be produced. "
                            + "The failure has been recorded against the proposal.");
        }
    }

    /** DOCX, then PDF, then both into the store. The temporary DOCX exists only
     *  because LibreOffice needs a file on disk to open. */
    private Proposal renderAndStore(Proposal proposal, ProposalAssembly assembly, ProposalType proposalType) {
        Map<String, String> placeholders = ProposalPlaceholders.build(proposal, assembly);
        byte[] docx = docxGenerationService.generate(
                templateService.load(proposalType), placeholders, toContractLineItems(assembly));

        StoredDocument storedDocx = storageService.store(
                proposal.getOrganizationId(), proposal.getProposalNumber(), "docx", docx);

        StoredDocument storedPdf = null;
        if (pdfConversionService.isEnabled()) {
            Path temporary = null;
            try {
                temporary = storageService.writeTemporary(proposal.getProposalNumber() + ".docx", docx);
                byte[] pdf = pdfConversionService.convertToPdf(temporary);
                storedPdf = storageService.store(
                        proposal.getOrganizationId(), proposal.getProposalNumber(), "pdf", pdf);
            } finally {
                storageService.deleteTemporary(temporary);
            }
        } else {
            log.warn("PDF conversion is disabled; proposal {} has a DOCX only",
                    proposal.getProposalNumber());
        }

        return recordService.attachDocuments(proposal.getId(), storedDocx, storedPdf);
    }

    /**
     * Adapts proposal lines to the shape the shared renderer expects.
     *
     * {@code DocxGenerationService} clones a marked table row per line item and
     * was written against the contract module's record. Rather than duplicate
     * three hundred lines of OpenXML handling to change the type of one
     * parameter, the lines are mapped across here. The alternative — a shared
     * line-item interface — would put a type in the contract package that exists
     * only to serve this call.
     */
    private List<com.techcrm.crm.contract.ContractAssembly.ResolvedLineItem> toContractLineItems(
            ProposalAssembly assembly) {
        return assembly.lineItems().stream()
                .map(item -> new com.techcrm.crm.contract.ContractAssembly.ResolvedLineItem(
                        item.lineNumber(),
                        item.description(),
                        item.quantity(),
                        item.unitPrice(),
                        item.lineTotal(),
                        item.source() == ProposalLineItem.Source.LEAD_PRODUCT
                                ? com.techcrm.crm.contract.ContractLineItem.Source.LEAD_PRODUCT
                                : com.techcrm.crm.contract.ContractLineItem.Source.DEAL_VALUE))
                .toList();
    }

    /* -------------------------------------------------------------- queries */

    @Transactional(readOnly = true)
    public Proposal require(AuthenticatedUser caller, Long proposalId) {
        return proposalRepository.findByIdAndOrganizationId(proposalId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found"));
    }

    @Transactional(readOnly = true)
    public ProposalResponse get(AuthenticatedUser caller, Long proposalId) {
        return toResponse(require(caller, proposalId));
    }

    @Transactional(readOnly = true)
    public ProposalStatusResponse status(AuthenticatedUser caller, Long proposalId) {
        Proposal proposal = require(caller, proposalId);
        return new ProposalStatusResponse(
                String.valueOf(proposal.getId()),
                String.valueOf(proposal.getDealId()),
                proposal.getStatus().name(),
                proposal.getSentForSignatureAt(),
                proposal.getSignedAt(),
                proposal.getRejectedAt(),
                proposal.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public List<ProposalResponse> list(AuthenticatedUser caller) {
        return proposalRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())
                .stream().map(this::toResponse).toList();
    }

    /* -------------------------------------------------------------- mapping */

    public GenerateProposalResponse toGenerateResponse(Proposal proposal) {
        return new GenerateProposalResponse(
                String.valueOf(proposal.getId()),
                proposal.getProposalNumber(),
                String.valueOf(proposal.getDealId()),
                proposal.getOpportunityId(),
                proposal.getProposalType().name(),
                proposal.getStatus().name(),
                documentUrl(proposal, "pdf", proposal.getPdfPath()),
                documentUrl(proposal, "docx", proposal.getDocxPath()));
    }

    public ProposalResponse toResponse(Proposal proposal) {
        Account account = accountRepository.findById(proposal.getAccountId()).orElse(null);
        Contact contact = proposal.getContactId() == null
                ? null : contactRepository.findById(proposal.getContactId()).orElse(null);
        User owner = proposal.getOwnerId() == null
                ? null : userRepository.findById(proposal.getOwnerId()).orElse(null);

        List<ProposalLineItemResponse> lineItems =
                lineItemRepository.findByProposalIdOrderByLineNumberAsc(proposal.getId())
                        .stream().map(ProposalLineItemResponse::from).toList();

        return new ProposalResponse(
                String.valueOf(proposal.getId()),
                proposal.getProposalNumber(),
                String.valueOf(proposal.getDealId()),
                proposal.getOpportunityId(),
                String.valueOf(proposal.getAccountId()),
                account == null ? null : account.getName(),
                proposal.getContactId() == null ? null : String.valueOf(proposal.getContactId()),
                contact == null ? null : contact.getFullName(),
                contact == null ? proposal.getSignerEmail() : contact.getEmail(),
                proposal.getOwnerId() == null ? null : String.valueOf(proposal.getOwnerId()),
                owner == null ? null : owner.getFullName(),
                proposal.getProposalType().name(),
                proposal.getProposalType().displayName(),
                proposal.getTemplateKey(),
                proposal.getStatus().name(),
                proposal.getFailureReason(),
                proposal.getCurrency(),
                proposal.getTotalAmount(),
                proposal.getValidUntil(),
                proposal.getPaymentTerms(),
                proposal.getDeliveryTimeline(),
                lineItems,
                documentUrl(proposal, "pdf", proposal.getPdfPath()),
                documentUrl(proposal, "docx", proposal.getDocxPath()),
                proposal.getSignerName(),
                proposal.getSignerEmail(),
                proposal.getSentForSignatureAt(),
                proposal.getSignedAt(),
                proposal.getRejectedAt(),
                proposal.getRejectionReason(),
                proposal.getCreatedAt(),
                proposal.getUpdatedAt());
    }

    /**
     * The API path a document is downloadable from — never a filesystem path.
     *
     * Null when the document does not exist, so a caller can tell "not generated
     * yet" from "here it is" without probing the URL. Relative rather than
     * absolute because the backend sits behind whatever host the deployment
     * gives it, and baking one in would break the moment that changes.
     */
    private String documentUrl(Proposal proposal, String extension, String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        return "/api/proposals/" + proposal.getId() + "/document." + extension;
    }
}
