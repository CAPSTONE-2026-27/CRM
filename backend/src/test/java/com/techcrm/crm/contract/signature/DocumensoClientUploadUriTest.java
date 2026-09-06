package com.techcrm.crm.contract.signature;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract with S3 presigned upload URLs: they must reach the wire byte for
 * byte.
 *
 * This is a regression test for a real failure. Documenso hands back an AWS
 * presigned PUT URL whose {@code X-Amz-Credential} query parameter contains
 * %2F-escaped slashes. {@code RestClient.uri(String)} treats its argument as a
 * URI *template* and re-encodes it, turning every {@code %2F} into
 * {@code %252F}. S3 then rejects the upload with
 *
 * <pre>400 AuthorizationQueryParametersError: the Credential is mal-formed</pre>
 *
 * which reads like an authentication problem and sends you looking at the API
 * key. The cure is passing a pre-parsed {@link java.net.URI}.
 *
 * Asserted against a loopback HTTP server rather than a mock, because the whole
 * point is what the client actually puts on the wire — a mocked RestClient would
 * have happily passed while production stayed broken.
 */
class DocumensoClientUploadUriTest {

    /** A realistic presigned query string: the credential's slashes are escaped,
     *  exactly as AWS produces them. */
    private static final String PRESIGNED_QUERY =
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20260906%2Feu-central-1%2Fs3%2Faws4_request"
                    + "&X-Amz-Date=20260906T120000Z"
                    + "&X-Amz-Expires=3600"
                    + "&X-Amz-SignedHeaders=host"
                    + "&X-Amz-Content-Sha256=UNSIGNED-PAYLOAD"
                    + "&X-Amz-Signature=abc123"
                    + "&x-id=PutObject";

    private HttpServer server;
    private final AtomicReference<String> receivedQuery = new AtomicReference<>();
    private final AtomicReference<Integer> receivedBodyLength = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            receivedBodyLength.set(exchange.getRequestBody().readAllBytes().length);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private DocumensoClient client() {
        DocumensoProperties properties = new DocumensoProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-key");
        properties.setRequestTimeoutMs(10_000);
        return new DocumensoClient(properties);
    }

    @Test
    void deliversAPresignedUploadUrlWithoutReEncodingIt() throws Exception {
        String uploadUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/bucket/contract.pdf" + PRESIGNED_QUERY;
        byte[] pdf = "%PDF-1.4 pretend contract".getBytes(StandardCharsets.UTF_8);

        // uploadPdf is private; exercised through the package-private seam the
        // production path uses, so the test cannot drift from it.
        invokeUpload(client(), uploadUrl, pdf);

        assertThat(receivedQuery.get())
                .as("the presigned query must arrive exactly as issued")
                .isEqualTo(PRESIGNED_QUERY.substring(1));

        // The specific corruption that caused the 400.
        assertThat(receivedQuery.get()).contains("%2F20260906%2Feu-central-1%2Fs3%2Faws4_request");
        assertThat(receivedQuery.get()).doesNotContain("%252F");

        assertThat(receivedBodyLength.get()).isEqualTo(pdf.length);
    }

    private void invokeUpload(DocumensoClient client, String url, byte[] pdf) throws Exception {
        var method = DocumensoClient.class.getDeclaredMethod("uploadPdf", String.class, byte[].class);
        method.setAccessible(true);
        method.invoke(client, url, pdf);
    }
}
