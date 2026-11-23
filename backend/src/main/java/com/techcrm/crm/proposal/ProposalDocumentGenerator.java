package com.techcrm.crm.proposal;

import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.contract.ContractAssembly;
import com.techcrm.crm.contract.document.ContractDocumentException;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.document.DocumentStorageService.StoredDocument;
import com.techcrm.crm.contract.document.DocxGenerationService;
import com.techcrm.crm.contract.document.PdfConversionService;
import com.techcrm.crm.proposal.template.ProposalTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Renders a proposal's documents off the request thread.
 *
 * The mirror of {@link com.techcrm.crm.contract.ContractDocumentGenerator}, and
 * it exists for the same hard external limit: SAP Build Process Automation
 * abandons an HTTP call at 30 seconds, and a cold generation takes longer than
 * that — a docx4j render plus a LibreOffice subprocess. Holding the connection
 * open until it finished meant the bot timed out and retried something that was
 * still running.
 *
 * So {@code POST /generate} now validates, reserves the proposal row and returns
 * 202 in about a second, and the slow half happens here. Everything the task
 * needs is passed in as plain values — no JPA entities cross the thread
 * boundary, because they would be detached by the time this ran and any lazy
 * access would fail somewhere unhelpful.
 *
 * A distinct bean rather than an {@code @Async} method on {@code ProposalService}
 * for the usual proxying reason: Spring cannot intercept a call an object makes
 * to itself, so the annotation would be silently ignored and the work would run
 * on the request thread exactly as before — the failure mode being that nothing
 * looks wrong except the clock.
 *
 * It shares {@code documentGenerationExecutor} with the contract generator
 * rather than taking a pool of its own. The two never run for the same deal at
 * the same time, the work is identical in shape and cost, and a second pool
 * would double the number of concurrent LibreOffice subprocesses a single
 * machine has to survive.
 */
@Service
public class ProposalDocumentGenerator {

    private static final Logger log = LoggerFactory.getLogger(ProposalDocumentGenerator.class);

    /**
     * Everything the render needs, resolved on the request thread while the
     * caller's transaction and security context still exist.
     *
     * @param placeholders already built, so {@link ProposalPlaceholders} never
     *                     touches a detached entity on a background thread
     * @param lineItems    already converted to the contract module's shape, which
     *                     is what {@link DocxGenerationService} takes
     */
    public record DocumentJob(
            Long proposalId,
            String proposalNumber,
            Long dealId,
            Long organizationId,
            Long userId,
            ProposalType proposalType,
            Map<String, String> placeholders,
            List<ContractAssembly.ResolvedLineItem> lineItems
    ) {
    }

    private final ProposalTemplateService templateService;
    private final DocxGenerationService docxGenerationService;
    private final PdfConversionService pdfConversionService;
    private final DocumentStorageService storageService;
    private final ProposalRecordService recordService;
    private final AuditLogService auditLogService;

    public ProposalDocumentGenerator(ProposalTemplateService templateService,
                                     DocxGenerationService docxGenerationService,
                                     PdfConversionService pdfConversionService,
                                     DocumentStorageService storageService,
                                     ProposalRecordService recordService,
                                     AuditLogService auditLogService) {
        this.templateService = templateService;
        this.docxGenerationService = docxGenerationService;
        this.pdfConversionService = pdfConversionService;
        this.storageService = storageService;
        this.recordService = recordService;
        this.auditLogService = auditLogService;
    }

    /**
     * Renders, converts, stores and promotes the proposal to GENERATED.
     *
     * Never throws. Nothing is waiting on the return value, so an exception
     * escaping here would go to the executor's default handler and vanish from
     * the caller's point of view — leaving a proposal stuck in GENERATING with
     * no explanation. Failures are written to the row instead, where
     * {@code GET /api/proposals/{id}} will show them.
     */
    @Async("documentGenerationExecutor")
    public void generate(DocumentJob job) {
        long start = System.currentTimeMillis();
        try {
            byte[] docx = docxGenerationService.generate(
                    templateService.load(job.proposalType()), job.placeholders(), job.lineItems());

            StoredDocument storedDocx = storageService.store(
                    job.organizationId(), job.proposalNumber(), "docx", docx);

            StoredDocument storedPdf = null;
            if (pdfConversionService.isEnabled()) {
                Path temporary = null;
                try {
                    temporary = storageService.writeTemporary(job.proposalNumber() + ".docx", docx);
                    byte[] pdf = pdfConversionService.convertToPdf(temporary);
                    storedPdf = storageService.store(
                            job.organizationId(), job.proposalNumber(), "pdf", pdf);
                } finally {
                    storageService.deleteTemporary(temporary);
                }
            } else {
                log.warn("PDF conversion is disabled; proposal {} has a DOCX only", job.proposalNumber());
            }

            Proposal completed = recordService.attachDocuments(job.proposalId(), storedDocx, storedPdf);
            recordService.mirrorOntoDeal(job.dealId(), completed.getStatus(), null);

            auditLogService.record(job.organizationId(), job.userId(), "PROPOSAL_GENERATED",
                    "PROPOSAL", String.valueOf(job.proposalId()),
                    "Generated " + job.proposalType().name() + " for deal " + job.dealId());

            log.info("Proposal {} generated in {} ms", job.proposalNumber(), System.currentTimeMillis() - start);

        } catch (ContractDocumentException e) {
            fail(job, e, "could not be produced");
        } catch (RuntimeException e) {
            // Anything unexpected — a dropped database connection during
            // attachDocuments is the one that has actually happened on the
            // contract side. The proposal must still end up FAILED rather than
            // GENERATING for ever.
            fail(job, e, "failed unexpectedly");
        }
    }

    private void fail(DocumentJob job, Exception cause, String what) {
        log.error("Proposal {} {}", job.proposalNumber(), what, cause);
        try {
            recordService.markFailed(job.proposalId(), cause.getMessage());
            recordService.mirrorOntoDeal(job.dealId(), ProposalStatus.FAILED, null);
            auditLogService.record(job.organizationId(), job.userId(), "PROPOSAL_GENERATION_FAILED",
                    "PROPOSAL", String.valueOf(job.proposalId()), cause.getMessage());
        } catch (RuntimeException e) {
            // The database is the thing that just failed, most likely. Nothing
            // further can be recorded, and the stranded GENERATING row is handled
            // by the next generate: it supersedes any live proposal that has no
            // document behind it.
            log.error("Proposal {} failed, and the failure could not be recorded either",
                    job.proposalNumber(), e);
        }
    }
}
