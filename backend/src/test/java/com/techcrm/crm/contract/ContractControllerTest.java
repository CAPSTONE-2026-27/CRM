package com.techcrm.crm.contract;

import com.techcrm.crm.config.ApiExceptionHandler;
import com.techcrm.crm.contract.ContractService.GenerationOutcome;
import com.techcrm.crm.contract.document.DocumentStorageService;
import com.techcrm.crm.contract.signature.ContractSignatureService;
import com.techcrm.crm.contract.signature.ContractWebhookService;
import com.techcrm.crm.contract.signature.DocumensoProperties;
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
    @Mock ContractSignatureService signatureService;
    @Mock ContractWebhookService webhookService;
    @Mock DocumentStorageService storageService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var controller = new ContractController(contractService, signatureService, webhookService,
                storageService, new DocumensoProperties());

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
                .thenReturn(new GenerationOutcome(generated("GENERATED"), true));

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

    /** The status code is how a retry is distinguished, so the body did not have
     *  to grow a field the workflow would have to know about. */
    @Test
    void anAbsorbedRetryReturns200WithTheSameBody() throws Exception {
        when(contractService.generate(any(), any()))
                .thenReturn(new GenerationOutcome(generated("SENT"), false));

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
                .thenReturn(new GenerationOutcome(generated("GENERATED"), true));

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

    @Test
    void sendForSignatureReturnsTheSigningDetails() throws Exception {
        when(signatureService.sendForSignature(any(), any())).thenReturn(
                new ContractDtos.SendForSignatureResponse("42", "SENT", "https://sign.example/s/tok", "881"));

        mvc.perform(post("/api/contracts/send-for-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.signUrl").value("https://sign.example/s/tok"))
                .andExpect(jsonPath("$.documensoId").value("881"));
    }

    @Test
    void sendForSignatureRejectsAnInvalidRecipientEmail() throws Exception {
        mvc.perform(post("/api/contracts/send-for-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\":42,\"recipientEmail\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    /** The raw body reaches the service verbatim, and the configured header name
     *  is what the secret is read from. */
    @Test
    void theWebhookPassesTheRawBodyAndSecretHeaderThrough() throws Exception {
        when(webhookService.handle(anyString(), anyString()))
                .thenReturn(new ContractDtos.WebhookAck("processed", "42", "SIGNED"));

        mvc.perform(post("/api/contracts/sign-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Documenso-Secret", "whsec-test")
                        .content("{\"event\":\"DOCUMENT_COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("processed"))
                .andExpect(jsonPath("$.status").value("SIGNED"));

        org.mockito.Mockito.verify(webhookService)
                .handle("{\"event\":\"DOCUMENT_COMPLETED\"}", "whsec-test");
    }

    @Test
    void theWebhookReportsARedeliveryAsSuch() throws Exception {
        when(webhookService.handle(anyString(), any()))
                .thenReturn(new ContractDtos.WebhookAck("duplicate", "42", "SIGNED"));

        mvc.perform(post("/api/contracts/sign-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"DOCUMENT_COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("duplicate"));
    }
}
