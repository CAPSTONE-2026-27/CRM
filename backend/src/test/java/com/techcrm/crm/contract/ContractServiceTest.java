package com.techcrm.crm.contract;

import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.contact.ContactRepository;
import com.techcrm.crm.contract.ContractDtos.GenerateContractRequest;
import com.techcrm.crm.contract.document.ContractDocumentException;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.document.DocumentStorageService.StoredDocument;
import com.techcrm.crm.contract.document.DocxGenerationService;
import com.techcrm.crm.contract.document.PdfConversionService;
import com.techcrm.crm.contract.template.ContractTemplateService;
import com.techcrm.crm.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.techcrm.crm.contract.ContractFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Orchestration: idempotency, regeneration, and what happens when the document
 *  step fails. The rendering itself is covered by DocxGenerationServiceTest. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContractServiceTest {

    @Mock ContractRepository contractRepository;
    @Mock ContractLineItemRepository lineItemRepository;
    @Mock ContractDataAssembler assembler;
    @Mock ContractTemplateService templateService;
    @Mock DocxGenerationService docxGenerationService;
    @Mock PdfConversionService pdfConversionService;
    @Mock DocumentStorageService storageService;
    @Mock ContractRecordService recordService;
    @Mock AccountRepository accountRepository;
    @Mock ContactRepository contactRepository;
    @Mock UserRepository userRepository;
    @Mock AuditLogService auditLogService;

    ContractService service;

    private static final GenerateContractRequest GENERATE =
            new GenerateContractRequest(DEAL_ID, null, null, null, null, null, null, null);
    private static final GenerateContractRequest REGENERATE =
            new GenerateContractRequest(DEAL_ID, null, null, null, null, null, null, true);

    @BeforeEach
    void setUp() {
        service = new ContractService(contractRepository, lineItemRepository, assembler, templateService,
                docxGenerationService, pdfConversionService, storageService, recordService,
                accountRepository, contactRepository, userRepository, auditLogService);

        when(assembler.requireDeal(any(), eq(DEAL_ID))).thenReturn(deal());
        when(assembler.assemble(any(), any(), any())).thenReturn(assembly());
        when(templateService.resolve(any(), any())).thenReturn(ContractType.STANDARD_SALES_AGREEMENT);
        when(templateService.templateKey(any())).thenReturn("classpath:contract-templates/x.docx");
        when(templateService.load(any())).thenReturn(new byte[]{1, 2, 3});
        when(docxGenerationService.generate(any(), any(), any())).thenReturn(new byte[]{4, 5, 6});
        when(pdfConversionService.isEnabled()).thenReturn(true);
        when(pdfConversionService.convertToPdf(any())).thenReturn(new byte[]{7, 8, 9});
        when(storageService.writeTemporary(anyString(), any())).thenReturn(Path.of("tmp/x.docx"));
        when(storageService.store(anyLong(), anyString(), eq("docx"), any()))
                .thenReturn(new StoredDocument("7/CTR-000042/a.docx", Path.of("a.docx"), 3));
        when(storageService.store(anyLong(), anyString(), eq("pdf"), any()))
                .thenReturn(new StoredDocument("7/CTR-000042/a.pdf", Path.of("a.pdf"), 3));

        when(contractRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.empty());
        when(recordService.createDraft(any(), any(), any(), any())).thenReturn(contract());
        when(recordService.attachDocuments(anyLong(), any(), any())).thenReturn(contract());
        when(lineItemRepository.findByContractIdOrderByLineNumberAsc(anyLong())).thenReturn(List.of());
    }

    @Test
    void generatesAContractAndReturnsTheFourFieldsTheBotNeeds() {
        var outcome = service.generate(caller(), GENERATE);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.response().contractId()).isEqualTo("42");
        assertThat(outcome.response().dealId()).isEqualTo("31");
        assertThat(outcome.response().status()).isEqualTo("GENERATED");
        assertThat(outcome.response().pdfUrl()).isEqualTo("/api/contracts/42/document.pdf");
        assertThat(outcome.response().docxUrl()).isEqualTo("/api/contracts/42/document.docx");
    }

    @Test
    void runsTheFullDocumentPipelineInOrder() {
        service.generate(caller(), GENERATE);

        verify(docxGenerationService).generate(any(), any(), any());
        verify(storageService).store(eq(ORG_ID), eq("CTR-000042"), eq("docx"), any());
        verify(pdfConversionService).convertToPdf(any());
        verify(storageService).store(eq(ORG_ID), eq("CTR-000042"), eq("pdf"), any());
        verify(recordService).attachDocuments(eq(42L), any(), any());
        verify(recordService).mirrorOntoDeal(eq(DEAL_ID), eq(ContractStatus.GENERATED), any());
    }

    /**
     * The retry case. The automation platform re-posts on timeout, and a second
     * signed agreement for one opportunity is a commercial problem, not just a
     * duplicate row.
     */
    @Test
    void aRetryReturnsTheExistingContractInsteadOfGeneratingASecond() {
        when(contractRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.of(contract()));

        var outcome = service.generate(caller(), GENERATE);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.response().contractId()).isEqualTo("42");
        verify(recordService, never()).createDraft(any(), any(), any(), any());
        verify(docxGenerationService, never()).generate(any(), any(), any());
    }

    @Test
    void aRetryDoesNotEvenAssembleTheContractData() {
        when(contractRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.of(contract()));

        service.generate(caller(), GENERATE);

        verify(assembler, never()).assemble(any(), any(), any());
    }

    @Test
    void anExplicitRegenerationSupersedesThePreviousContract() {
        when(contractRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.of(contract()));

        var outcome = service.generate(caller(), REGENERATE);

        assertThat(outcome.created()).isTrue();
        verify(recordService).supersede(42L);
        verify(recordService).createDraft(any(), any(), any(), any());
        verify(auditLogService).record(eq(ORG_ID), eq(USER_ID), eq("CONTRACT_SUPERSEDED"),
                eq("CONTRACT"), eq("42"), anyString());
    }

    /** A signed contract is the agreement. Quietly replacing it would leave the
     *  CRM claiming a document is current when the customer signed another. */
    @Test
    void refusesToRegenerateOverASignedContract() {
        Contract signed = contract();
        signed.setStatus(ContractStatus.SIGNED);
        when(contractRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.of(signed));

        assertThatThrownBy(() -> service.generate(caller(), REGENERATE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(recordService, never()).supersede(anyLong());
    }

    @Test
    void skipsPdfConversionWhenLibreOfficeIsTurnedOff() {
        when(pdfConversionService.isEnabled()).thenReturn(false);

        service.generate(caller(), GENERATE);

        verify(pdfConversionService, never()).convertToPdf(any());
        verify(storageService, times(1)).store(anyLong(), anyString(), eq("docx"), any());
        verify(storageService, never()).store(anyLong(), anyString(), eq("pdf"), any());
    }

    /**
     * A conversion failure has to leave a record a human can read, and must not
     * put a docx4j or LibreOffice dump into the API response.
     */
    @Test
    void recordsTheFailureAndKeepsTheDetailOutOfTheResponse() {
        when(pdfConversionService.convertToPdf(any()))
                .thenThrow(new ContractDocumentException("LibreOffice exited with 77: Fatal at /usr/lib/x"));

        assertThatThrownBy(() -> service.generate(caller(), GENERATE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    var ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(ex.getReason()).contains("CTR-000042");
                    assertThat(ex.getReason()).doesNotContain("LibreOffice");
                    assertThat(ex.getReason()).doesNotContain("/usr/lib");
                });

        verify(recordService).markFailed(eq(42L), anyString());
        verify(recordService).mirrorOntoDeal(DEAL_ID, ContractStatus.FAILED, null);
    }

    @Test
    void aDocxFailureIsRecordedTheSameWay() {
        when(docxGenerationService.generate(any(), any(), any()))
                .thenThrow(new ContractDocumentException("docx4j: unmarshalling failed"));

        assertThatThrownBy(() -> service.generate(caller(), GENERATE))
                .isInstanceOf(ResponseStatusException.class);

        verify(recordService).markFailed(eq(42L), anyString());
        verify(storageService, never()).store(anyLong(), anyString(), eq("pdf"), any());
    }

    /** The temporary DOCX exists only so LibreOffice has a file to open, and
     *  must go even when the conversion blows up. */
    @Test
    void cleansUpTheTemporaryDocxOnFailure() {
        when(pdfConversionService.convertToPdf(any()))
                .thenThrow(new ContractDocumentException("boom"));

        assertThatThrownBy(() -> service.generate(caller(), GENERATE))
                .isInstanceOf(ResponseStatusException.class);

        verify(storageService).deleteTemporary(any());
    }

    @Test
    void writesAnAuditEntryForASuccessfulGeneration() {
        service.generate(caller(), GENERATE);

        verify(auditLogService).record(eq(ORG_ID), eq(USER_ID), eq("CONTRACT_GENERATED"),
                eq("CONTRACT"), eq("42"), anyString());
    }

    @Test
    void getIsScopedToTheCallersOrganization() {
        when(contractRepository.findByIdAndOrganizationId(42L, ORG_ID)).thenReturn(Optional.of(contract()));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(contact()));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner()));

        var response = service.get(caller(), 42L);

        assertThat(response.contractId()).isEqualTo("42");
        assertThat(response.accountName()).isEqualTo("Northwind Traders Pvt Ltd");
        assertThat(response.salesExecutive()).isEqualTo("Ravi Kulkarni");
        assertThat(response.contractTypeName()).isEqualTo("Standard Sales Agreement");
        assertThat(response.pdfUrl()).isEqualTo("/api/contracts/42/document.pdf");
    }

    @Test
    void anUnknownContractIsNotFound() {
        when(contractRepository.findByIdAndOrganizationId(anyLong(), anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(caller(), 999L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void statusIsTheSmallShapeTheBotPolls() {
        Contract sent = contract();
        sent.setStatus(ContractStatus.SENT);
        when(contractRepository.findByIdAndOrganizationId(42L, ORG_ID)).thenReturn(Optional.of(sent));

        var status = service.status(caller(), 42L);

        assertThat(status.contractId()).isEqualTo("42");
        assertThat(status.dealId()).isEqualTo("31");
        assertThat(status.status()).isEqualTo("SENT");
    }

    /** A URL for a document that was never produced would 404 on use; null says
     *  so up front. */
    @Test
    void reportsNoDocumentUrlForAContractThatHasNoDocument() {
        Contract failed = contract();
        failed.setStatus(ContractStatus.FAILED);
        failed.setDocxPath(null);
        failed.setPdfPath(null);

        var response = service.toGenerateResponse(failed);

        assertThat(response.pdfUrl()).isNull();
        assertThat(response.docxUrl()).isNull();
        assertThat(response.status()).isEqualTo("FAILED");
    }
}
