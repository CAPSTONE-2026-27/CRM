package com.techcrm.crm.contract.signature;

import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.contract.Contract;
import com.techcrm.crm.contract.ContractDtos.SendForSignatureRequest;
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

import java.util.Optional;

import static com.techcrm.crm.contract.ContractFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContractSignatureServiceTest {

    @Mock ContractRepository contractRepository;
    @Mock DocumensoClient documensoClient;
    @Mock DocumentStorageService storageService;
    @Mock ContractRecordService recordService;
    @Mock AuditLogService auditLogService;

    ContractSignatureService service;

    private static final SendForSignatureRequest SEND = new SendForSignatureRequest(42L, null, null);

    @BeforeEach
    void setUp() {
        service = new ContractSignatureService(contractRepository, documensoClient, storageService,
                recordService, auditLogService);

        when(contractRepository.findByIdAndOrganizationId(42L, ORG_ID)).thenReturn(Optional.of(contract()));
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.read(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(documensoClient.isConfigured()).thenReturn(true);
        when(documensoClient.send(any())).thenReturn(
                new DocumensoClient.SignatureResult("881", "45", "https://sign.example/s/tok"));

        Contract sent = contract();
        sent.setStatus(ContractStatus.SENT);
        sent.setDocumensoDocumentId("881");
        sent.setSignUrl("https://sign.example/s/tok");
        when(recordService.markSent(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sent);
    }

    @Test
    void sendsTheContractAndReturnsWhatTheBotNeeds() {
        var response = service.sendForSignature(caller(), SEND);

        assertThat(response.contractId()).isEqualTo("42");
        assertThat(response.status()).isEqualTo("SENT");
        assertThat(response.documensoId()).isEqualTo("881");
        assertThat(response.signUrl()).isEqualTo("https://sign.example/s/tok");
    }

    /** The contract number travels as Documenso's externalId, which is what lets
     *  a webhook find the contract even if the document id were never stored. */
    @Test
    void sendsThePdfWithTheContractNumberAsExternalId() {
        service.sendForSignature(caller(), SEND);

        var captor = ArgumentCaptor.forClass(DocumensoClient.SignatureRequest.class);
        verify(documensoClient).send(captor.capture());

        var request = captor.getValue();
        assertThat(request.externalId()).isEqualTo("CTR-000042");
        assertThat(request.recipientEmail()).isEqualTo("asha.menon@northwind.example");
        assertThat(request.recipientName()).isEqualTo("Asha Menon");
        assertThat(request.title()).contains("CTR-000042").contains("Standard Sales Agreement");
        verify(storageService).read("7/CTR-000042/CTR-000042-20260901-101500.pdf");
    }

    @Test
    void anExplicitRecipientOverridesTheContractsContact() {
        service.sendForSignature(caller(), new SendForSignatureRequest(42L, "Legal Desk", "legal@northwind.example"));

        var captor = ArgumentCaptor.forClass(DocumensoClient.SignatureRequest.class);
        verify(documensoClient).send(captor.capture());
        assertThat(captor.getValue().recipientEmail()).isEqualTo("legal@northwind.example");
        assertThat(captor.getValue().recipientName()).isEqualTo("Legal Desk");
    }

    @Test
    void mirrorsSentOntoTheDealAndAudits() {
        service.sendForSignature(caller(), SEND);

        verify(recordService).mirrorOntoDeal(DEAL_ID, ContractStatus.SENT, null);
        verify(auditLogService).record(eq(ORG_ID), eq(USER_ID), eq("CONTRACT_SENT"),
                eq("CONTRACT"), eq("42"), anyString());
    }

    /** Sending twice would give the customer two links to one agreement, and
     *  only one of them would report back. */
    @Test
    void aContractAlreadyWithDocumensoIsNotSentAgain() {
        Contract already = contract();
        already.setStatus(ContractStatus.SENT);
        already.setDocumensoDocumentId("881");
        already.setSignUrl("https://sign.example/s/tok");
        when(contractRepository.findByIdAndOrganizationId(42L, ORG_ID)).thenReturn(Optional.of(already));

        var response = service.sendForSignature(caller(), SEND);

        assertThat(response.documensoId()).isEqualTo("881");
        verify(documensoClient, never()).send(any());
    }

    @Test
    void anUnknownContractIsNotFound() {
        when(contractRepository.findByIdAndOrganizationId(anyLong(), anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendForSignature(caller(), new SendForSignatureRequest(999L, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The customer signs the PDF, so a DOCX-only contract cannot go out even
     *  though it generated successfully. */
    @Test
    void refusesAContractWithNoGeneratedPdf() {
        Contract docxOnly = contract();
        docxOnly.setPdfPath(null);
        when(contractRepository.findByIdAndOrganizationId(42L, ORG_ID)).thenReturn(Optional.of(docxOnly));

        assertThatThrownBy(() -> service.sendForSignature(caller(), SEND))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no generated PDF");
    }

    @Test
    void refusesWhenTheStoredPdfHasGoneMissingFromDisk() {
        when(storageService.exists(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.sendForSignature(caller(), SEND))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void refusesAContractThatIsNotInASendableState() {
        Contract rejected = contract();
        rejected.setStatus(ContractStatus.REJECTED);
        when(contractRepository.findByIdAndOrganizationId(42L, ORG_ID)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> service.sendForSignature(caller(), SEND))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be sent for signature");
    }

    @Test
    void saysSoPlainlyWhenDocumensoIsNotConfigured() {
        when(documensoClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.sendForSignature(caller(), SEND))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** Documenso's own error text can quote request internals, so it goes to the
     *  log and the audit trail, never to the caller. */
    @Test
    void aDocumensoFailureBecomesABadGatewayWithoutLeakingItsMessage() {
        when(documensoClient.send(any()))
                .thenThrow(new DocumensoException("Documenso rejected the document creation with 401 key=sk_live_x"));

        assertThatThrownBy(() -> service.sendForSignature(caller(), SEND))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getReason()).doesNotContain("sk_live_x");
                    assertThat(ex.getReason()).doesNotContain("401");
                });

        verify(auditLogService).record(eq(ORG_ID), eq(USER_ID), eq("CONTRACT_SEND_FAILED"),
                eq("CONTRACT"), eq("42"), anyString());
        verify(recordService, never()).markSent(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotSendAContractBelongingToAnotherOrganization() {
        when(contractRepository.findByIdAndOrganizationId(42L, 999L)).thenReturn(Optional.empty());
        var otherOrg = new com.techcrm.crm.auth.AuthenticatedUser(
                1L, 999L, com.techcrm.crm.user.Role.ADMIN, java.util.List.of(), false);

        assertThatThrownBy(() -> service.sendForSignature(otherOrg, SEND))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Contract not found");
        verify(documensoClient, never()).send(any());
    }
}
