package com.techcrm.crm.contract.email;

import com.techcrm.crm.audit.AuditLog;
import com.techcrm.crm.audit.AuditLogRepository;
import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contract.Contract;
import com.techcrm.crm.contract.ContractDtos.ContractEmailResponse;
import com.techcrm.crm.contract.ContractDtos.SendContractEmailRequest;
import com.techcrm.crm.contract.ContractRecordService;
import com.techcrm.crm.contract.ContractRepository;
import com.techcrm.crm.contract.ContractStatus;
import com.techcrm.crm.contract.document.DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emails a contract to its customer for review, with the PDF attached, and marks
 * the contract SENT.
 *
 * Called by the automation platform once the contract is GENERATED. The
 * platform decides when; this does the sending, because the PDF is far larger
 * than anything SAP Build Process Automation will carry between steps.
 *
 * <b>Idempotent.</b> The platform retries a step whose response it did not get,
 * and a timeout after Mailjet accepted the message would otherwise email the
 * customer twice. A contract that has already been emailed gets the earlier
 * result back unless {@code resend} is set. The record of that send is its audit
 * entry, so no schema change was needed for it.
 */
@Service
public class ContractEmailService {

    private static final Logger log = LoggerFactory.getLogger(ContractEmailService.class);

    static final String EMAILED = "CONTRACT_EMAILED";
    static final String EMAIL_FAILED = "CONTRACT_EMAIL_FAILED";
    private static final String ENTITY = "CONTRACT";

    /** A finished, current document the customer has not already decided on.
     *  SENT and VIEWED so {@code resend} works. */
    private static final Set<ContractStatus> EMAILABLE = EnumSet.of(
            ContractStatus.GENERATED, ContractStatus.SENT, ContractStatus.VIEWED);

    private static final Pattern DETAIL_FIELD = Pattern.compile("(\\w+)=([^;]*)");

    private final ContractRepository contractRepository;
    private final ContractRecordService recordService;
    private final DocumentStorageService storageService;
    private final ContractEmailTemplates templates;
    private final MailjetClient mailjetClient;
    private final MailjetProperties properties;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;

    public ContractEmailService(ContractRepository contractRepository,
                                ContractRecordService recordService,
                                DocumentStorageService storageService,
                                ContractEmailTemplates templates,
                                MailjetClient mailjetClient,
                                MailjetProperties properties,
                                AuditLogService auditLogService,
                                AuditLogRepository auditLogRepository) {
        this.contractRepository = contractRepository;
        this.recordService = recordService;
        this.storageService = storageService;
        this.templates = templates;
        this.mailjetClient = mailjetClient;
        this.properties = properties;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
    }

    public ContractEmailResponse send(AuthenticatedUser caller, Long contractId, SendContractEmailRequest request) {
        SendContractEmailRequest options = request != null ? request : new SendContractEmailRequest(null, null, null);

        Contract contract = contractRepository.findByIdAndOrganizationId(contractId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found"));

        if (!Boolean.TRUE.equals(options.resend())) {
            Optional<AuditLog> previous = auditLogRepository
                    .findFirstByOrganizationIdAndEntityTypeAndEntityIdAndActionOrderByOccurredAtDesc(
                            caller.organizationId(), ENTITY, String.valueOf(contract.getId()), EMAILED);
            if (previous.isPresent()) {
                log.info("Contract {} was already emailed; returning the earlier result", contract.getContractNumber());
                return alreadySent(contract, previous.get());
            }
        }

        if (!EMAILABLE.contains(contract.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Contract " + contract.getContractNumber() + " is " + contract.getStatus()
                            + " and cannot be emailed to the customer"
                            + (contract.getStatus() == ContractStatus.DRAFTING ? " yet - it is still generating" : ""));
        }
        if (contract.getPdfPath() == null || !storageService.exists(contract.getPdfPath())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Contract " + contract.getContractNumber() + " has no generated PDF to attach");
        }

        String recipientEmail = firstNonBlank(options.recipientEmail(), contract.getSignerEmail());
        String recipientName = firstNonBlank(options.recipientName(), contract.getSignerName());
        if (recipientEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No email address to send contract " + contract.getContractNumber() + " to");
        }

        if (!mailjetClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery is not configured on this server");
        }

        ContractEmailTemplates.Rendered email = templates.render(values(contract, recipientName));
        byte[] pdf = storageService.read(contract.getPdfPath());

        String messageId;
        try {
            messageId = mailjetClient.send(new MailjetClient.Email(
                    recipientEmail, recipientName,
                    email.subject(), email.text(), email.html(),
                    contract.getContractNumber(),
                    contract.getContractNumber() + ".pdf",
                    pdf));
        } catch (MailjetException e) {
            log.error("Mailjet rejected the email for contract {}", contract.getContractNumber(), e);
            auditLogService.record(caller.organizationId(), caller.userId(), EMAIL_FAILED,
                    ENTITY, String.valueOf(contract.getId()), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The email provider could not send contract " + contract.getContractNumber());
        }

        // Recorded before the status update: the email is already with the
        // customer, and this entry is what stops a retry from sending it again.
        auditLogService.record(caller.organizationId(), caller.userId(), EMAILED,
                ENTITY, String.valueOf(contract.getId()),
                "sentTo=" + recipientEmail + ";mailjetMessageId=" + messageId + ";attachmentBytes=" + pdf.length);
        log.info("Contract {} emailed to {} (Mailjet {}, {} byte attachment)",
                contract.getContractNumber(), recipientEmail, messageId, pdf.length);

        try {
            Contract sent = recordService.markEmailed(contract.getId());
            recordService.mirrorOntoDeal(sent.getDealId(), ContractStatus.SENT, null);
        } catch (RuntimeException e) {
            // The customer has the email. Failing the call now would make the
            // automation platform treat a delivered contract as undelivered.
            log.error("Contract {} was emailed but its SENT status could not be recorded",
                    contract.getContractNumber(), e);
        }

        return new ContractEmailResponse(
                String.valueOf(contract.getId()), contract.getContractNumber(), "SENT",
                recipientEmail, messageId, pdf.length, OffsetDateTime.now());
    }

    /** Everything a template may use. Adding a placeholder is one line here. */
    private Map<String, String> values(Contract contract, String recipientName) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("signerName", recipientName == null ? "" : recipientName);
        values.put("contractNumber", contract.getContractNumber());
        values.put("contractTitle", contract.getContractType().displayName());
        values.put("senderName", properties.getFromName());
        values.put("senderEmail", properties.getFromEmail());
        return values;
    }

    private static ContractEmailResponse alreadySent(Contract contract, AuditLog entry) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = DETAIL_FIELD.matcher(entry.getDetail() == null ? "" : entry.getDetail());
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2));
        }
        Integer bytes = null;
        try {
            bytes = Integer.valueOf(fields.get("attachmentBytes"));
        } catch (NumberFormatException ignored) {
            // An entry written in another format still answers "already sent".
        }
        return new ContractEmailResponse(
                String.valueOf(contract.getId()), contract.getContractNumber(), "ALREADY_SENT",
                fields.get("sentTo"), fields.get("mailjetMessageId"), bytes, entry.getOccurredAt());
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
