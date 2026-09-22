package com.techcrm.crm.contract.email;

import com.techcrm.crm.audit.AuditLog;
import com.techcrm.crm.audit.AuditLogRepository;
import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.contract.Contract;
import com.techcrm.crm.contract.ContractDtos.SendContractEmailRequest;
import com.techcrm.crm.contract.ContractRecordService;
import com.techcrm.crm.contract.ContractRepository;
import com.techcrm.crm.contract.ContractStatus;
import com.techcrm.crm.contract.document.DocumentStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static com.techcrm.crm.contract.ContractFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Emailing a contract. What matters most: the customer is never emailed twice by
 * a retry, never emailed a contract that is unfinished or no longer current, and
 * a failure is recorded where somebody can find out why.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContractEmailServiceTest {

    @Mock ContractRepository contractRepository;
    @Mock ContractRecordService recordService;
    @Mock DocumentStorageService storageService;
    @Mock ContractEmailTemplates templates;
    @Mock MailjetClient mailjetClient;
    @Mock AuditLogService auditLogService;
    @Mock AuditLogRepository auditLogRepository;

    MailjetProperties properties = new MailjetProperties();
    ContractEmailService service;

    private static final byte[] PDF = "%PDF-1.7 contract".getBytes();

    @BeforeEach
    void setUp() {
        properties.setFromName("Piyush Pawar");
        properties.setFromEmail("sales@techcrm.example");
        service = new ContractEmailService(contractRepository, recordService, storageService, templates,
                mailjetClient, properties, auditLogService, auditLogRepository);

        // contract() is GENERATED with a PDF and a contact — ready to email.
        when(contractRepository.findByIdAndOrganizationId(anyLong(), anyLong())).thenReturn(Optional.of(contract()));
        when(auditLogRepository.findFirstByOrganizationIdAndEntityTypeAndEntityIdAndActionOrderByOccurredAtDesc(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.read(anyString())).thenReturn(PDF);
        when(mailjetClient.isConfigured()).thenReturn(true);
        when(templates.render(any())).thenReturn(
                new ContractEmailTemplates.Rendered("Subject", "<p>html</p>", "text"));
        when(mailjetClient.send(any())).thenReturn("uuid-123");
        when(recordService.markEmailed(anyLong())).thenReturn(contract());
    }

    private static Contract withStatus(ContractStatus status) {
        Contract contract = contract();
        contract.setStatus(status);
        return contract;
    }

    @Test
    void emailsAGeneratedContractsPdfToItsContact() {
        var result = service.send(caller(), 42L, null);

        ArgumentCaptor<MailjetClient.Email> email = ArgumentCaptor.forClass(MailjetClient.Email.class);
        verify(mailjetClient).send(email.capture());
        assertThat(email.getValue().toEmail()).isEqualTo("asha.menon@northwind.example");
        assertThat(email.getValue().toName()).isEqualTo("Asha Menon");
        assertThat(email.getValue().attachmentName()).isEqualTo("CTR-000042.pdf");
        assertThat(email.getValue().attachment()).isEqualTo(PDF);
        assertThat(email.getValue().customId()).isEqualTo("CTR-000042");

        assertThat(result.status()).isEqualTo("SENT");
        assertThat(result.mailjetMessageId()).isEqualTo("uuid-123");
        assertThat(result.attachmentBytes()).isEqualTo(PDF.length);

        verify(auditLogService).record(eq(ORG_ID), eq(USER_ID), eq(ContractEmailService.EMAILED),
                eq("CONTRACT"), eq("42"), contains("mailjetMessageId=uuid-123"));
    }

    /** No signature step any more: an email is what puts the contract in front
     *  of the customer, so it is what moves the contract and the deal to SENT. */
    @Test
    void marksTheContractAndTheDealSent() {
        service.send(caller(), 42L, null);

        verify(recordService).markEmailed(42L);
        verify(recordService).mirrorOntoDeal(DEAL_ID, ContractStatus.SENT, null);
    }

    /** The email is already with the customer, so failing the call would make
     *  the automation platform think it was not delivered. */
    @Test
    void aFailureToRecordTheStatusDoesNotFailADeliveredEmail() {
        when(recordService.markEmailed(anyLong()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db gone"));

        assertThatCode(() -> service.send(caller(), 42L, null)).doesNotThrowAnyException();
        verify(auditLogService).record(any(), any(), eq(ContractEmailService.EMAILED), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void theTemplateGetsTheContractsValues() {
        service.send(caller(), 42L, null);

        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        verify(templates).render(values.capture());
        assertThat(values.getValue())
                .containsEntry("signerName", "Asha Menon")
                .containsEntry("contractNumber", "CTR-000042")
                .containsEntry("senderName", "Piyush Pawar")
                .containsEntry("senderEmail", "sales@techcrm.example")
                .containsKey("contractTitle");
    }

    /** Demo contacts use example.com, which never receives mail. */
    @Test
    void theRecipientCanBeOverridden() {
        service.send(caller(), 42L, new SendContractEmailRequest("me@company.com", "Test Person", null));

        ArgumentCaptor<MailjetClient.Email> email = ArgumentCaptor.forClass(MailjetClient.Email.class);
        verify(mailjetClient).send(email.capture());
        assertThat(email.getValue().toEmail()).isEqualTo("me@company.com");
        assertThat(email.getValue().toName()).isEqualTo("Test Person");
    }

    /**
     * The reason this is idempotent: SAP retries a step whose response it lost,
     * and without this the customer gets the contract twice.
     */
    @Test
    void aRetryDoesNotEmailTheCustomerAgain() {
        AuditLog earlier = new AuditLog();
        earlier.setDetail("sentTo=asha.menon@northwind.example;mailjetMessageId=uuid-first;attachmentBytes=82637");
        earlier.setOccurredAt(OffsetDateTime.parse("2026-09-17T09:00:00Z"));
        when(auditLogRepository.findFirstByOrganizationIdAndEntityTypeAndEntityIdAndActionOrderByOccurredAtDesc(
                ORG_ID, "CONTRACT", "42", ContractEmailService.EMAILED)).thenReturn(Optional.of(earlier));

        var result = service.send(caller(), 42L, null);

        verify(mailjetClient, never()).send(any());
        verify(recordService, never()).markEmailed(anyLong());
        assertThat(result.status()).isEqualTo("ALREADY_SENT");
        assertThat(result.mailjetMessageId()).isEqualTo("uuid-first");
        assertThat(result.sentTo()).isEqualTo("asha.menon@northwind.example");
        assertThat(result.attachmentBytes()).isEqualTo(82637);
    }

    @Test
    void resendEmailsAnAlreadySentContractAgainOnPurpose() {
        when(contractRepository.findByIdAndOrganizationId(anyLong(), anyLong()))
                .thenReturn(Optional.of(withStatus(ContractStatus.SENT)));
        AuditLog earlier = new AuditLog();
        earlier.setDetail("sentTo=x;mailjetMessageId=uuid-first;attachmentBytes=1");
        when(auditLogRepository.findFirstByOrganizationIdAndEntityTypeAndEntityIdAndActionOrderByOccurredAtDesc(
                any(), any(), any(), any())).thenReturn(Optional.of(earlier));

        var result = service.send(caller(), 42L, new SendContractEmailRequest(null, null, true));

        verify(mailjetClient).send(any());
        assertThat(result.status()).isEqualTo("SENT");
    }

    @Test
    void aContractStillGeneratingIsRefused() {
        when(contractRepository.findByIdAndOrganizationId(anyLong(), anyLong()))
                .thenReturn(Optional.of(withStatus(ContractStatus.DRAFTING)));

        assertStatus(() -> service.send(caller(), 42L, null), HttpStatus.CONFLICT);
        verify(mailjetClient, never()).send(any());
    }

    /** Superseded, failed, signed or rejected: not a document to put in front of
     *  the customer now. */
    @Test
    void aContractThatIsNoLongerCurrentIsRefused() {
        for (ContractStatus status : new ContractStatus[]{
                ContractStatus.SUPERSEDED, ContractStatus.FAILED, ContractStatus.SIGNED, ContractStatus.REJECTED}) {
            when(contractRepository.findByIdAndOrganizationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(withStatus(status)));

            assertStatus(() -> service.send(caller(), 42L, null), HttpStatus.CONFLICT);
        }
        verify(mailjetClient, never()).send(any());
    }

    @Test
    void aMissingPdfIsRefused() {
        when(storageService.exists(anyString())).thenReturn(false);

        assertStatus(() -> service.send(caller(), 42L, null), HttpStatus.CONFLICT);
    }

    @Test
    void anotherOrganizationsContractIsNotFound() {
        when(contractRepository.findByIdAndOrganizationId(anyLong(), anyLong())).thenReturn(Optional.empty());

        assertStatus(() -> service.send(caller(), 42L, null), HttpStatus.NOT_FOUND);
    }

    @Test
    void noRecipientAddressIsABadRequest() {
        Contract noEmail = contract();
        noEmail.setSignerEmail(null);
        when(contractRepository.findByIdAndOrganizationId(anyLong(), anyLong())).thenReturn(Optional.of(noEmail));

        assertStatus(() -> service.send(caller(), 42L, null), HttpStatus.BAD_REQUEST);
    }

    @Test
    void unconfiguredMailjetIsServiceUnavailable() {
        when(mailjetClient.isConfigured()).thenReturn(false);

        assertStatus(() -> service.send(caller(), 42L, null), HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** Mailjet's reason goes to the audit log; the response stays generic, and
     *  the contract is not marked SENT. */
    @Test
    void aMailjetFailureIsRecordedAndBecomesABadGateway() {
        when(mailjetClient.send(any())).thenThrow(new MailjetException("Sender not validated"));

        assertThatThrownBy(() -> service.send(caller(), 42L, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getReason()).doesNotContain("Sender not validated");
                });

        verify(auditLogService).record(eq(ORG_ID), eq(USER_ID), eq(ContractEmailService.EMAIL_FAILED),
                eq("CONTRACT"), eq("42"), contains("Sender not validated"));
        verify(auditLogService, never()).record(any(), any(), eq(ContractEmailService.EMAILED),
                any(), any(), any());
        verify(recordService, never()).markEmailed(anyLong());
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }
}
