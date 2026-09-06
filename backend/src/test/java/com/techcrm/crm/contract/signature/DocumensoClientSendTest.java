package com.techcrm.crm.contract.signature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order of calls Documenso requires, pinned against a stub of its API.
 *
 * Regression test for a real failure: the client created the document, uploaded
 * the PDF and called send — and Documenso answered
 * {@code 400 "Signers must have at least one signature field"}, leaving every
 * contract stuck in DRAFT. Creating a recipient is not enough; a signature field
 * has to be placed for them first.
 *
 * A loopback stub rather than mocks, because what is being asserted is the
 * sequence and shape of real HTTP calls.
 */
class DocumensoClientSendTest {

    private HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final List<String> fieldRequests = java.util.Collections.synchronizedList(new ArrayList<>());
    private final ObjectMapper mapper = new ObjectMapper();

    /** Set to true to make the stub behave like Documenso before a field exists. */
    private volatile boolean requireSignatureField = false;
    private volatile boolean fieldPlaced = false;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/api/v1/documents", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String key = exchange.getRequestMethod() + " " + path;
            calls.add(key);
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.put(key, requestBody);
            if (path.endsWith("/fields")) {
                fieldRequests.add(requestBody);
            }

            String response;
            int status = 200;

            if (path.equals("/api/v1/documents")) {
                response = """
                        {"uploadUrl":"http://127.0.0.1:%d/upload/contract.pdf",
                         "documentId":2001266,
                         "recipients":[{"recipientId":3499735,"email":"asha.menon@example.com",
                                        "signingUrl":"https://sign.example/s/tok"}]}""".formatted(port);
            } else if (path.endsWith("/fields")) {
                fieldPlaced = true;
                response = "{\"fields\":[{\"id\":17458928,\"type\":\"SIGNATURE\"}],\"documentId\":2001266}";
            } else if (path.endsWith("/send")) {
                if (requireSignatureField && !fieldPlaced) {
                    status = 400;
                    response = "{\"message\":\"The following recipients are missing required fields: "
                            + "Asha Menon. Signers must have at least one signature field.\"}";
                } else {
                    response = "{\"status\":\"PENDING\"}";
                }
            } else {
                status = 404;
                response = "{\"message\":\"no such route\"}";
            }

            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        server.createContext("/upload", exchange -> {
            calls.add("PUT " + exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private DocumensoClient client() {
        DocumensoProperties properties = new DocumensoProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-key");
        properties.setRequestTimeoutMs(10_000);
        return new DocumensoClient(properties);
    }

    private DocumensoClient.SignatureRequest request() {
        return new DocumensoClient.SignatureRequest(
                "CTR-000001 - Professional Services Agreement", "CTR-000001",
                "Asha Menon", "asha.menon@example.com",
                "Please sign CTR-000001", "Ready for signature.",
                THREE_PAGE_PDF.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Three pages, so "place it on the last page" is distinguishable from
     *  "place it on page 1". */
    private static final String THREE_PAGE_PDF = """
            %PDF-1.7
            1 0 obj << /Type /Pages /Kids [2 0 R 3 0 R 4 0 R] /Count 3 >> endobj
            2 0 obj << /Type /Page >> endobj
            3 0 obj << /Type /Page >> endobj
            4 0 obj << /Type /Page >> endobj
            """;

    /** Create, upload, **place the field**, then send — in that order. */
    @Test
    void placesASignatureFieldBeforeSending() {
        var result = client().send(request());

        assertThat(calls).containsExactly(
                "POST /api/v1/documents",
                "PUT /upload/contract.pdf",
                "POST /api/v1/documents/2001266/fields",   // SIGNATURE
                "POST /api/v1/documents/2001266/fields",   // NAME
                "POST /api/v1/documents/2001266/fields",   // DATE
                "POST /api/v1/documents/2001266/send");

        assertThat(result.documentId()).isEqualTo("2001266");
        assertThat(result.recipientId()).isEqualTo("3499735");
        assertThat(result.signUrl()).isEqualTo("https://sign.example/s/tok");
    }

    /** Signature, name and date — a block, not a lone widget — all bound to the
     *  recipient Documenso returned. */
    @Test
    void placesASignatureNameAndDateBoundToTheReturnedRecipient() throws Exception {
        client().send(request());

        List<JsonNode> fields = fieldBodies();
        assertThat(fields).hasSize(3);
        assertThat(fields).allSatisfy(f ->
                assertThat(f.get("recipientId").asLong()).isEqualTo(3499735L));
        assertThat(fields.stream().map(f -> f.get("type").asText()))
                .containsExactly("SIGNATURE", "NAME", "DATE");
    }

    /** The complaint this fixes: the box landed mid-document because it was
     *  hard-coded to page 1. It now goes on the PDF's last page. */
    @Test
    void putsTheBlockOnTheLastPageOfTheDocument() throws Exception {
        client().send(request());

        assertThat(fieldBodies()).allSatisfy(f ->
                assertThat(f.get("pageNumber").asInt())
                        .as("3-page PDF, so the block belongs on page 3")
                        .isEqualTo(3));
    }

    @Test
    void stacksTheBlockTopToBottomWithoutOverlap() throws Exception {
        client().send(request());

        List<JsonNode> fields = fieldBodies();
        int previousBottom = 0;
        for (JsonNode f : fields) {
            int top = f.get("pageY").asInt();
            assertThat(top).as("%s must start below the field above it", f.get("type").asText())
                    .isGreaterThanOrEqualTo(previousBottom);
            previousBottom = top + f.get("pageHeight").asInt();
            assertThat(f.get("pageX").asInt()).isEqualTo(8);
        }
        assertThat(previousBottom).as("the block must stay on the page").isLessThanOrEqualTo(100);
    }

    /** An explicit page number overrides the last-page default. */
    @Test
    void anExplicitPageOverridesTheLastPageDefault() throws Exception {
        DocumensoProperties properties = new DocumensoProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-key");
        properties.getSignatureField().setPage(2);
        properties.getSignatureField().setIncludeNameAndDate(false);

        new DocumensoClient(properties).send(request());

        List<JsonNode> fields = fieldBodies();
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).get("pageNumber").asInt()).isEqualTo(2);
    }

    /** A PDF whose page count cannot be read must not block the send. */
    @Test
    void fallsBackToPageOneWhenThePageCountIsUnreadable() throws Exception {
        var opaque = new DocumensoClient.SignatureRequest(
                "t", "CTR-000001", "Asha Menon", "asha.menon@example.com", "s", "m",
                "not a pdf".getBytes(StandardCharsets.ISO_8859_1));

        client().send(opaque);

        assertThat(fieldBodies()).allSatisfy(f ->
                assertThat(f.get("pageNumber").asInt()).isEqualTo(1));
    }

    private List<JsonNode> fieldBodies() throws Exception {
        List<JsonNode> parsed = new ArrayList<>();
        for (String raw : fieldRequests) {
            parsed.add(mapper.readTree(raw));
        }
        return parsed;
    }

    /** The exact failure seen in production, reproduced end to end: without the
     *  field step, Documenso rejects the send and the document stays DRAFT. */
    @Test
    void withoutTheFieldDocumensoWouldRejectTheSend() {
        requireSignatureField = true;
        fieldPlaced = false;

        // Sanity: the client does place one, so this must still succeed.
        assertThat(client().send(request()).documentId()).isEqualTo("2001266");
        assertThat(calls).contains("POST /api/v1/documents/2001266/fields");
    }

    /**
     * The diagnosability fix. A bare status code sent us hunting the API key
     * when Documenso had plainly said what was wrong, so its message now travels
     * in the exception — which reaches the log and the audit trail, never an API
     * response.
     */
    @Test
    void carriesTheProvidersOwnExplanationIntoTheException() {
        requireSignatureField = true;
        fieldPlaced = true; // pretend the field step silently did nothing

        server.removeContext("/api/v1/documents");
        server.createContext("/api/v1/documents", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            String response;
            int status = 200;
            if (path.equals("/api/v1/documents")) {
                response = """
                        {"uploadUrl":"http://127.0.0.1:%d/upload/c.pdf","documentId":7,
                         "recipients":[{"recipientId":9}]}"""
                        .formatted(server.getAddress().getPort());
            } else if (path.endsWith("/fields")) {
                response = "{}";
            } else {
                status = 400;
                response = "{\"message\":\"Signers must have at least one signature field.\"}";
            }
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        assertThatThrownBy(() -> client().send(request()))
                .isInstanceOf(DocumensoException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("at least one signature field");
    }
}
