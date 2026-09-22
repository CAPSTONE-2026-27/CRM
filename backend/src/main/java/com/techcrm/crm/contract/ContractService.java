package com.techcrm.crm.contract;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contact.Contact;
import com.techcrm.crm.contact.ContactRepository;
import com.techcrm.crm.contract.ContractDtos.ContractLineItemResponse;
import com.techcrm.crm.contract.ContractDtos.ContractResponse;
import com.techcrm.crm.contract.ContractDtos.ContractStatusResponse;
import com.techcrm.crm.contract.ContractDtos.GenerateContractRequest;
import com.techcrm.crm.contract.ContractDtos.GenerateContractResponse;
import com.techcrm.crm.contract.document.ContractDocumentException;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.document.DocumentStorageService.StoredDocument;
import com.techcrm.crm.contract.document.DocxGenerationService;
import com.techcrm.crm.contract.document.PdfConversionService;
import com.techcrm.crm.contract.template.ContractTemplateService;
import com.techcrm.crm.deal.Deal;
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
 * The contract generation flow, start to finish.
 *
 * <pre>
 *   deal -> validate -> assemble -> pick template -> reserve row  (on the request thread)
 *                                -> DOCX -> PDF -> store         (on a background thread)
 * </pre>
 *
 * The split is at the row, not earlier, and that is the important part.
 * Validation stays synchronous: an unknown deal is still a 404, a wrong stage
 * still a 409, missing customer data still a 400, on the call that caused them.
 * Moving validation into the background would turn every one of those into a
 * FAILED status the caller has to go looking for, and would hand out 202 for
 * deals that were never going to produce a contract.
 *
 * What moved is only the slow half — a docx4j render and a LibreOffice
 * subprocess, together longer than the 30 seconds SAP Build Process Automation
 * waits before abandoning a call. See {@link ContractDocumentGenerator}.
 * {@code contract.async-generation=false} puts it back inline.
 *
 * Not {@code @Transactional}: rendering and converting a document takes seconds
 * and involves an external process, and a transaction spanning that would both
 * hold a connection far too long and make recording a failure impossible. Each
 * database step goes through {@link ContractRecordService} instead.
 */
