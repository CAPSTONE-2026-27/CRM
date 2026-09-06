package com.techcrm.crm.contract.signature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.contract.Contract;
import com.techcrm.crm.contract.ContractDtos.WebhookAck;
import com.techcrm.crm.contract.ContractRecordService;
import com.techcrm.crm.contract.ContractRepository;
import com.techcrm.crm.contract.ContractStatus;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.deal.DealRepository;
import com.techcrm.crm.onboarding.CustomerOnboardingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/**
 * Processes a Documenso signature callback.
 *
 * <pre>
 *   verify secret -> parse -> find contract -> deduplicate
 *                -> update contract -> mirror onto the deal
 *                -> audit -> activate the customer, if signed
 * </pre>
 *
 * The endpoint is unauthenticated — Documenso holds no CRM token and there is
 * nobody to issue it one — so the shared secret is the whole of its access
 * control. An unconfigured secret means the endpoint refuses everything rather
 * than trusting everything: the alternative is a public URL that can mark any
 * contract signed.
 */
@Service
public class ContractWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ContractWebhookService.class);

    private final ContractRepository contractRepository;
    private final DealRepository dealRepository;
    private final ContractEventRecorder eventRecorder;
    private final ContractRecordService recordService;
    private final CustomerOnboardingService onboardingService;
    private final AuditLogService auditLogService;
    private final DocumensoProperties properties;

    /**
     * Constructed, not injected. Spring Boot 4 makes JSON binding pluggable and
     * this application context has no ObjectMapper bean to autowire — see the
     * jackson-databind note in pom.xml. {@link com.techcrm.crm.ai.AiJson} owns
     * its own for the same reason. Only ever used to read a webhook body, so
     * the default configuration is all it needs.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ContractWebhookService(ContractRepository contractRepository,
                                  DealRepository dealRepository,
                                  ContractEventRecorder eventRecorder,
                                  ContractRecordService recordService,
                                  CustomerOnboardingService onboardingService,
                                  AuditLogService auditLogService,
                                  DocumensoProperties properties) {
        this.contractRepository = contractRepository;
        this.dealRepository = dealRepository;
        this.eventRecorder = eventRecorder;
        this.recordService = recordService;
        this.onboardingService = onboardingService;
        this.auditLogService = auditLogService;
        this.properties = properties;
    }

    /**
     * The whole delivery is one transaction, so the "already seen" marker, the
     * status change and the activation commit together or not at all.
     *
     * @param rawBody the request body verbatim. Not a bound object: the digest
     *                that makes redelivery detection work has to be taken over
     *                exactly the bytes Documenso sent, and a re-serialised
     *                object is not those bytes.
     */
    @Transactional
    public WebhookAck handle(String rawBody, String providedSecret) {
        verifySecret(providedSecret);

        DocumensoWebhookEvent event;
        try {
            event = DocumensoWebhookEvent.parse(objectMapper, rawBody);
        } catch (DocumensoException e) {
            log.warn("Rejected a Documenso webhook: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed webhook payload");
        }

        Contract contract = findContract(event);
        if (contract == null) {
            // 404 rather than 200: Documenso surfaces failed deliveries in its
            // own UI, and silently swallowing an event for a contract we cannot
            // find would hide a real misconfiguration.
            log.warn("Documenso {} referenced unknown document {} / external id {}",
                    event.eventType(), event.documentId(), event.externalId());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown contract");
        }

        String digest = sha256(rawBody);
        if (!eventRecorder.recordIfNew(contract.getId(), event.eventType(), event.documentId(), digest)) {
            log.info("Ignoring duplicate Documenso {} for contract {}",
                    event.eventType(), contract.getContractNumber());
            return new WebhookAck("duplicate", String.valueOf(contract.getId()), contract.getStatus().name());
        }

        Contract updated = apply(contract.getId(), event);
        return new WebhookAck("processed", String.valueOf(updated.getId()), updated.getStatus().name());
    }

    /**
     * Constant-time comparison. A byte-by-byte early exit on a shared secret is
     * a timing oracle, and this endpoint is reachable by anyone who can find the
     * URL.
     */
    private void verifySecret(String providedSecret) {
        String expected = properties.getWebhook().getSecret();
        if (expected == null || expected.isBlank()) {
            log.error("A Documenso webhook arrived but documenso.webhook.secret is not set; refusing it");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Signature callbacks are not configured on this server");
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = providedSecret == null
                ? new byte[0] : providedSecret.getBytes(StandardCharsets.UTF_8);

        if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
            log.warn("Rejected a Documenso webhook with a bad or missing {} header",
                    properties.getWebhook().getSecretHeader());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
    }

    /** By Documenso's document id first, then by the contract number we sent as
     *  externalId — which still identifies the contract if the id was never
     *  stored because the send call died after Documenso had accepted it. */
    private Contract findContract(DocumensoWebhookEvent event) {
        if (event.documentId() != null) {
            Contract byDocument = contractRepository.findByDocumensoDocumentId(event.documentId()).orElse(null);
            if (byDocument != null) {
                return byDocument;
            }
        }
        if (event.externalId() != null) {
            return contractRepository.findByContractNumber(event.externalId()).orElse(null);
        }
        return null;
    }

    /**
     * Applies one event.
     *
     * Runs inside {@link #handle}'s transaction, so a contract cannot end up
     * marked SIGNED with the deal left unmirrored, and customer activation goes
     * back with a rollback rather than outliving the signature that triggered it.
     */
    private Contract apply(Long contractId, DocumensoWebhookEvent event) {
        Contract contract = contractRepository.findById(contractId).orElseThrow();

        ContractStatus previous = contract.getStatus();
        ContractStatus next = statusFor(event.eventType(), previous);

        if (next != null && next != previous) {
            contract.setStatus(next);
            if (next == ContractStatus.SIGNED) {
                contract.setSignedAt(OffsetDateTime.now());
            } else if (next == ContractStatus.REJECTED) {
                contract.setRejectedAt(OffsetDateTime.now());
                contract.setRejectionReason(event.rejectionReason());
            }
            contract = contractRepository.save(contract);
            recordService.mirrorOntoDeal(contract.getDealId(), next, contract.getSignedAt());
        }

        auditLogService.record(contract.getOrganizationId(), null, "CONTRACT_" + event.eventType(),
                "CONTRACT", String.valueOf(contract.getId()),
                "Documenso document " + event.documentId() + ": " + previous + " -> " + contract.getStatus());

        if (contract.getStatus() == ContractStatus.SIGNED && previous != ContractStatus.SIGNED) {
            activateCustomer(contract);
        }

        return contract;
    }

    /**
     * Documenso's events, mapped onto contract status.
     *
     * A terminal status is never left: a stray OPENED arriving after a signature
     * must not walk a signed contract back to VIEWED. Unknown events map to
     * null, which records them without changing anything — the right response to
     * a Documenso release that adds one.
     *
     * DOCUMENT_SIGNED and DOCUMENT_COMPLETED both mean signed here because the
     * CRM creates exactly one recipient per document, so there is no partial
     * state between them. That would need revisiting if countersigning were
     * added.
     */
    static ContractStatus statusFor(String eventType, ContractStatus current) {
        if (current != null && current.isTerminal()) {
            return null;
        }
        return switch (eventType) {
            case DocumensoWebhookEvent.DOCUMENT_SENT -> ContractStatus.SENT;
            case DocumensoWebhookEvent.DOCUMENT_OPENED -> ContractStatus.VIEWED;
            case DocumensoWebhookEvent.DOCUMENT_SIGNED,
                 DocumensoWebhookEvent.DOCUMENT_COMPLETED -> ContractStatus.SIGNED;
            case DocumensoWebhookEvent.DOCUMENT_REJECTED,
                 DocumensoWebhookEvent.DOCUMENT_CANCELLED -> ContractStatus.REJECTED;
            default -> null;
        };
    }

    /**
     * Customer activation — the last step of the workflow, and the reason the
     * signature has to reach the CRM at all.
     *
     * Reuses the existing onboarding module rather than introducing a second
     * notion of an activated customer. It does not touch the deal's stage: see
     * {@link ContractRecordService#mirrorOntoDeal}.
     */
    private void activateCustomer(Contract contract) {
        Deal deal = dealRepository.findById(contract.getDealId()).orElse(null);
        if (deal == null) {
            log.warn("Contract {} was signed but its deal {} no longer exists; skipping activation",
                    contract.getContractNumber(), contract.getDealId());
            return;
        }
        onboardingService.initiate(contract.getOrganizationId(), deal.getId(), deal.getOpportunityId(),
                deal.getAccountId(), deal.getOwnerId());
        log.info("Contract {} signed; customer activation opened for opportunity {}",
                contract.getContractNumber(), deal.getOpportunityId());
    }

    private static String sha256(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
        }
    }
}
