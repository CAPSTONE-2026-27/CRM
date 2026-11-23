package com.techcrm.crm.contract;

import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.contract.document.ContractDocumentException;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.document.DocumentStorageService.StoredDocument;
import com.techcrm.crm.contract.document.DocxGenerationService;
import com.techcrm.crm.contract.document.PdfConversionService;
import com.techcrm.crm.contract.template.ContractTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Renders a contract's documents off the request thread.
 *
 * The reason this exists is a hard external limit: SAP Build Process Automation
 * abandons an HTTP call at 30 seconds, and a cold generation takes longer than
 * that — a docx4j render plus a LibreOffice subprocess. Holding the connection
 * open until it finished meant the bot timed out and retried something that was
 * still running.
 *
 * So {@code POST /generate} now validates, reserves the contract row and returns
 * 202 in about a second, and the slow half happens here. Everything the task
 * needs is passed in as plain values — no JPA entities cross the thread
 * boundary, because they would be detached by the time this ran and any lazy
 * access would fail somewhere unhelpful.
 *
 * A distinct bean rather than an {@code @Async} method on {@code ContractService}
 * for the usual proxying reason: Spring cannot intercept a call an object makes
 * to itself, so the annotation would be silently ignored and the work would run
 * on the request thread exactly as before — the failure mode being that nothing
 * looks wrong except the clock.
 */
@Service
public class ContractDocumentGenerator {

    private static final Logger log = LoggerFactory.getLogger(ContractDocumentGenerator.class);

    /**
     * Everything the render needs, resolved on the request thread while the
     * caller's transaction and security context still exist.
     *
     * @param placeholders already built, so {@link ContractPlaceholders} never
     *                     touches a detached entity on a background thread
     */
    public record DocumentJob(
            Long contractId,
            String contractNumber,
            Long dealId,
            Long organizationId,
            Long userId,
            ContractType contractType,
            Map<String, String> placeholders,
            List<ContractAssembly.ResolvedLineItem> lineItems
    ) {
    }

    private final ContractTemplateService templateService;
    private final DocxGenerationService docxGenerationService;
    private final PdfConversionService pdfConversionService;
    private final DocumentStorageService storageService;
    private final ContractRecordService recordService;
    private final AuditLogService auditLogService;

    public ContractDocumentGenerator(ContractTemplateService templateService,
                                     DocxGenerationService docxGenerationService,
                                     PdfConversionService pdfConversionService,
                                     DocumentStorageService storageService,
                                     ContractRecordService recordService,
                                     AuditLogService auditLogService) {
        this.templateService = templateService;
        this.docxGenerationService = docxGenerationService;
        this.pdfConversionService = pdfConversionService;
        this.storageService = storageService;
        this.recordService = recordService;
        this.auditLogService = auditLogService;
    }

    /**
     * Renders, converts, stores and promotes the contract to GENERATED.
     *
     * Never throws. Nothing is waiting on the return value, so an exception
     * escaping here would go to the executor's default handler and vanish from
     * the caller's point of view — leaving a contract stuck in DRAFTING with no
     * explanation. Failures are written to the row instead, where
     * {@code GET /api/contracts/{id}} will show them.
     */
    @Async("documentGenerationExecutor")
    public void generate(DocumentJob job) {
        long start = System.currentTimeMillis();
        try {
            byte[] docx = docxGenerationService.generate(
                    templateService.load(job.contractType()), job.placeholders(), job.lineItems());

            StoredDocument storedDocx = storageService.store(
                    job.organizationId(), job.contractNumber(), "docx", docx);

            StoredDocument storedPdf = null;
            if (pdfConversionService.isEnabled()) {
                Path temporary = null;
                try {
                    temporary = storageService.writeTemporary(job.contractNumber() + ".docx", docx);
                    byte[] pdf = pdfConversionService.convertToPdf(temporary);
                    storedPdf = storageService.store(
                            job.organizationId(), job.contractNumber(), "pdf", pdf);
                } finally {
                    storageService.deleteTemporary(temporary);
                }
            } else {
                log.warn("PDF conversion is disabled; contract {} has a DOCX only", job.contractNumber());
            }

            Contract completed = recordService.attachDocuments(job.contractId(), storedDocx, storedPdf);
            recordService.mirrorOntoDeal(job.dealId(), completed.getStatus(), null);

            auditLogService.record(job.organizationId(), job.userId(), "CONTRACT_GENERATED",
                    "CONTRACT", String.valueOf(job.contractId()),
                    "Generated " + job.contractType().name() + " for deal " + job.dealId());

            log.info("Contract {} generated in {} ms", job.contractNumber(), System.currentTimeMillis() - start);

        } catch (ContractDocumentException e) {
            fail(job, e, "could not be produced");
        } catch (RuntimeException e) {
            // Anything unexpected — a dropped database connection during
            // attachDocuments is the one that has actually happened here. The
            // contract must still end up FAILED rather than DRAFTING for ever.
            fail(job, e, "failed unexpectedly");
        }
    }

    private void fail(DocumentJob job, Exception cause, String what) {
        log.error("Contract {} {}", job.contractNumber(), what, cause);
        try {
            recordService.markFailed(job.contractId(), cause.getMessage());
            recordService.mirrorOntoDeal(job.dealId(), ContractStatus.FAILED, null);
            auditLogService.record(job.organizationId(), job.userId(), "CONTRACT_GENERATION_FAILED",
                    "CONTRACT", String.valueOf(job.contractId()), cause.getMessage());
        } catch (RuntimeException e) {
            // The database is the thing that just failed, most likely. Nothing
            // further can be recorded, and the stranded DRAFTING row is handled
            // by the next generate: it supersedes any live contract that has no
            // document behind it.
            log.error("Contract {} failed, and the failure could not be recorded either",
                    job.contractNumber(), e);
        }
    }
}
