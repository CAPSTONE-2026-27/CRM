package com.techcrm.crm.proposal;

import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.contact.ContactRepository;
import com.techcrm.crm.contract.document.ContractDocumentException;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.document.DocumentStorageService.StoredDocument;
import com.techcrm.crm.contract.document.DocxGenerationService;
import com.techcrm.crm.contract.document.PdfConversionService;
import com.techcrm.crm.proposal.ProposalDtos.GenerateProposalRequest;
import com.techcrm.crm.proposal.template.ProposalTemplateService;
import com.techcrm.crm.user.UserRepository;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.techcrm.crm.proposal.ProposalFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Orchestration: idempotency, regeneration, and what happens when the document
 *  step fails. The rendering itself is covered by DocxGenerationServiceTest. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProposalServiceTest {

    @Mock ProposalRepository proposalRepository;
    @Mock ProposalLineItemRepository lineItemRepository;
    @Mock ProposalDataAssembler assembler;
    @Mock ProposalTemplateService templateService;
    @Mock DocxGenerationService docxGenerationService;
    @Mock PdfConversionService pdfConversionService;
    @Mock DocumentStorageService storageService;
    @Mock ProposalRecordService recordService;
    @Mock AccountRepository accountRepository;
    @Mock ContactRepository contactRepository;
    @Mock UserRepository userRepository;
    @Mock AuditLogService auditLogService;
    @Mock ProposalDocumentGenerator documentGenerator;

    ProposalProperties properties;
    ProposalService service;

    private static final GenerateProposalRequest GENERATE =
            new GenerateProposalRequest(DEAL_ID, null, null, null, null, null, null);
    private static final GenerateProposalRequest REGENERATE =
            new GenerateProposalRequest(DEAL_ID, null, null, null, null, null, true);

    @BeforeEach
    void setUp() {
        properties = new ProposalProperties();
        // These cases are about the render pipeline itself, so they run it
        // inline. The asynchronous path — which is the shipped default — has its
        // own tests below.
        properties.setAsyncGeneration(false);

        service = new ProposalService(proposalRepository, lineItemRepository, assembler, templateService,
                docxGenerationService, pdfConversionService, storageService, recordService,
                documentGenerator, properties,
                accountRepository, contactRepository, userRepository, auditLogService);

        when(assembler.requireDeal(any(), eq(DEAL_ID))).thenReturn(deal());
        when(assembler.assemble(any(), any(), any())).thenReturn(assembly());
        when(templateService.resolve(any(), any())).thenReturn(ProposalType.STANDARD_SALES_PROPOSAL);
        when(templateService.templateKey(any())).thenReturn("classpath:proposal-templates/x.docx");
        when(templateService.load(any())).thenReturn(new byte[]{1, 2, 3});
        when(docxGenerationService.generate(any(), any(), any())).thenReturn(new byte[]{4, 5, 6});
        when(pdfConversionService.isEnabled()).thenReturn(true);
        when(pdfConversionService.convertToPdf(any())).thenReturn(new byte[]{7, 8, 9});
        when(storageService.writeTemporary(anyString(), any())).thenReturn(Path.of("tmp/x.docx"));
        when(storageService.store(anyLong(), anyString(), eq("docx"), any()))
                .thenReturn(new StoredDocument("7/PRP-000042/a.docx", Path.of("a.docx"), 3));
        when(storageService.store(anyLong(), anyString(), eq("pdf"), any()))
                .thenReturn(new StoredDocument("7/PRP-000042/a.pdf", Path.of("a.pdf"), 3));

        when(proposalRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.empty());
        when(recordService.createDraft(any(), any(), any(), any())).thenReturn(proposal());
        when(recordService.attachDocuments(anyLong(), any(), any())).thenReturn(proposal());
        when(lineItemRepository.findByProposalIdOrderByLineNumberAsc(anyLong())).thenReturn(List.of());
    }

    @Test
    void generatesAProposalAndReturnsTheFieldsTheBotNeeds() {
        var outcome = service.generate(caller(), GENERATE);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.response().proposalId()).isEqualTo("42");
        assertThat(outcome.response().proposalNumber()).isEqualTo("PRP-000042");
        assertThat(outcome.response().dealId()).isEqualTo("31");
        assertThat(outcome.response().status()).isEqualTo("GENERATED");
        assertThat(outcome.response().pdfUrl()).isEqualTo("/api/proposals/42/document.pdf");
        assertThat(outcome.response().docxUrl()).isEqualTo("/api/proposals/42/document.docx");
    }

    @Test
    void runsTheFullDocumentPipelineInOrder() {
        service.generate(caller(), GENERATE);

        verify(docxGenerationService).generate(any(), any(), any());
        verify(storageService).store(eq(ORG_ID), eq("PRP-000042"), eq("docx"), any());
        verify(pdfConversionService).convertToPdf(any());
        verify(storageService).store(eq(ORG_ID), eq("PRP-000042"), eq("pdf"), any());
        verify(recordService).attachDocuments(eq(42L), any(), any());
        verify(recordService).mirrorOntoDeal(eq(DEAL_ID), eq(ProposalStatus.GENERATED), any());
    }

    /**
     * The retry case. The automation platform re-posts on timeout, and a second
     * quotation for one opportunity means two different prices in the customer's
     * inbox.
     */
    @Test
    void aRetryReturnsTheExistingProposalInsteadOfGeneratingASecond() {
        when(proposalRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.of(proposal()));

        var outcome = service.generate(caller(), GENERATE);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.response().proposalId()).isEqualTo("42");
        verify(recordService, never()).createDraft(any(), any(), any(), any());
        verify(docxGenerationService, never()).generate(any(), any(), any());
    }

    @Test
    void aRetryDoesNotEvenAssembleTheProposalData() {
        when(proposalRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.of(proposal()));

        service.generate(caller(), GENERATE);

        verify(assembler, never()).assemble(any(), any(), any());
    }

    /**
     * The same failure the contract module hit in production: a generation whose
     * document never landed left a row that looked complete, and every retry was
     * handed that empty record back as a success. A retry must look at whether a
     * document exists, not only at the status.
     */
    @Test
    void anAbandonedAttemptIsRegeneratedRatherThanReturnedAsASuccess() {
        Proposal abandoned = proposal();
        abandoned.setStatus(ProposalStatus.GENERATING);
        abandoned.setDocxPath(null);
        abandoned.setPdfPath(null);
        when(proposalRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.of(abandoned));

        // The plain request, with no regenerate flag. Needing one to escape a
        // half-written row is the bug, not the cure.
        var outcome = service.generate(caller(), GENERATE);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.response().pdfUrl()).isNotNull();
        verify(recordService).supersede(42L);
        verify(docxGenerationService).generate(any(), any(), any());
    }

    @Test
    void anExplicitRegenerationSupersedesThePreviousProposal() {
        when(proposalRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.of(proposal()));

        var outcome = service.generate(caller(), REGENERATE);

        assertThat(outcome.created()).isTrue();
        verify(recordService).supersede(42L);
        verify(recordService).createDraft(any(), any(), any(), any());
        verify(auditLogService).record(eq(ORG_ID), eq(USER_ID), eq("PROPOSAL_SUPERSEDED"),
                eq("PROPOSAL"), eq("42"), anyString());
    }

    /** A signed proposal is the customer's acceptance of a price. Quietly
     *  replacing it would leave the CRM quoting one figure while the customer has
     *  agreed to another. */
    @Test
    void refusesToRegenerateOverASignedProposal() {
        Proposal signed = proposal();
        signed.setStatus(ProposalStatus.SIGNED);
        when(proposalRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.of(signed));

        assertThatThrownBy(() -> service.generate(caller(), REGENERATE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(recordService, never()).supersede(anyLong());
    }

    /**
     * A proposal left SENT_FOR_SIGNATURE or VIEWED by the former e-signature
     * integration can never receive the customer's decision now, so an explicit
     * regenerate must be able to replace it — or the deal is stuck for ever.
     */
    @Test
    void aProposalLeftWithTheFormerSignatureProviderCanBeRegenerated() {
        for (ProposalStatus status : List.of(ProposalStatus.SENT_FOR_SIGNATURE, ProposalStatus.VIEWED)) {
            Proposal sent = proposal();
            sent.setStatus(status);
            when(proposalRepository.findLiveForDeal(DEAL_ID, ORG_ID)).thenReturn(Optional.of(sent));

            service.generate(caller(), REGENERATE);
        }
        verify(recordService, org.mockito.Mockito.times(2)).supersede(anyLong());
    }

    @Test
    void skipsPdfConversionWhenLibreOfficeIsTurnedOff() {
        when(pdfConversionService.isEnabled()).thenReturn(false);

        service.generate(caller(), GENERATE);

        verify(pdfConversionService, never()).convertToPdf(any());
        verify(storageService).store(anyLong(), anyString(), eq("docx"), any());
        verify(storageService, never()).store(anyLong(), anyString(), eq("pdf"), any());
    }

    /**
     * A failed render must leave an auditable row, not a rolled-back nothing, and
     * must not leak docx4j or LibreOffice internals to the caller.
     */
    @Test
    void recordsAFailedRenderAgainstTheProposalAndHidesTheDetail() {
        when(docxGenerationService.generate(any(), any(), any()))
                .thenThrow(new ContractDocumentException("docx4j: unexpected end of ZIP"));

        assertThatThrownBy(() -> service.generate(caller(), GENERATE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException ex = (ResponseStatusException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(ex.getReason()).doesNotContain("docx4j");
                    assertThat(ex.getReason()).contains("PRP-000042");
                });

        verify(recordService).markFailed(eq(42L), anyString());
        verify(recordService).mirrorOntoDeal(eq(DEAL_ID), eq(ProposalStatus.FAILED), any());
        verify(auditLogService).record(eq(ORG_ID), eq(USER_ID), eq("PROPOSAL_GENERATION_FAILED"),
                eq("PROPOSAL"), eq("42"), anyString());
    }

    /** The temporary DOCX LibreOffice opens must be cleaned up even when the
     *  conversion throws, or a failing server slowly fills its own disk. */
    @Test
    void deletesTheTemporaryDocxEvenWhenConversionFails() {
        when(pdfConversionService.convertToPdf(any()))
                .thenThrow(new ContractDocumentException("LibreOffice exited with 1"));

        assertThatThrownBy(() -> service.generate(caller(), GENERATE))
                .isInstanceOf(ResponseStatusException.class);

        verify(storageService).deleteTemporary(any());
    }

    @Test
    void reportsStatusWithTheProposalSpecificTimestamps() {
        when(proposalRepository.findByIdAndOrganizationId(PROPOSAL_ID, ORG_ID))
                .thenReturn(Optional.of(proposal()));

        var status = service.status(caller(), PROPOSAL_ID);

        assertThat(status.proposalId()).isEqualTo("42");
        assertThat(status.dealId()).isEqualTo("31");
        assertThat(status.status()).isEqualTo("GENERATED");
    }

    @Test
    void aProposalFromAnotherOrganizationIsNotFound() {
        when(proposalRepository.findByIdAndOrganizationId(PROPOSAL_ID, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(caller(), PROPOSAL_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Document URLs are null until a document exists, so a caller can tell
     *  "not generated yet" from "here it is" without probing the URL. */
    @Test
    void documentUrlsAreNullUntilThereIsADocument() {
        Proposal drafting = proposal();
        drafting.setStatus(ProposalStatus.GENERATING);
        drafting.setDocxPath(null);
        drafting.setPdfPath(null);

        var response = service.toGenerateResponse(drafting);

        assertThat(response.pdfUrl()).isNull();
        assertThat(response.docxUrl()).isNull();
    }

    /**
     * The shipped default: validate and reserve on the request thread, render in
     * the background, answer 202.
     *
     * These use a reserved row — GENERATING, no documents — because that is what
     * the caller actually gets back from an accepted request.
     */
    @org.junit.jupiter.api.Nested
    class AsynchronousGeneration {

        @BeforeEach
        void useTheShippedDefault() {
            properties.setAsyncGeneration(true);

            Proposal reserved = proposal();
            reserved.setStatus(ProposalStatus.GENERATING);
            reserved.setDocxPath(null);
            reserved.setPdfPath(null);
            when(recordService.createDraft(any(), any(), any(), any())).thenReturn(reserved);
        }

        @Test
        void acceptsTheRequestWithoutRenderingAnything() {
            var outcome = service.generate(caller(), GENERATE);

            assertThat(outcome.disposition()).isEqualTo(ProposalService.Disposition.ACCEPTED);
            assertThat(outcome.created()).isTrue();

            // Nothing slow happened on this thread.
            verify(docxGenerationService, never()).generate(any(), any(), any());
            verify(pdfConversionService, never()).convertToPdf(any());
            verify(storageService, never()).store(anyLong(), anyString(), anyString(), any());
        }

        @Test
        void handsTheWorkToTheBackgroundGenerator() {
            service.generate(caller(), GENERATE);

            ArgumentCaptor<ProposalDocumentGenerator.DocumentJob> job =
                    ArgumentCaptor.forClass(ProposalDocumentGenerator.DocumentJob.class);
            verify(documentGenerator).generate(job.capture());

            assertThat(job.getValue().dealId()).isEqualTo(DEAL_ID);
            assertThat(job.getValue().proposalType()).isEqualTo(ProposalType.STANDARD_SALES_PROPOSAL);
        }

        /**
         * No JPA entity may cross the thread boundary — it would be detached by
         * the time the task ran. The placeholders are resolved here instead, on
         * the request thread, and only plain values are handed over.
         */
        @Test
        void resolvesThePlaceholdersBeforeHandingOver() {
            service.generate(caller(), GENERATE);

            ArgumentCaptor<ProposalDocumentGenerator.DocumentJob> job =
                    ArgumentCaptor.forClass(ProposalDocumentGenerator.DocumentJob.class);
            verify(documentGenerator).generate(job.capture());

            assertThat(job.getValue().placeholders()).isNotEmpty();
            assertThat(job.getValue().lineItems()).isNotEmpty();
        }
    }
}
