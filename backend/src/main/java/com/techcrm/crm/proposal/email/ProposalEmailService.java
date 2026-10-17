package com.techcrm.crm.proposal.email;

import com.techcrm.crm.audit.AuditLog;
import com.techcrm.crm.audit.AuditLogRepository;
import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.mail.EmailDeliveryException;
import com.techcrm.crm.mail.EmailSender;
import com.techcrm.crm.mail.OutboundEmail;
import com.techcrm.crm.proposal.Proposal;
import com.techcrm.crm.proposal.ProposalDtos.ProposalEmailResponse;
import com.techcrm.crm.proposal.ProposalDtos.SendProposalEmailRequest;
import com.techcrm.crm.proposal.ProposalRecordService;
import com.techcrm.crm.proposal.ProposalRepository;
import com.techcrm.crm.proposal.ProposalStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emails a proposal to its customer, with the PDF attached, and marks the
 * proposal SENT.
 *
 * The mirror of {@link com.techcrm.crm.contract.email.ContractEmailService},
 * and deliberately so: the automation platform drives both the same way, and a
 * second shape for the same operation would mean two bots that look alike but
 * behave differently under retry.
 *
 * The {@link EmailSender} and the document store are shared with the contract
 * module rather than duplicated — neither is contract-specific, and a second
 * sender would mean two places to rotate a credential. Only the wording differs,
 * which is why {@link ProposalEmailTemplates} is its own class.
 *
 * <b>Idempotent.</b> The platform retries a step whose response it did not get,
 * and a timeout after the provider accepted the message would otherwise email
 * the customer twice. A proposal already emailed gets the earlier result back unless
 * {@code resend} is set. The record of that send is its audit entry, so no
 * schema change was needed for it.
 */
@Service
public class ProposalEmailService {

    private static final Logger log = LoggerFactory.getLogger(ProposalEmailService.class);

    static final String EMAILED = "PROPOSAL_EMAILED";
    static final String EMAIL_FAILED = "PROPOSAL_EMAIL_FAILED";
    private static final String ENTITY = "PROPOSAL";

    /** A finished, current document the customer has not already decided on.
     *  SENT and VIEWED so {@code resend} works. */
    private static final Set<ProposalStatus> EMAILABLE = EnumSet.of(
            ProposalStatus.GENERATED, ProposalStatus.SENT, ProposalStatus.VIEWED);

    private static final Pattern DETAIL_FIELD = Pattern.compile("(\\w+)=([^;]*)");

    private static final DateTimeFormatter VALID_UNTIL = DateTimeFormatter.ofPattern("d MMMM yyyy");

    private final ProposalRepository proposalRepository;
    private final ProposalRecordService recordService;
    private final DocumentStorageService storageService;
    private final ProposalEmailTemplates templates;
    private final EmailSender emailSender;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;

    public ProposalEmailService(ProposalRepository proposalRepository,
                                ProposalRecordService recordService,
                                DocumentStorageService storageService,
                                ProposalEmailTemplates templates,
                                EmailSender emailSender,
                                AuditLogService auditLogService,
                                AuditLogRepository auditLogRepository) {
        this.proposalRepository = proposalRepository;
        this.recordService = recordService;
        this.storageService = storageService;
        this.templates = templates;
        this.emailSender = emailSender;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
    }