@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    private final ContractRepository contractRepository;
    private final ContractLineItemRepository lineItemRepository;
    private final ContractDataAssembler assembler;
    private final ContractTemplateService templateService;
    private final DocxGenerationService docxGenerationService;
    private final PdfConversionService pdfConversionService;
    private final DocumentStorageService storageService;
    private final ContractRecordService recordService;
    private final ContractDocumentGenerator documentGenerator;
    private final ContractProperties properties;
    private final AccountRepository accountRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public ContractService(ContractRepository contractRepository,
                           ContractLineItemRepository lineItemRepository,
                           ContractDataAssembler assembler,
                           ContractTemplateService templateService,
                           DocxGenerationService docxGenerationService,
                           PdfConversionService pdfConversionService,
                           DocumentStorageService storageService,
                           ContractRecordService recordService,
                           ContractDocumentGenerator documentGenerator,
                           ContractProperties properties,
                           AccountRepository accountRepository,
                           ContactRepository contactRepository,
                           UserRepository userRepository,
                           AuditLogService auditLogService) {
        this.contractRepository = contractRepository;
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
        /** A live contract already existed and was returned unchanged. 200. */
        EXISTING
    }

    public record GenerationOutcome(GenerateContractResponse response, Disposition disposition) {

        /** True when this call produced a new contract, either way round. */
        public boolean created() {
            return disposition != Disposition.EXISTING;
        }
    }

    /**
     * Generates a contract for a deal.
     *
     * Idempotent by default. The automation platform retries on timeout, and a
     * retry that produced a second signed agreement for the same opportunity
     * would be a commercial problem, not just a duplicate row — so an existing
     * live contract is returned as-is unless the caller explicitly asks for a
     * regeneration.
     */
    public GenerationOutcome generate(AuthenticatedUser caller, GenerateContractRequest request) {
        Deal deal = assembler.requireDeal(caller, request.dealId());

        Optional<Contract> existing = contractRepository.findLiveForDeal(deal.getId(), caller.organizationId());
        boolean regenerate = Boolean.TRUE.equals(request.regenerate());

        // Only a contract that actually produced a document is a valid answer to
        // a retry. Status on its own is not enough: the row is written before
        // the document is rendered, so a run that died in between -- a dropped
        // database connection during the commit is enough -- leaves a record
        // that would otherwise be handed back as a success for ever, with null
        // document URLs and nothing but a manual regenerate to get past it.
        if (existing.isPresent() && !regenerate && existing.get().hasDocument()) {
            log.info("Returning existing contract {} for deal {} (idempotent generate)",
                    existing.get().getContractNumber(), deal.getId());
            return new GenerationOutcome(toGenerateResponse(existing.get()), Disposition.EXISTING);
        }

        if (existing.isPresent()) {
            Contract previous = existing.get();
            // A signed contract is the agreement. Replacing it silently would
            // leave the CRM claiming a document is current when the customer
            // has signed a different one.
            if (previous.getStatus() == ContractStatus.SIGNED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Contract " + previous.getContractNumber()
                                + " for this deal is already signed and cannot be regenerated");
            }
            if (!previous.hasDocument()) {
                log.warn("Contract {} for deal {} is {} with no document behind it; "
                                + "superseding the abandoned attempt and generating again",
                        previous.getContractNumber(), deal.getId(), previous.getStatus());
            }
            recordService.supersede(previous.getId());
            auditLogService.record(caller.organizationId(), caller.userId(), "CONTRACT_SUPERSEDED",
                    "CONTRACT", String.valueOf(previous.getId()),
                    "Superseded by regeneration for deal " + deal.getId());
        }

        ContractAssembly assembly = assembler.assemble(caller, deal, request);
        ContractType contractType = templateService.resolve(assembly, request.contractType());
        String templateKey = templateService.templateKey(contractType);

        Contract contract = recordService.createDraft(caller, assembly, contractType, templateKey);
        log.info("Drafting contract {} ({}) for deal {} / {}",
                contract.getContractNumber(), contractType, deal.getId(), deal.getOpportunityId());

        if (properties.isAsyncGeneration()) {
            // Placeholders are built here, on the request thread, while the
            // entities in the assembly are still attached. Only plain values
            // cross to the background task.
            documentGenerator.generate(new ContractDocumentGenerator.DocumentJob(
                    contract.getId(),
                    contract.getContractNumber(),
                    deal.getId(),
                    caller.organizationId(),
                    caller.userId(),
                    contractType,
                    ContractPlaceholders.build(contract, assembly),
                    assembly.lineItems()));

            log.info("Contract {} queued for generation; returning 202", contract.getContractNumber());
            return new GenerationOutcome(toGenerateResponse(contract), Disposition.ACCEPTED);
        }

        try {
            Contract completed = renderAndStore(contract, assembly, contractType);
            recordService.mirrorOntoDeal(deal.getId(), completed.getStatus(), null);

            auditLogService.record(caller.organizationId(), caller.userId(), "CONTRACT_GENERATED",
                    "CONTRACT", String.valueOf(completed.getId()),
                    "Generated " + contractType.name() + " for deal " + deal.getId()
                            + " (" + deal.getOpportunityId() + ")");

            return new GenerationOutcome(toGenerateResponse(completed), Disposition.CREATED);

        } catch (ContractDocumentException e) {
            // The cause carries a docx4j trace or a LibreOffice stderr dump.
            // It goes to the log and to the contract row, never to the caller.
            log.error("Contract {} could not be produced", contract.getContractNumber(), e);
            recordService.markFailed(contract.getId(), e.getMessage());
            recordService.mirrorOntoDeal(deal.getId(), ContractStatus.FAILED, null);
            auditLogService.record(caller.organizationId(), caller.userId(), "CONTRACT_GENERATION_FAILED",
                    "CONTRACT", String.valueOf(contract.getId()), e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Contract " + contract.getContractNumber() + " could not be produced. "
                            + "The failure has been recorded against the contract.");
        }
    }

    /** DOCX, then PDF, then both into the store. The temporary DOCX exists only
     *  because LibreOffice needs a file on disk to open. */
    private Contract renderAndStore(Contract contract, ContractAssembly assembly, ContractType contractType) {
        Map<String, String> placeholders = ContractPlaceholders.build(contract, assembly);
        byte[] docx = docxGenerationService.generate(
                templateService.load(contractType), placeholders, assembly.lineItems());

        StoredDocument storedDocx = storageService.store(
                contract.getOrganizationId(), contract.getContractNumber(), "docx", docx);

        StoredDocument storedPdf = null;
        if (pdfConversionService.isEnabled()) {
            Path temporary = null;
            try {
                temporary = storageService.writeTemporary(contract.getContractNumber() + ".docx", docx);
                byte[] pdf = pdfConversionService.convertToPdf(temporary);
                storedPdf = storageService.store(
                        contract.getOrganizationId(), contract.getContractNumber(), "pdf", pdf);
            } finally {
                storageService.deleteTemporary(temporary);
            }
        } else {
            log.warn("PDF conversion is disabled; contract {} has a DOCX only",
                    contract.getContractNumber());
        }

        return recordService.attachDocuments(contract.getId(), storedDocx, storedPdf);
    }

    /* ------------------------------------------------------------- queries */

    @Transactional(readOnly = true)
    public Contract require(AuthenticatedUser caller, Long contractId) {
        return contractRepository.findByIdAndOrganizationId(contractId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found"));
    }

    @Transactional(readOnly = true)
    public ContractResponse get(AuthenticatedUser caller, Long contractId) {
        return toResponse(require(caller, contractId));
    }

    @Transactional(readOnly = true)
    public ContractStatusResponse status(AuthenticatedUser caller, Long contractId) {
        Contract contract = require(caller, contractId);
        return new ContractStatusResponse(
                String.valueOf(contract.getId()),
                String.valueOf(contract.getDealId()),
                contract.getStatus().name(),
                contract.getSentAt(),
                contract.getSignedAt(),
                contract.getRejectedAt(),
                contract.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public List<ContractResponse> list(AuthenticatedUser caller) {
        return contractRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())
                .stream().map(this::toResponse).toList();
    }

    /* -------------------------------------------------------------- mapping */

    public GenerateContractResponse toGenerateResponse(Contract contract) {
        return new GenerateContractResponse(
                String.valueOf(contract.getId()),
                contract.getContractNumber(),
                String.valueOf(contract.getDealId()),
                contract.getOpportunityId(),
                contract.getContractType().name(),
                contract.getStatus().name(),
                documentUrl(contract, "pdf", contract.getPdfPath()),
                documentUrl(contract, "docx", contract.getDocxPath()));
    }

    public ContractResponse toResponse(Contract contract) {
        Account account = accountRepository.findById(contract.getAccountId()).orElse(null);
        Contact contact = contract.getContactId() == null
                ? null : contactRepository.findById(contract.getContactId()).orElse(null);
        User owner = contract.getOwnerId() == null
                ? null : userRepository.findById(contract.getOwnerId()).orElse(null);

        List<ContractLineItemResponse> lineItems =
                lineItemRepository.findByContractIdOrderByLineNumberAsc(contract.getId())
                        .stream().map(ContractLineItemResponse::from).toList();

        return new ContractResponse(
                String.valueOf(contract.getId()),
                contract.getContractNumber(),
                String.valueOf(contract.getDealId()),
                contract.getOpportunityId(),
                String.valueOf(contract.getAccountId()),
                account == null ? null : account.getName(),
                contract.getContactId() == null ? null : String.valueOf(contract.getContactId()),
                contact == null ? null : contact.getFullName(),
                contact == null ? contract.getSignerEmail() : contact.getEmail(),
                contract.getOwnerId() == null ? null : String.valueOf(contract.getOwnerId()),
                owner == null ? null : owner.getFullName(),
                contract.getContractType().name(),
                contract.getContractType().displayName(),
                contract.getTemplateKey(),
                contract.getStatus().name(),
                contract.getFailureReason(),
                contract.getCurrency(),
                contract.getTotalAmount(),
                contract.getStartDate(),
                contract.getEndDate(),
                contract.getPaymentTerms(),
                contract.getDeliveryTimeline(),
                lineItems,
                documentUrl(contract, "pdf", contract.getPdfPath()),
                documentUrl(contract, "docx", contract.getDocxPath()),
                contract.getSignerName(),
                contract.getSignerEmail(),
                contract.getSentAt(),
                contract.getSignedAt(),
                contract.getRejectedAt(),
                contract.getRejectionReason(),
                contract.getCreatedAt(),
                contract.getUpdatedAt());
    }

    /**
     * The API path a document is downloadable from — never a filesystem path.
     *
     * Null when the document does not exist, so a caller can tell "not generated
     * yet" from "here it is" without probing the URL. Relative rather than
     * absolute because the backend sits behind whatever host the deployment
     * gives it, and baking one in would break the moment that changes.
     */
    private String documentUrl(Contract contract, String extension, String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        return "/api/contracts/" + contract.getId() + "/document." + extension;
    }
}
