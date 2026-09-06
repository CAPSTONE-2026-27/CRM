package com.techcrm.crm.contract.signature;

import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contract.Contract;
import com.techcrm.crm.contract.ContractDtos.SendForSignatureRequest;
import com.techcrm.crm.contract.ContractDtos.SendForSignatureResponse;
import com.techcrm.crm.contract.ContractRecordService;
import com.techcrm.crm.contract.ContractRepository;
import com.techcrm.crm.contract.ContractStatus;
import com.techcrm.crm.contract.document.DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;


/**
 * Hands a generated contract to Documenso for signature.
 *
 * Idempotent in the same spirit as generation: a contract that has already been
 * sent is not sent again, because a second Documenso document would give the
 * customer two links to the same agreement and only one of them would report
 * back. The existing signing details are returned instead.
 */
@Service
public class ContractSignatureService {

    private static final Logger log = LoggerFactory.getLogger(ContractSignatureService.class);

    private final ContractRepository contractRepository;
    private final DocumensoClient documensoClient;
    private final DocumentStorageService storageService;
    private final ContractRecordService recordService;
    private final AuditLogService auditLogService;

    public ContractSignatureService(ContractRepository contractRepository,
                                    DocumensoClient documensoClient,
                                    DocumentStorageService storageService,
                                    ContractRecordService recordService,
                                    AuditLogService auditLogService) {
        this.contractRepository = contractRepository;
        this.documensoClient = documensoClient;
        this.storageService = storageService;
        this.recordService = recordService;
        this.auditLogService = auditLogService;
    }

    public SendForSignatureResponse sendForSignature(AuthenticatedUser caller, SendForSignatureRequest request) {
        Contract contract = contractRepository
                .findByIdAndOrganizationId(request.contractId(), caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found"));

        if (contract.getDocumensoDocumentId() != null) {
            log.info("Contract {} is already with Documenso as document {}",
                    contract.getContractNumber(), contract.getDocumensoDocumentId());
            return response(contract);
        }

        if (contract.getStatus() != ContractStatus.GENERATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Contract " + contract.getContractNumber() + " is " + contract.getStatus()
                            + " and cannot be sent for signature");
        }

        // The customer signs the PDF, so its absence is fatal here even though
        // the contract generated successfully as a DOCX.
        if (contract.getPdfPath() == null || !storageService.exists(contract.getPdfPath())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Contract " + contract.getContractNumber() + " has no generated PDF to send. "
                            + "Regenerate it with PDF conversion enabled.");
        }

        if (!documensoClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Electronic signature is not configured on this server");
        }

        String recipientName = firstNonBlank(request.recipientName(), contract.getSignerName());
        String recipientEmail = firstNonBlank(request.recipientEmail(), contract.getSignerEmail());
        if (recipientEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No email address to send contract " + contract.getContractNumber() + " to");
        }

        DocumensoClient.SignatureResult result;
        try {
            result = documensoClient.send(new DocumensoClient.SignatureRequest(
                    contract.getContractNumber() + " - " + contract.getContractType().displayName(),
                    contract.getContractNumber(),
                    recipientName,
                    recipientEmail,
                    "Please sign " + contract.getContractNumber(),
                    "Your agreement is ready for signature. Please review and sign it at your convenience.",
                    storageService.read(contract.getPdfPath())));
        } catch (DocumensoException e) {
            // Documenso's own message can quote request internals; it belongs in
            // the log, not in a CRM response.
            log.error("Documenso rejected contract {}", contract.getContractNumber(), e);
            auditLogService.record(caller.organizationId(), caller.userId(), "CONTRACT_SEND_FAILED",
                    "CONTRACT", String.valueOf(contract.getId()), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The signature provider could not accept contract " + contract.getContractNumber());
        }

        Contract sent = recordService.markSent(contract.getId(), result.documentId(), result.recipientId(),
                result.signUrl(), recipientName, recipientEmail);
        recordService.mirrorOntoDeal(sent.getDealId(), ContractStatus.SENT, null);

        auditLogService.record(caller.organizationId(), caller.userId(), "CONTRACT_SENT",
                "CONTRACT", String.valueOf(sent.getId()),
                "Sent to " + recipientEmail + " as Documenso document " + result.documentId());

        return response(sent);
    }

    private SendForSignatureResponse response(Contract contract) {
        return new SendForSignatureResponse(
                String.valueOf(contract.getId()),
                contract.getStatus().name(),
                contract.getSignUrl(),
                contract.getDocumensoDocumentId());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
