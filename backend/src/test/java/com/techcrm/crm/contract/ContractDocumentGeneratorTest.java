package com.techcrm.crm.contract;

import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.contract.ContractDocumentGenerator.DocumentJob;
import com.techcrm.crm.contract.document.ContractDocumentException;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.document.DocumentStorageService.StoredDocument;
import com.techcrm.crm.contract.document.DocxGenerationService;
import com.techcrm.crm.contract.document.PdfConversionService;
import com.techcrm.crm.contract.template.ContractTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.techcrm.crm.contract.ContractFixtures.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The background half of generation.
 *
 * What matters here is the failure behaviour. Nothing awaits this task, so an
 * exception escaping it goes to the executor's default handler and disappears —
 * leaving a contract stuck in DRAFTING with no explanation and its deal's slot
 * held indefinitely. Every case below therefore asserts that the method returns
 * normally and that the failure was written where somebody can find it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContractDocumentGeneratorTest {

    @Mock ContractTemplateService templateService;
    @Mock DocxGenerationService docxGenerationService;
    @Mock PdfConversionService pdfConversionService;
    @Mock DocumentStorageService storageService;
    @Mock ContractRecordService recordService;
    @Mock AuditLogService auditLogService;

    ContractDocumentGenerator generator;

    private static final DocumentJob JOB = new DocumentJob(
            42L, "CTR-000042", DEAL_ID, ORG_ID, USER_ID,
            ContractType.STANDARD_SALES_AGREEMENT,
            Map.of("contractNumber", "CTR-000042"),
            List.of());

    @BeforeEach
    void setUp() {
        generator = new ContractDocumentGenerator(templateService, docxGenerationService,
                pdfConversionService, storageService, recordService, auditLogService);

        when(templateService.load(any())).thenReturn(new byte[]{1, 2, 3});
        when(docxGenerationService.generate(any(), any(), any())).thenReturn(new byte[]{4, 5, 6});
        when(pdfConversionService.isEnabled()).thenReturn(true);
        when(pdfConversionService.convertToPdf(any())).thenReturn(new byte[]{7, 8, 9});
        when(storageService.writeTemporary(anyString(), any())).thenReturn(Path.of("tmp/x.docx"));
        when(storageService.store(anyLong(), anyString(), eq("docx"), any()))
                .thenReturn(new StoredDocument("7/CTR-000042/a.docx", Path.of("a.docx"), 3));
        when(storageService.store(anyLong(), anyString(), eq("pdf"), any()))
                .thenReturn(new StoredDocument("7/CTR-000042/a.pdf", Path.of("a.pdf"), 3));
        when(recordService.attachDocuments(anyLong(), any(), any())).thenReturn(contract());
    }

    @Test
    void rendersConvertsStoresAndPromotesTheContract() {
        generator.generate(JOB);

        verify(docxGenerationService).generate(any(), eq(JOB.placeholders()), eq(JOB.lineItems()));
        verify(storageService).store(eq(ORG_ID), eq("CTR-000042"), eq("docx"), any());
        verify(pdfConversionService).convertToPdf(any());
        verify(storageService).store(eq(ORG_ID), eq("CTR-000042"), eq("pdf"), any());
        verify(recordService).attachDocuments(eq(42L), any(), any());
        verify(recordService).mirrorOntoDeal(eq(DEAL_ID), eq(ContractStatus.GENERATED), any());
        verify(auditLogService).record(eq(ORG_ID), eq(USER_ID), eq("CONTRACT_GENERATED"),
                eq("CONTRACT"), eq("42"), anyString());
    }

    @Test
    void skipsPdfConversionWhenLibreOfficeIsTurnedOff() {
        when(pdfConversionService.isEnabled()).thenReturn(false);

        generator.generate(JOB);

        verify(pdfConversionService, never()).convertToPdf(any());
        verify(storageService).store(anyLong(), anyString(), eq("docx"), any());
        verify(storageService, never()).store(anyLong(), anyString(), eq("pdf"), any());
    }

    /** The contract must end up FAILED, and the caller must be able to see why
     *  through GET /api/contracts/{id}. */
    @Test
    void aRenderFailureIsRecordedRatherThanThrown() {
        when(docxGenerationService.generate(any(), any(), any()))
                .thenThrow(new ContractDocumentException("docx4j: unexpected end of ZIP"));

        assertThatCode(() -> generator.generate(JOB)).doesNotThrowAnyException();

        verify(recordService).markFailed(eq(42L), contains("docx4j"));
        verify(recordService).mirrorOntoDeal(eq(DEAL_ID), eq(ContractStatus.FAILED), any());
        verify(auditLogService).record(eq(ORG_ID), eq(USER_ID), eq("CONTRACT_GENERATION_FAILED"),
                eq("CONTRACT"), eq("42"), anyString());
    }

    @Test
    void aConversionFailureIsRecordedRatherThanThrown() {
        when(pdfConversionService.convertToPdf(any()))
                .thenThrow(new ContractDocumentException("LibreOffice exited with 1"));

        assertThatCode(() -> generator.generate(JOB)).doesNotThrowAnyException();

        verify(recordService).markFailed(eq(42L), contains("LibreOffice"));
    }

    /** The temporary DOCX LibreOffice opens must be cleaned up even when the
     *  conversion throws, or a failing server slowly fills its own disk. */
    @Test
    void deletesTheTemporaryDocxEvenWhenConversionFails() {
        when(pdfConversionService.convertToPdf(any()))
                .thenThrow(new ContractDocumentException("LibreOffice exited with 1"));

        generator.generate(JOB);

        verify(storageService).deleteTemporary(any());
    }

    /**
     * The one that has actually happened: the render succeeded and the database
     * connection died on the write. Not a ContractDocumentException, so it needs
     * catching separately — otherwise the contract stays DRAFTING for ever.
     */
    @Test
    void anUnexpectedFailureIsAlsoRecordedRatherThanThrown() {
        when(recordService.attachDocuments(anyLong(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                        "Unable to commit against JDBC Connection"));

        assertThatCode(() -> generator.generate(JOB)).doesNotThrowAnyException();

        verify(recordService).markFailed(eq(42L), contains("JDBC"));
    }

    /**
     * The annotation is the entire point of this class.
     *
     * Without it the method runs on the request thread and everything still
     * passes — the only symptom is the clock, and SAP BPA timing out again.
     * Asserted by reflection because a unit test calls the bean directly and so
     * cannot observe the proxy; {@code @EnableAsync} itself is already exercised
     * by the lead-scoring path.
     */
    @Test
    void theGenerateMethodIsAsyncAndUsesTheDocumentPool() throws Exception {
        var method = ContractDocumentGenerator.class.getMethod("generate", DocumentJob.class);
        var async = method.getAnnotation(org.springframework.scheduling.annotation.Async.class);

        org.assertj.core.api.Assertions.assertThat(async)
                .as("generate() must be @Async or generation goes back on the request thread")
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(async.value())
                .as("must use the bounded document pool, not the framework default")
                .isEqualTo("documentGenerationExecutor");
    }

    /** A void return is what makes this fire-and-forget. A Future would invite a
     *  caller to block on it, which would undo the change. */
    @Test
    void theGenerateMethodReturnsNothingToWaitOn() throws Exception {
        org.assertj.core.api.Assertions.assertThat(
                        ContractDocumentGenerator.class.getMethod("generate", DocumentJob.class).getReturnType())
                .isEqualTo(void.class);
    }

    /**
     * When the database is what failed, recording the failure fails too. The task
     * must still return quietly — the stranded DRAFTING row is picked up by the
     * next generate, which supersedes any live contract that has no document.
     */
    @Test
    void survivesBeingUnableToRecordTheFailure() {
        when(docxGenerationService.generate(any(), any(), any()))
                .thenThrow(new ContractDocumentException("render failed"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database is gone"))
                .when(recordService).markFailed(anyLong(), anyString());

        assertThatCode(() -> generator.generate(JOB)).doesNotThrowAnyException();
    }
}
