package com.techcrm.crm.contract;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contract.ContractDtos.ContractResponse;
import com.techcrm.crm.contract.ContractDtos.ContractStatusResponse;
import com.techcrm.crm.contract.ContractDtos.GenerateContractRequest;
import com.techcrm.crm.contract.ContractDtos.GenerateContractResponse;
import com.techcrm.crm.contract.ContractDtos.SendForSignatureRequest;
import com.techcrm.crm.contract.ContractDtos.SendForSignatureResponse;
import com.techcrm.crm.contract.ContractDtos.WebhookAck;
import com.techcrm.crm.contract.ContractService.GenerationOutcome;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.signature.ContractSignatureService;
import com.techcrm.crm.contract.signature.ContractWebhookService;
import com.techcrm.crm.contract.signature.DocumensoProperties;
import jakarta.servlet.http.HttpServletRequest;
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
 * Contract generation and signature.
 *
 * Every endpoint here is authenticated through the CRM's existing JWT filter and
 * scoped to the caller's organization — except {@code /sign-callback}, which
 * Documenso calls with no CRM identity and which is protected by a shared secret
 * instead. That one exception is declared in {@code SecurityConfig}; nothing
 * else about the security model changes for this module.
 *
 * The generate, status and download endpoints are the ones the SAP Build Process
 * Automation bot uses.
 */
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ContractService contractService;
    private final ContractSignatureService signatureService;
    private final ContractWebhookService webhookService;
    private final DocumentStorageService storageService;
    private final DocumensoProperties documensoProperties;

    public ContractController(ContractService contractService,
                              ContractSignatureService signatureService,
                              ContractWebhookService webhookService,
                              DocumentStorageService storageService,
                              DocumensoProperties documensoProperties) {
        this.contractService = contractService;
        this.signatureService = signatureService;
        this.webhookService = webhookService;
        this.storageService = storageService;
        this.documensoProperties = documensoProperties;
    }

    /**
     * Generates a contract for an existing CRM deal.
     *
     * <b>201</b> when a contract was created, <b>200</b> when an existing live
     * contract was returned because this was a retry. The body is identical
     * either way, so a caller that ignores the status code still gets a usable
     * answer — the distinction is there for one that does not.
     *
     * <b>404</b> unknown deal, <b>409</b> wrong stage or an already-signed
     * contract, <b>400</b> incomplete contract data, <b>500</b> document
     * generation or conversion failure.
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateContractResponse> generate(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Valid @RequestBody GenerateContractRequest request) {

        GenerationOutcome outcome = contractService.generate(caller, request);
        return ResponseEntity
                .status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.response());
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

    /** The endpoint the automation platform polls while it waits for a signature. */
    @GetMapping("/{contractId}/status")
    public ContractStatusResponse status(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable Long contractId) {
        return contractService.status(caller, contractId);
    }

    @PostMapping("/send-for-signature")
    public SendForSignatureResponse sendForSignature(@AuthenticationPrincipal AuthenticatedUser caller,
                                                     @Valid @RequestBody SendForSignatureRequest request) {
        return signatureService.sendForSignature(caller, request);
    }

    /**
     * Documenso's signature callback.
     *
     * The body is taken as a String, not bound to a DTO: the redelivery digest
     * has to be computed over exactly the bytes Documenso sent.
     *
     * Always answers with a body Documenso can log — {@code processed} or
     * {@code duplicate} — so an operator reading its delivery history can tell a
     * retry that was absorbed from one that did nothing.
     */
    @PostMapping(value = "/sign-callback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public WebhookAck signCallback(@RequestBody String rawBody, HttpServletRequest request) {
        String secret = request.getHeader(documensoProperties.getWebhook().getSecretHeader());
        return webhookService.handle(rawBody, secret);
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
