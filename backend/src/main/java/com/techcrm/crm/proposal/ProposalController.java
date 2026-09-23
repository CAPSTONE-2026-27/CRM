package com.techcrm.crm.proposal;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.proposal.ProposalDtos.GenerateProposalRequest;
import com.techcrm.crm.proposal.ProposalDtos.GenerateProposalResponse;
import com.techcrm.crm.proposal.ProposalDtos.ProposalEmailResponse;
import com.techcrm.crm.proposal.ProposalDtos.ProposalResponse;
import com.techcrm.crm.proposal.ProposalDtos.ProposalStatusResponse;
import com.techcrm.crm.proposal.ProposalDtos.SendProposalEmailRequest;
import com.techcrm.crm.proposal.email.ProposalEmailService;
import com.techcrm.crm.proposal.ProposalService.GenerationOutcome;
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
 * Proposal generation, and emailing the proposal to the customer.
 *
 * Every endpoint here is authenticated through the CRM's existing JWT filter and
 * scoped to the caller's organization.
 *
 * The generate, status and download endpoints are the ones the SAP Build Process
 * Automation bot uses.
 */
@RestController
@RequestMapping("/api/proposals")
public class ProposalController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ProposalService proposalService;
    private final DocumentStorageService storageService;
    private final ProposalEmailService emailService;

    public ProposalController(ProposalService proposalService,
                              DocumentStorageService storageService,
                              ProposalEmailService emailService) {
        this.proposalService = proposalService;
        this.storageService = storageService;
        this.emailService = emailService;
    }

    /**
     * Generates a proposal for an existing CRM deal.
     *
     * <b>202</b> — accepted: the proposal is reserved and its documents are
     * being rendered. {@code status} is {@code GENERATING} and the document URLs
     * are null. Poll {@code GET /{proposalId}/status} until it reads GENERATED
     * (or FAILED). This is the default, and answers in about a second, because
     * SAP Build Process Automation abandons an HTTP call at 30 seconds and a
     * cold generation takes longer than that.
     *
     * <b>201</b> — created: the same thing, but rendered inline and already
     * finished. Only when {@code proposal.async-generation=false}.
     *
     * <b>200</b> — an existing live proposal was returned because this was a
     * retry.
     *
     * The body is identical in all three cases, so a caller that ignores the
     * status code still gets a usable answer; the distinction is there for one
     * that does not.
     *
     * <b>404</b> unknown deal, <b>409</b> wrong stage or a proposal already with
     * the customer, <b>400</b> incomplete proposal data, <b>500</b> document
     * generation or conversion failure (synchronous mode only — asynchronously,
     * a failure lands on the proposal's status instead).
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateProposalResponse> generate(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Valid @RequestBody GenerateProposalRequest request) {

        GenerationOutcome outcome = proposalService.generate(caller, request);
        HttpStatus status = switch (outcome.disposition()) {
            case ACCEPTED -> HttpStatus.ACCEPTED;
            case CREATED -> HttpStatus.CREATED;
            case EXISTING -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(outcome.response());
    }

    @GetMapping
    public List<ProposalResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return proposalService.list(caller);
    }

    @GetMapping("/{proposalId}")
    public ProposalResponse get(@AuthenticationPrincipal AuthenticatedUser caller,
                                @PathVariable Long proposalId) {
        return proposalService.get(caller, proposalId);
    }

    /** The endpoint the automation platform polls while a proposal is generating. while a proposal is generating. */
    @GetMapping("/{proposalId}/status")
    public ProposalStatusResponse status(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable Long proposalId) {
        return proposalService.status(caller, proposalId);
    }

    /**
     * Emails the proposal PDF to the customer, and marks it SENT.
     *
     * Called by the automation platform once the proposal is DRAFT. The platform
     * decides when; the backend sends, because it holds the PDF and SAP Build
     * Process Automation cannot carry one between steps. The wording comes from
     * the editable files in {@code mailjet.templates.location}.
     *
     * The body is optional: {@code recipientEmail} and {@code recipientName}
     * override the contact, {@code resend: true} emails again.
     *
     * <b>200</b> {@code SENT}, or {@code ALREADY_SENT} when a retry found it
     * already emailed and nothing was sent twice. <b>404</b> no such proposal,
     * <b>409</b> no PDF, or a proposal not ready or no longer current,
     * <b>400</b> no recipient address, <b>502</b> Mailjet refused it (the reason
     * is in the audit log), <b>503</b> Mailjet is not configured.
     */
    @PostMapping("/{proposalId}/send-email")
    public ProposalEmailResponse sendEmail(@AuthenticationPrincipal AuthenticatedUser caller,
                                           @PathVariable Long proposalId,
                                           @Valid @RequestBody(required = false) SendProposalEmailRequest request) {
        return emailService.send(caller, proposalId, request);
    }

    /* ------------------------------------------------------------ downloads */

    /**
     * The generated PDF.
     *
     * Served through the API rather than from a static path, so the same JWT and
     * organization scoping that guards every other endpoint guards the documents
     * too. A proposal PDF names a customer and a price; a guessable public URL
     * for it would be a data leak in a CRM that has none elsewhere.
     */
    @GetMapping("/{proposalId}/document.pdf")
    public ResponseEntity<Resource> downloadPdf(@AuthenticationPrincipal AuthenticatedUser caller,
                                                @PathVariable Long proposalId) {
        Proposal proposal = proposalService.require(caller, proposalId);
        return download(proposal, proposal.getPdfPath(), "pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping("/{proposalId}/document.docx")
    public ResponseEntity<Resource> downloadDocx(@AuthenticationPrincipal AuthenticatedUser caller,
                                                 @PathVariable Long proposalId) {
        Proposal proposal = proposalService.require(caller, proposalId);
        return download(proposal, proposal.getDocxPath(), "docx", DOCX);
    }

    private ResponseEntity<Resource> download(Proposal proposal, String storedPath,
                                              String extension, MediaType mediaType) {
        if (storedPath == null || !storageService.exists(storedPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Proposal " + proposal.getProposalNumber() + " has no generated ."
                            + extension + " document");
        }

        byte[] content = storageService.read(storedPath);

        // The proposal number, not the stored file name: the stored name carries
        // a generation timestamp that means nothing to whoever downloads it.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(proposal.getProposalNumber() + "." + extension, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // A proposal can be regenerated at the same URL, and a cached
                // copy of the superseded one is worse than a second fetch.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ByteArrayResource(content));
    }
}