    public ProposalEmailResponse send(AuthenticatedUser caller, Long proposalId,
                                      SendProposalEmailRequest request) {
        SendProposalEmailRequest options =
                request != null ? request : new SendProposalEmailRequest(null, null, null);

        Proposal proposal = proposalRepository.findByIdAndOrganizationId(proposalId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found"));

        if (!Boolean.TRUE.equals(options.resend())) {
            Optional<AuditLog> previous = auditLogRepository
                    .findFirstByOrganizationIdAndEntityTypeAndEntityIdAndActionOrderByOccurredAtDesc(
                            caller.organizationId(), ENTITY, String.valueOf(proposal.getId()), EMAILED);
            if (previous.isPresent()) {
                log.info("Proposal {} was already emailed; returning the earlier result",
                        proposal.getProposalNumber());
                return alreadySent(proposal, previous.get());
            }
        }

        if (!EMAILABLE.contains(proposal.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Proposal " + proposal.getProposalNumber() + " is " + proposal.getStatus()
                            + " and cannot be emailed to the customer"
                            + (proposal.getStatus() == ProposalStatus.GENERATING
                                    ? " yet - it is still generating" : ""));
        }
        if (proposal.getPdfPath() == null || !storageService.exists(proposal.getPdfPath())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Proposal " + proposal.getProposalNumber() + " has no generated PDF to attach");
        }

        String recipientEmail = firstNonBlank(options.recipientEmail(), proposal.getSignerEmail());
        String recipientName = firstNonBlank(options.recipientName(), proposal.getSignerName());
        if (recipientEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No email address to send proposal " + proposal.getProposalNumber() + " to");
        }

        if (!emailSender.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery is not configured on this server");
        }

        ProposalEmailTemplates.Rendered email = templates.render(values(proposal, recipientName));
        byte[] pdf = storageService.read(proposal.getPdfPath());

        String messageId;
        try {
            messageId = emailSender.send(new OutboundEmail(
                    recipientEmail, recipientName,
                    email.subject(), email.text(), email.html(),
                    proposal.getProposalNumber(),
                    proposal.getProposalNumber() + ".pdf",
                    pdf));
        } catch (EmailDeliveryException e) {
            log.error("{} rejected the email for proposal {}", emailSender.provider(),
                    proposal.getProposalNumber(), e);
            auditLogService.record(caller.organizationId(), caller.userId(), EMAIL_FAILED,
                    ENTITY, String.valueOf(proposal.getId()), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The email provider could not send proposal " + proposal.getProposalNumber());
        }

        // Recorded before the status update: the email is already with the
        // customer, and this entry is what stops a retry from sending it again.
        auditLogService.record(caller.organizationId(), caller.userId(), EMAILED,
                ENTITY, String.valueOf(proposal.getId()),
                "sentTo=" + recipientEmail + ";provider=" + emailSender.provider()
                        + ";messageId=" + messageId + ";attachmentBytes=" + pdf.length);
        log.info("Proposal {} emailed to {} ({} {}, {} byte attachment)",
                proposal.getProposalNumber(), recipientEmail, emailSender.provider(), messageId, pdf.length);

        try {
            Proposal sent = recordService.markEmailed(proposal.getId());
            recordService.mirrorOntoDeal(sent.getDealId(), ProposalStatus.SENT, null);
        } catch (RuntimeException e) {
            // The customer has the email. Failing the call now would make the
            // automation platform treat a delivered proposal as undelivered.
            log.error("Proposal {} was emailed but its SENT status could not be recorded",
                    proposal.getProposalNumber(), e);
        }

        return new ProposalEmailResponse(
                String.valueOf(proposal.getId()), proposal.getProposalNumber(), "SENT",
                recipientEmail, messageId, pdf.length, OffsetDateTime.now());
    }

    /** Everything a template may use. Adding a placeholder is one line here. */
    private Map<String, String> values(Proposal proposal, String recipientName) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("signerName", recipientName == null ? "" : recipientName);
        values.put("proposalNumber", proposal.getProposalNumber());
        values.put("proposalTitle", proposal.getProposalType().displayName());
        // The offer's expiry is the one thing a proposal email has that a
        // contract email does not, and the reason a customer opens it promptly.
        values.put("validUntil", proposal.getValidUntil() == null
                ? "" : proposal.getValidUntil().format(VALID_UNTIL));
        values.put("senderName", emailSender.fromName());
        values.put("senderEmail", emailSender.fromEmail());
        return values;
    }

    private static ProposalEmailResponse alreadySent(Proposal proposal, AuditLog entry) {
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
        return new ProposalEmailResponse(
                String.valueOf(proposal.getId()), proposal.getProposalNumber(), "ALREADY_SENT",
                fields.get("sentTo"), messageId(fields), bytes, entry.getOccurredAt());
    }

    /** Entries written before the SMTP option existed call it mailjetMessageId. */
    private static String messageId(Map<String, String> fields) {
        return fields.containsKey("messageId") ? fields.get("messageId") : fields.get("mailjetMessageId");
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
