package com.techcrm.crm.proposal;

import com.techcrm.crm.config.ApiExceptionHandler;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.proposal.ProposalDtos.GenerateProposalResponse;
import com.techcrm.crm.proposal.email.ProposalEmailService;
import com.techcrm.crm.proposal.ProposalService.GenerationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static com.techcrm.crm.proposal.ProposalFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP surface: that the declared paths really map, that downloads carry the
 * right headers, and that a retry is answered 200 where a first call is 201.
 *
 * Standalone MockMvc rather than @SpringBootTest — the application context needs
 * a database, and none of what is asserted here depends on one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProposalControllerTest {

    @Mock ProposalService proposalService;
    @Mock DocumentStorageService storageService;
    @Mock ProposalEmailService emailService;

    MockMvc mvc;

    private static GenerateProposalResponse generated() {
        return new GenerateProposalResponse("42", "PRP-000042", "31", "OPP-000031",
                "STANDARD_SALES_PROPOSAL", "GENERATED",
                "/api/proposals/42/document.pdf", "/api/proposals/42/document.docx");
    }

    @BeforeEach
    void setUp() {
        var controller = new ProposalController(proposalService, storageService, emailService);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        when(proposalService.require(any(), anyLong())).thenReturn(proposal());
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.read(anyString())).thenReturn("%PDF-1.7 pretend".getBytes());
    }

    /** A first call created something. */
    @Test
    void generateAnswers201WhenAProposalWasCreated() throws Exception {
        when(proposalService.generate(any(), any())).thenReturn(new GenerationOutcome(generated(), ProposalService.Disposition.CREATED));

        mvc.perform(post("/api/proposals/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealId\":31}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposalId").value("42"))
                .andExpect(jsonPath("$.proposalNumber").value("PRP-000042"))
                .andExpect(jsonPath("$.status").value("GENERATED"));
    }

    /** A retry did not. Same body either way, so a caller that ignores the
     *  status code still gets a usable answer. */
    @Test
    void generateAnswers200WhenARetryWasAbsorbed() throws Exception {
        when(proposalService.generate(any(), any())).thenReturn(new GenerationOutcome(generated(), ProposalService.Disposition.EXISTING));

        mvc.perform(post("/api/proposals/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealId\":31}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalId").value("42"));
    }

    /**
     * The shipped default. A cold render outruns the 30 seconds SAP Build
     * Process Automation waits, so the work is queued and the bot polls. 202
     * with GENERATING and null document URLs is what tells it to.
     */
    @Test
    void generateReturns202WhenTheWorkWasQueued() throws Exception {
        var accepted = new ProposalDtos.GenerateProposalResponse(
                "42", "PRP-000042", "31", "OPP-000031",
                "STANDARD_SALES_PROPOSAL", "GENERATING", null, null);
        when(proposalService.generate(any(), any()))
                .thenReturn(new GenerationOutcome(accepted, ProposalService.Disposition.ACCEPTED));

        mvc.perform(post("/api/proposals/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealId\":31}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.pdfUrl").doesNotExist());
    }

    /** dealId is @NotNull, so a body without it never reaches the service. */
    @Test
    void generateRejectsABodyWithNoDealId() throws Exception {
        mvc.perform(post("/api/proposals/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /** The CRM's string ids come back as strings, so both forms are accepted. */
    @Test
    void generateAcceptsADealIdAsAStringOrANumber() throws Exception {
        when(proposalService.generate(any(), any())).thenReturn(new GenerationOutcome(generated(), ProposalService.Disposition.CREATED));

        mvc.perform(post("/api/proposals/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealId\":\"31\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void aNonNumericDealIdIsA400BeforeAnyLookup() throws Exception {
        mvc.perform(post("/api/proposals/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealId\":\"OPP-000031\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusIsMappedAndSmall() throws Exception {
        when(proposalService.status(any(), anyLong())).thenReturn(
                new ProposalDtos.ProposalStatusResponse("42", "31", "SENT_FOR_SIGNATURE",
                        null, null, null, null));

        mvc.perform(get("/api/proposals/42/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT_FOR_SIGNATURE"))
                .andExpect(jsonPath("$.dealId").value("31"));
    }

    /**
     * The download names the file by proposal number, not by the stored name —
     * which carries a generation timestamp that means nothing to whoever
     * downloads it — and forbids caching, because a regeneration reuses the URL.
     */
    @Test
    void thePdfDownloadIsNamedAndUncacheable() throws Exception {
        mvc.perform(get("/api/proposals/42/document.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("PRP-000042.pdf")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void theDocxDownloadCarriesTheOpenXmlContentType() throws Exception {
        mvc.perform(get("/api/proposals/42/document.docx"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("PRP-000042.docx")));
    }

    /** A path recorded in the database whose file is gone is a 404, not a 500. */
    @Test
    void aMissingFileIsA404() throws Exception {
        when(storageService.exists(anyString())).thenReturn(false);

        mvc.perform(get("/api/proposals/42/document.pdf"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aProposalWithNoDocumentYetIsA404() throws Exception {
        Proposal drafting = proposal();
        drafting.setPdfPath(null);
        when(proposalService.require(any(), anyLong())).thenReturn(drafting);

        mvc.perform(get("/api/proposals/42/document.pdf"))
                .andExpect(status().isNotFound());
    }

    /** Service-layer failures reach the caller through the CRM's existing
     *  handler, as {"error": "..."} rather than a stack trace. */
    @Test
    void serviceFailuresGoThroughTheExistingExceptionHandler() throws Exception {
        when(proposalService.generate(any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "Deal is in stage QUALIFICATION"));

        mvc.perform(post("/api/proposals/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealId\":31}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Deal is in stage QUALIFICATION"));
    }

    /* ------------------------------------------------------- send email */

    @Test
    void sendEmailReturnsTheSendResult() throws Exception {
        when(emailService.send(any(), anyLong(), any())).thenReturn(new ProposalDtos.ProposalEmailResponse(
                "42", "PRP-000042", "SENT", "you@company.com", "uuid-123", 74211,
                java.time.OffsetDateTime.parse("2026-09-22T10:00:00Z")));

        mvc.perform(post("/api/proposals/42/send-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientEmail\":\"you@company.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentTo").value("you@company.com"))
                .andExpect(jsonPath("$.mailjetMessageId").value("uuid-123"))
                .andExpect(jsonPath("$.attachmentBytes").value(74211));

        org.mockito.Mockito.verify(emailService).send(any(), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.argThat(r -> "you@company.com".equals(r.recipientEmail())));
    }

    /** The automation platform can call it with no body at all. */
    @Test
    void sendEmailNeedsNoBody() throws Exception {
        when(emailService.send(any(), anyLong(), any())).thenReturn(new ProposalDtos.ProposalEmailResponse(
                "42", "PRP-000042", "SENT", "rajesh@company.com", "uuid-123", 74211, null));

        mvc.perform(post("/api/proposals/42/send-email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void sendEmailRejectsAnInvalidRecipientEmail() throws Exception {
        mvc.perform(post("/api/proposals/42/send-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientEmail\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }
}
