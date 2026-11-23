package com.techcrm.crm.contract.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real HTTP call, against a loopback stub of Mailjet.
 *
 * The attachment test uses a PDF-sized payload on purpose: the bug this whole
 * design exists to avoid is a contract arriving truncated and unopenable.
 */
class MailjetClientTest {

    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String receivedAuth;
    private volatile String receivedBody;
    private volatile int responseStatus = 200;
    private volatile String responseBody = """
            {"Messages":[{"Status":"success","CustomID":"CTR-000005",
              "To":[{"Email":"me@company.com","MessageUUID":"uuid-123","MessageID":1}]}]}""";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3.1/send", exchange -> {
            receivedAuth = exchange.getRequestHeaders().getFirst("Authorization");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private MailjetClient client() {
        MailjetProperties properties = new MailjetProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("public-key");
        properties.setSecretKey("secret-key");
        properties.setFromEmail("sales@techcrm.example");
        properties.setFromName("Piyush Pawar");
        properties.setRequestTimeoutMs(10_000);
        return new MailjetClient(properties);
    }

    private static MailjetClient.Email email(byte[] pdf) {
        return new MailjetClient.Email("me@company.com", "Meera", "Subject", "text", "<p>html</p>",
                "CTR-000005", "CTR-000005.pdf", pdf);
    }

    @Test
    void authenticatesWithTheApiKeyAndSecret() {
        client().send(email(new byte[]{1}));

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("public-key:secret-key".getBytes(StandardCharsets.UTF_8));
        assertThat(receivedAuth).isEqualTo(expected);
    }

    /** A real contract's size, decoded back byte for byte. */
    @Test
    void attachesTheWholePdfUntruncated() throws IOException {
        byte[] pdf = new byte[82_637];
        new Random(7).nextBytes(pdf);

        client().send(email(pdf));

        JsonNode attachment = mapper.readTree(receivedBody).path("Messages").get(0).path("Attachments").get(0);
        String encoded = attachment.path("Base64Content").asText();
        assertThat(encoded).hasSize(110_184).doesNotContain("\n").doesNotContain("\r");
        assertThat(Base64.getDecoder().decode(encoded)).isEqualTo(pdf);
        assertThat(attachment.path("ContentType").asText()).isEqualTo("application/pdf");
        assertThat(attachment.path("Filename").asText()).isEqualTo("CTR-000005.pdf");
    }

    @Test
    void sendsTheMessageMailjetExpects() throws IOException {
        String uuid = client().send(email(new byte[]{1}));

        JsonNode message = mapper.readTree(receivedBody).path("Messages").get(0);
        assertThat(message.path("From").path("Email").asText()).isEqualTo("sales@techcrm.example");
        assertThat(message.path("From").path("Name").asText()).isEqualTo("Piyush Pawar");
        assertThat(message.path("To").get(0).path("Email").asText()).isEqualTo("me@company.com");
        assertThat(message.path("Subject").asText()).isEqualTo("Subject");
        assertThat(message.path("HTMLPart").asText()).isEqualTo("<p>html</p>");
        assertThat(message.path("TextPart").asText()).isEqualTo("text");
        assertThat(message.path("CustomID").asText()).isEqualTo("CTR-000005");
        assertThat(uuid).isEqualTo("uuid-123");
    }

    /** Mailjet's own reason must survive into the exception, for the audit log. */
    @Test
    void aRejectionCarriesMailjetsReason() {
        responseStatus = 400;
        responseBody = """
                {"Messages":[{"Status":"error","Errors":[{"ErrorCode":"send-0015",
                  "ErrorMessage":"Sender not validated"}]}]}""";

        assertThatThrownBy(() -> client().send(email(new byte[]{1})))
                .isInstanceOf(MailjetException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("Sender not validated");
    }

    @Test
    void aNonSuccessStatusIsAFailureEvenOn200() {
        responseBody = "{\"Messages\":[{\"Status\":\"error\"}]}";

        assertThatThrownBy(() -> client().send(email(new byte[]{1})))
                .isInstanceOf(MailjetException.class);
    }

    @Test
    void anUnreachableMailjetIsAMailjetException() {
        server.stop(0);

        assertThatThrownBy(() -> client().send(email(new byte[]{1})))
                .isInstanceOf(MailjetException.class)
                .hasMessageContaining("Could not reach Mailjet");
    }
}
