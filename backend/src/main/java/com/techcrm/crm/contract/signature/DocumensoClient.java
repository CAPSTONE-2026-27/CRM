package com.techcrm.crm.contract.signature;

import com.techcrm.crm.contract.document.PdfPageCount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to Documenso.
 *
 * Three calls, in the order Documenso's v1 API requires them:
 *
 * <pre>
 *   POST /api/v1/documents        -> { documentId, uploadUrl, recipients[] }
 *   PUT  &lt;uploadUrl&gt;          -> the PDF bytes
 *   POST /api/v1/documents/{id}/send
 * </pre>
 *
 * The upload URL is presigned and absolute — on Documenso Cloud it points at
 * their object store, not at the API host — so it gets its own client with no
 * base URL and no Authorization header. Sending the API key to whatever host
 * that URL names would leak it.
 *
 * Written against v1 because the project had no prior Documenso integration to
 * match; see {@link DocumensoProperties}. Every failure becomes a
 * {@link DocumensoException} carrying the detail for the log, never for the API
 * response.
 */
@Component
public class DocumensoClient {

    private static final Logger log = LoggerFactory.getLogger(DocumensoClient.class);

    private final DocumensoProperties properties;
    private final RestClient api;
    private final RestClient upload;

    public DocumensoClient(DocumensoProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getRequestTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getRequestTimeoutMs()));

        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        this.api = builder.clone()
                .baseUrl(properties.getBaseUrl() == null ? "" : trimTrailingSlash(properties.getBaseUrl()))
                .build();
        this.upload = builder.clone().build();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** What the CRM hands over. The PDF, not the DOCX: the customer signs the
     *  rendered document, and Documenso's own preview is a PDF renderer. */
    public record SignatureRequest(
            String title,
            String externalId,
            String recipientName,
            String recipientEmail,
            String subject,
            String message,
            byte[] pdf
    ) {
    }

    /** What comes back, reduced to the three things the CRM stores.
     *  {@code signUrl} is null on installations that do not return one — the
     *  customer then reaches the document only through Documenso's own email. */
    public record SignatureResult(String documentId, String recipientId, String signUrl) {
    }

    public SignatureResult send(SignatureRequest request) {
        if (!isConfigured()) {
            throw new DocumensoException("Documenso is not configured (documenso.base-url / documenso.api-key)");
        }

        Map<String, Object> created = createDocument(request);

        String documentId = asString(firstPresent(created, "documentId", "id"));
        String uploadUrl = asString(created.get("uploadUrl"));
        if (documentId == null || uploadUrl == null) {
            throw new DocumensoException(
                    "Documenso did not return a documentId and uploadUrl; got keys " + created.keySet());
        }

        uploadPdf(uploadUrl, request.pdf());

        Recipient recipient = firstRecipient(created);
        // Documenso refuses to send a document whose signer has nothing to sign:
        // "Signers must have at least one signature field." Creating the
        // recipient is not enough, so the field is placed before sending rather
        // than leaving the document stuck in DRAFT.
        addSignatureBlock(documentId, recipient.id(), request.pdf());

        sendDocument(documentId);
        log.info("Documenso document {} created, signature block placed and sent for {}",
                documentId, request.externalId());
        return new SignatureResult(documentId, recipient.id(), recipient.signUrl());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createDocument(SignatureRequest request) {
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("name", request.recipientName());
        recipient.put("email", request.recipientEmail());
        recipient.put("role", "SIGNER");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("subject", request.subject());
        meta.put("message", request.message());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", request.title());
        // Our own reference travels with the document, so a webhook can be tied
        // back to a contract even if the document id were ever lost on our side.
        body.put("externalId", request.externalId());
        body.put("recipients", List.of(recipient));
        body.put("meta", meta);

        try {
            Map<String, Object> response = api.post()
                    .uri("/api/v1/documents")
                    .header(properties.getApiKeyHeader(), authorizationValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new DocumensoException(
                                "Documenso rejected the document creation with "
                                        + res.getStatusCode() + " " + errorBody(res));
                    })
                    .body(Map.class);

            if (response == null) {
                throw new DocumensoException("Documenso returned an empty response to document creation");
            }
            return response;
        } catch (RestClientException e) {
            throw new DocumensoException("Could not reach Documenso to create the document: " + e.getMessage(), e);
        }
    }

    /**
     * Places the signer's signature block on the last page.
     *
     * A signature box alone is what Documenso strictly requires, but on its own
     * it reads as a stray widget in the middle of a contract. A NAME and a DATE
     * underneath it make it a signature block, matching the "Signed for and on
     * behalf of" wording the templates already print.
     *
     * The page is resolved from the generated PDF rather than assumed: the
     * templates run to two pages today, but a long address or a multi-line
     * schedule pushes that to three, and a signature field stranded on page one
     * of a three-page contract is exactly the complaint this fixes.
     */
    private void addSignatureBlock(String documentId, String recipientId, byte[] pdf) {
        if (recipientId == null) {
            throw new DocumensoException(
                    "Documenso returned no recipient id for document " + documentId
                            + ", so no signature field could be placed");
        }

        DocumensoProperties.SignatureField layout = properties.getSignatureField();
        int page = resolvePage(layout, pdf);

        addField(documentId, recipientId, "SIGNATURE", page,
                layout.getX(), layout.getY(), layout.getWidth(), layout.getHeight());

        if (layout.isIncludeNameAndDate()) {
            int row = layout.getY() + layout.getHeight() + layout.getRowGap();
            addField(documentId, recipientId, "NAME", page,
                    layout.getX(), row, layout.getWidth(), layout.getRowHeight());
            addField(documentId, recipientId, "DATE", page,
                    layout.getX(), row + layout.getRowHeight() + layout.getRowGap(),
                    layout.getWidth(), layout.getRowHeight());
        }
    }

    /** Configured page, or the document's last one. Falls back to page 1 when the
     *  page count cannot be read — an awkwardly placed field beats a failed send. */
    private int resolvePage(DocumensoProperties.SignatureField layout, byte[] pdf) {
        if (layout.getPage() > 0) {
            return layout.getPage();
        }
        int pages = PdfPageCount.of(pdf);
        if (pages == PdfPageCount.UNKNOWN) {
            log.warn("Could not determine the contract PDF's page count; "
                    + "placing the signature block on page 1");
            return 1;
        }
        return pages;
    }

    private void addField(String documentId, String recipientId, String type,
                          int page, int x, int y, int width, int height) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientId", Long.parseLong(recipientId));
        body.put("type", type);
        body.put("pageNumber", page);
        body.put("pageX", x);
        body.put("pageY", y);
        body.put("pageWidth", width);
        body.put("pageHeight", height);

        try {
            api.post()
                    .uri("/api/v1/documents/{id}/fields", documentId)
                    .header(properties.getApiKeyHeader(), authorizationValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new DocumensoException("Documenso rejected the " + type
                                + " field for document " + documentId + ": "
                                + res.getStatusCode() + " " + errorBody(res));
                    })
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new DocumensoException("Could not add the " + type + " field to document "
                    + documentId + ": " + e.getMessage(), e);
        }
    }

    private void uploadPdf(String uploadUrl, byte[] pdf) {
        try {
            upload.put()
                    // URI.create, not the String overload. uri(String) treats its
                    // argument as a URI template and re-encodes it -- and this URL
                    // is an S3 presigned one whose X-Amz-Credential already contains
                    // %2F-escaped slashes. Re-encoding turns those into %252F, S3
                    // reads a malformed credential, and the upload dies with a 400
                    // "AuthorizationQueryParametersError" that looks nothing like
                    // the encoding bug it is. A pre-parsed URI is passed through
                    // untouched.
                    .uri(URI.create(uploadUrl))
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new DocumensoException("Uploading the contract PDF failed with "
                                + res.getStatusCode() + " " + errorBody(res));
                    })
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new DocumensoException("Could not upload the contract PDF to Documenso: " + e.getMessage(), e);
        }
    }

    private void sendDocument(String documentId) {
        try {
            api.post()
                    .uri("/api/v1/documents/{id}/send", documentId)
                    .header(properties.getApiKeyHeader(), authorizationValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("sendEmail", properties.isSendEmail()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new DocumensoException(
                                "Documenso refused to send document " + documentId + ": "
                                        + res.getStatusCode() + " " + errorBody(res));
                    })
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new DocumensoException("Could not ask Documenso to send the document: " + e.getMessage(), e);
        }
    }

    /**
     * The provider's own explanation, which is the only thing that makes one of
     * these failures diagnosable -- a bare "400 BAD_REQUEST" sent us looking at
     * the API key when the real answer was "signers must have at least one
     * signature field". Goes to the log and the audit trail only; callers still
     * translate it into a fixed message for the API response. Truncated because
     * an HTML error page from a proxy would otherwise fill the audit row.
     */
    private static String errorBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            String body = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("\s+", " ").trim();
            return body.length() <= 500 ? body : body.substring(0, 500) + "...";
        } catch (Exception e) {
            return "(no response body)";
        }
    }

    private record Recipient(String id, String signUrl) {
    }

    @SuppressWarnings("unchecked")
    private Recipient firstRecipient(Map<String, Object> created) {
        Object recipients = created.get("recipients");
        if (!(recipients instanceof List<?> list) || list.isEmpty()) {
            return new Recipient(null, null);
        }
        if (!(list.get(0) instanceof Map<?, ?> first)) {
            return new Recipient(null, null);
        }
        Map<String, Object> recipient = (Map<String, Object>) first;
        return new Recipient(
                asString(firstPresent(recipient, "recipientId", "id")),
                asString(firstPresent(recipient, "signingUrl", "signUrl", "url")));
    }

    /**
     * Documenso v1 takes the raw API key. The prefix exists for deployments
     * fronted by a proxy that insists on {@code Bearer } — the alternative was
     * guessing one scheme and silently 401-ing on the other half of them.
     *
     * The separating space is added here rather than being expected at the end
     * of the configured value: Spring's property binder trims trailing
     * whitespace, so a configured "Bearer " arrives as "Bearer" and would
     * produce the header "Bearersk_live_..." — a 401 with no clue as to why.
     */
    private String authorizationValue() {
        String prefix = properties.getApiKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            return properties.getApiKey();
        }
        return prefix.trim() + " " + properties.getApiKey();
    }

    /** Documenso has renamed these keys across versions, so both spellings are
     *  accepted rather than pinning the integration to one point release. */
    private static Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Document and recipient ids come back as JSON numbers but are stored and
     *  compared as text, so they are normalised once here. */
    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
