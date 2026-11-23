package com.techcrm.crm.contract.email;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends one email with one PDF attachment through Mailjet's Send API v3.1.
 *
 * <pre>
 *   POST /v3.1/send   Basic auth (API key : secret key)
 * </pre>
 *
 * The attachment is base64-encoded here, in the process that already holds the
 * bytes, and goes straight to Mailjet — so it is never subject to anyone else's
 * size limit on the way.
 */
@Component
public class MailjetClient {

    private final MailjetProperties properties;
    private final RestClient api;

    public MailjetClient(MailjetProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getRequestTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getRequestTimeoutMs()));

        String baseUrl = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().replaceAll("/+$", "");
        this.api = RestClient.builder().requestFactory(requestFactory).baseUrl(baseUrl).build();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public record Email(
            String toEmail,
            String toName,
            String subject,
            String textBody,
            String htmlBody,
            /** Echoed in Mailjet's logs, so a message can be found by contract number. */
            String customId,
            String attachmentName,
            byte[] attachment
    ) {
    }

    /** @return Mailjet's MessageUUID — the receipt to look the message up by. */
    @SuppressWarnings("unchecked")
    public String send(Email email) {
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("Email", properties.getFromEmail());
        from.put("Name", properties.getFromName());

        Map<String, Object> to = new LinkedHashMap<>();
        to.put("Email", email.toEmail());
        if (email.toName() != null && !email.toName().isBlank()) {
            to.put("Name", email.toName());
        }

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("ContentType", MediaType.APPLICATION_PDF_VALUE);
        attachment.put("Filename", email.attachmentName());
        // getEncoder(), never getMimeEncoder(): the MIME variant inserts line
        // breaks every 76 characters, which corrupt the attachment.
        attachment.put("Base64Content", Base64.getEncoder().encodeToString(email.attachment()));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("From", from);
        message.put("To", List.of(to));
        message.put("Subject", email.subject());
        message.put("TextPart", email.textBody());
        message.put("HTMLPart", email.htmlBody());
        message.put("CustomID", email.customId());
        message.put("Attachments", List.of(attachment));

        Map<String, Object> response;
        try {
            response = api.post()
                    .uri("/v3.1/send")
                    .headers(headers -> headers.setBasicAuth(properties.getApiKey(), properties.getSecretKey()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("Messages", List.of(message)))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new MailjetException("Mailjet rejected the message with "
                                + res.getStatusCode() + " " + errorBody(res));
                    })
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new MailjetException("Could not reach Mailjet: " + e.getMessage(), e);
        }

        return messageUuid(response);
    }

    /**
     * Mailjet answers 200 per request but reports success per message, so the
     * message's own Status is what decides.
     */
    @SuppressWarnings("unchecked")
    private static String messageUuid(Map<String, Object> response) {
        if (response == null || !(response.get("Messages") instanceof List<?> messages) || messages.isEmpty()
                || !(messages.get(0) instanceof Map<?, ?> first)) {
            throw new MailjetException("Mailjet returned no message result: " + response);
        }
        Map<String, Object> message = (Map<String, Object>) first;
        if (!"success".equalsIgnoreCase(String.valueOf(message.get("Status")))) {
            throw new MailjetException("Mailjet did not accept the message: " + message);
        }
        if (message.get("To") instanceof List<?> recipients && !recipients.isEmpty()
                && recipients.get(0) instanceof Map<?, ?> recipient
                && recipient.get("MessageUUID") != null) {
            return String.valueOf(recipient.get("MessageUUID"));
        }
        throw new MailjetException("Mailjet accepted the message but returned no MessageUUID: " + message);
    }

    /** Mailjet's explanation — "sender not validated", "invalid API key" — which
     *  is the only thing that makes a failure diagnosable. Truncated so a proxy's
     *  HTML error page cannot fill the audit row. */
    private static String errorBody(ClientHttpResponse response) {
        try {
            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
            return body.length() <= 500 ? body : body.substring(0, 500) + "...";
        } catch (Exception e) {
            return "(no response body)";
        }
    }
}
