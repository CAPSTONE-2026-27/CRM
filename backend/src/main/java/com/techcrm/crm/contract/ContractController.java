package com.techcrm.crm.contract;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contract.ContractDtos.ContractResponse;
import com.techcrm.crm.contract.ContractDtos.ContractStatusResponse;
import com.techcrm.crm.contract.ContractDtos.GenerateContractRequest;
import com.techcrm.crm.contract.ContractDtos.GenerateContractResponse;
import com.techcrm.crm.contract.ContractDtos.ContractEmailResponse;
import com.techcrm.crm.contract.ContractDtos.SendContractEmailRequest;
import com.techcrm.crm.contract.ContractService.GenerationOutcome;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.email.ContractEmailService;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Contract generation, and emailing the contract to the customer.
 *
 * Every endpoint here is authenticated through the CRM's existing JWT filter and
 * scoped to the caller's organization.
 *
 * The SAP Build Process Automation bot calls generate, polls status until the
 * contract is GENERATED, then calls send-email.
 */
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ContractService contractService;
    private final DocumentStorageService storageService;
    private final ContractEmailService emailService;

    public ContractController(ContractService contractService,
                              DocumentStorageService storageService,
                              ContractEmailService emailService) {
        this.contractService = contractService;
        this.storageService = storageService;
        this.emailService = emailService;
    }

    /**
     * Generates a contract for an existing CRM deal.
     *
     * <b>202</b> — accepted: the contract is reserved and its documents are being
     * rendered. {@code status} is {@code DRAFTING} and the document URLs are
     * null. Poll {@code GET /{contractId}/status} until it reads GENERATED (or
     * FAILED). This is the default, and answers in about a second, because SAP
     * Build Process Automation abandons an HTTP call at 30 seconds and a cold
     * generation takes longer than that.
     *
     * <b>201</b> — created: the same thing, but rendered inline and already
     * finished. Only when {@code contract.async-generation=false}.
     *
     * <b>200</b> — an existing live contract was returned because this was a
     * retry.
     *
     * The body is identical in all three cases, so a caller that ignores the
     * status code still gets a usable answer; the distinction is there for one
     * that does not.
     *
     * <b>404</b> unknown deal, <b>409</b> wrong stage or an already-signed
     * contract, <b>400</b> incomplete contract data, <b>500</b> document
     * generation or conversion failure (synchronous mode only — asynchronously,
     * a failure lands on the contract's status instead).
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateContractResponse> generate(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Valid @RequestBody GenerateContractRequest request) {

        GenerationOutcome outcome = contractService.generate(caller, request);
        HttpStatus status = switch (outcome.disposition()) {
            case ACCEPTED -> HttpStatus.ACCEPTED;
            case CREATED -> HttpStatus.CREATED;
            case EXISTING -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(outcome.response());
    }

    @GetMapping
    public List<ContractResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return contractService.list(caller);
    }

    @GetMapping("/{contractId}")
    public ContractResponse get(@AuthenticationPrincipal AuthenticatedUser caller,
                                @PathVariable Long contractId) {
        return contractService.get(caller, contractId);
    }

    /** The endpoint the automation platform polls while a contract is generating. */
    @GetMapping("/{contractId}/status")
    public ContractStatusResponse status(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable Long contractId) {
        return contractService.status(caller, contractId);
    }

    /**
     * Emails the contract PDF to the customer for review, and marks it SENT.
     *
     * Called by the automation platform once the contract is GENERATED. The
     * platform decides when; the backend sends, because it holds the PDF and SAP
     * Build Process Automation cannot carry one between steps. The wording comes
     * from the editable files in {@code mailjet.templates.location}.
     *
     * The body is optional: {@code recipientEmail} and {@code recipientName}
     * override the contact, {@code resend: true} emails again.
     *
     * <b>200</b> {@code SENT}, or {@code ALREADY_SENT} when a retry found it
     * already emailed and nothing was sent twice. <b>404</b> no such contract,
     * <b>409</b> no PDF, or a contract not ready or no longer current,
     * <b>400</b> no recipient address, <b>502</b> Mailjet refused it (the reason
     * is in the audit log), <b>503</b> Mailjet is not configured.
     */
    @PostMapping("/{contractId}/send-email")
    public ContractEmailResponse sendEmail(@AuthenticationPrincipal AuthenticatedUser caller,
                                           @PathVariable Long contractId,
                                           @Valid @RequestBody(required = false) SendContractEmailRequest request) {
        return emailService.send(caller, contractId, request);
    }

    /* ------------------------------------------------------------ downloads */

    /**
     * The generated PDF.
     *
     * Served through the API rather than from a static path, so the same JWT and
     * organization scoping that guards every other endpoint guards the documents
     * too. A contract PDF names a customer, a price and a signatory; a
     * guessable public URL for it would be a data leak in a CRM that has none
     * elsewhere.
     */
    @GetMapping("/{contractId}/document.pdf")
    public ResponseEntity<Resource> downloadPdf(@AuthenticationPrincipal AuthenticatedUser caller,
                                                @PathVariable Long contractId) {
        Contract contract = contractService.require(caller, contractId);
        return download(contract, contract.getPdfPath(), "pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping("/{contractId}/document.docx")
    public ResponseEntity<Resource> downloadDocx(@AuthenticationPrincipal AuthenticatedUser caller,
                                                 @PathVariable Long contractId) {
        Contract contract = contractService.require(caller, contractId);
        return download(contract, contract.getDocxPath(), "docx", DOCX);
    }

    private ResponseEntity<Resource> download(Contract contract, String storedPath,
                                              String extension, MediaType mediaType) {
        if (storedPath == null || !storageService.exists(storedPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Contract " + contract.getContractNumber() + " has no generated ."
                            + extension + " document");
        }

        byte[] content = storageService.read(storedPath);

        // The contract number, not the stored file name: the stored name carries
        // a generation timestamp that means nothing to whoever downloads it.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(contract.getContractNumber() + "." + extension, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // A contract can be regenerated at the same URL, and a cached
                // copy of the superseded one is worse than a second fetch.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ByteArrayResource(content));
    }
}
