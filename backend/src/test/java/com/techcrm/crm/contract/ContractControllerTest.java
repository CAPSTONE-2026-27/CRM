package com.techcrm.crm.contract;

import com.techcrm.crm.config.ApiExceptionHandler;
import com.techcrm.crm.contract.ContractService.GenerationOutcome;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.email.ContractEmailService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.techcrm.crm.contract.ContractFixtures.*;
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
class ContractControllerTest {

    @Mock ContractService contractService;
    @Mock DocumentStorageService storageService;
    @Mock ContractEmailService emailService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var controller = new ContractController(contractService, storageService, emailService);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller(), null, List.of()));

        when(contractService.require(any(), anyLong())).thenReturn(contract());
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.read(anyString())).thenReturn("%PDF-1.4 fake".getBytes());
    }

    private static ContractDtos.GenerateContractResponse generated(String status) {
        return new ContractDtos.GenerateContractResponse(
                "42", "CTR-000042", "31", "OPP-000031", "STANDARD_SALES_AGREEMENT", status,
                "/api/contracts/42/document.pdf", "/api/contracts/42/document.docx");
    }

    @Test
    void generateReturns201AndTheFourFieldsTheWorkflowConsumes() throws Exception {
        when(contractService.generate(any(), any()))
                .thenReturn(new GenerationOutcome(generated("GENERATED"), ContractService.Disposition.CREATED));

        mvc.perform(post("/api/contracts/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealId\":31}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractId").value("42"))
                .andExpect(jsonPath("$.dealId").value("31"))
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.pdfUrl").value("/api/contracts/42/document.pdf"))
                .andExpect(jsonPath("$.docxUrl").value("/api/contracts/42/document.docx"));
    }

    /**
     * The shipped default, and the reason it exists: SAP Build Process
     * Automation abandons an HTTP call at 30 seconds, and a cold generation
     * takes longer. 202 with DRAFTING and null document URLs is what tells the
     * bot to poll rather than wait.
     */
    @Test
    void generateReturns202WhenTheWorkWasQueued() throws Exception {
        var accepted = new ContractDtos.GenerateContractResponse(
                "42", "CTR-000042", "31", "OPP-000031", "STANDARD_SALES_AGREEMENT",
                "DRAFTING", null, null);
        when(contractService.generate(any(), any()))
                .thenReturn(new GenerationOutcome(accepted, ContractService.Disposition.ACCEPTED));

        mvc.perform(post("/api/contracts/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealId\":31}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.contractId").value("42"))
                .andExpect(jsonPath("$.status").value("DRAFTING"))
                .andExpect(jsonPath("$.pdfUrl").doesNotExist())
                .andExpect(jsonPath("$.docxUrl").doesNotExist());
    }

    /** The status code is how a retry is distinguished, so the body did not have
     *  to grow a field the workflow would have to know about. */
    @Test
    void anAbsorbedRetryReturns200WithTheSameBody() throws Exception {
        when(contractService.generate(any(), any()))
                .thenReturn(new GenerationOutcome(generated("SENT"), ContractService.Disposition.EXISTING));

        mvc.perform(post("/api/contracts/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealId\":31}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractId").value("42"))
                .andExpect(jsonPath("$.status").value("SENT"));
    }

    /** dealId is required and must be a number — a bad one is rejected before
     *  any lookup happens. */
    @Test
    void generateRejectsAMissingDealId() throws Exception {
        mvc.perform(post("/api/contracts/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /** The CRM's own string-id responses hand back "31"; Jackson coerces it, so
     *  the bot can post straight back what it read. */
    @Test
    void generateAcceptsAStringDealIdBecauseTheCrmReturnsIdsAsStrings() throws Exception {
        when(contractService.generate(any(), any()))
                .thenReturn(new GenerationOutcome(generated("GENERATED"), ContractService.Disposition.CREATED));

        mvc.perform(post("/api/contracts/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealId\":\"31\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void statusIsReachableAtItsDocumentedPath() throws Exception {
        when(contractService.status(any(), anyLong())).thenReturn(
                new ContractDtos.ContractStatusResponse("42", "31", "SENT", null, null, null, null));

        mvc.perform(get("/api/contracts/42/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
    }

    /**
     * The literal ".pdf" suffix in the path is the risky part of this mapping,
     * so it is asserted rather than assumed.
     */
    @Test
    void downloadsThePdfAsAnAttachment() throws Exception {
        mvc.perform(get("/api/contracts/42/document.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("CTR-000042.pdf")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void downloadsTheDocxWithTheOpenXmlContentType() throws Exception {
        mvc.perform(get("/api/contracts/42/document.docx"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("CTR-000042.docx")));
    }

    /** A DOCX-only contract must 404 on the PDF rather than serve something
     *  else or 500. */
    @Test
    void aContractWithNoPdfReturns404ForThePdf() throws Exception {
        Contract docxOnly = contract();
        docxOnly.setPdfPath(null);
        when(contractService.require(any(), anyLong())).thenReturn(docxOnly);

        mvc.perform(get("/api/contracts/42/document.pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void aStoredDocumentMissingFromDiskReturns404() throws Exception {
        when(storageService.exists(anyString())).thenReturn(false);

        mvc.perform(get("/api/contracts/42/document.docx"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownContractReturns404() throws Exception {
        when(contractService.require(any(), anyLong()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found"));

        mvc.perform(get("/api/contracts/999/document.pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Contract not found"));
    }

    /* ------------------------------------------------------- send email */

    @Test
    void sendEmailReturnsTheSendResult() throws Exception {
        when(emailService.send(any(), anyLong(), any())).thenReturn(new ContractDtos.ContractEmailResponse(
                "5", "CTR-000005", "SENT", "you@company.com", "uuid-123", 82637,
                java.time.OffsetDateTime.parse("2026-09-17T10:00:00Z")));

        mvc.perform(post("/api/contracts/5/send-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientEmail\":\"you@company.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentTo").value("you@company.com"))
                .andExpect(jsonPath("$.mailjetMessageId").value("uuid-123"))
                .andExpect(jsonPath("$.attachmentBytes").value(82637));

        org.mockito.Mockito.verify(emailService).send(any(), org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.argThat(r -> "you@company.com".equals(r.recipientEmail())));
    }

    /** The automation platform can call it with no body at all. */
    @Test
    void sendEmailNeedsNoBody() throws Exception {
        when(emailService.send(any(), anyLong(), any())).thenReturn(new ContractDtos.ContractEmailResponse(
                "5", "CTR-000005", "SENT", "meera@company.com", "uuid-123", 82637, null));

        mvc.perform(post("/api/contracts/5/send-email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void sendEmailRejectsAnInvalidRecipientEmail() throws Exception {
        mvc.perform(post("/api/contracts/5/send-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientEmail\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }
}
