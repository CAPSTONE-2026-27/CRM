package com.techcrm.crm.contract.signature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A Documenso webhook delivery, reduced to what the CRM acts on.
 *
 * Documenso posts
 * <pre>
 *   { "event": "DOCUMENT_COMPLETED",
 *     "payload": { "id": 123, "externalId": "CTR-000042", "status": "COMPLETED",
 *                  "recipients": [ { "id": 45, "signingStatus": "SIGNED",
 *                                    "rejectionReason": null } ] },
 *     "createdAt": "..." }
 * </pre>
 *
 * Parsed leniently, and by hand rather than by binding to a record. Documenso
 * has moved these keys between releases and adds fields between them; a strict
 * binding would reject a delivery over a field we do not care about, and a
 * rejected webhook is a contract whose signature never reaches the CRM. Anything
 * unrecognised is preserved in the audit trail and otherwise ignored.
 */
public record DocumensoWebhookEvent(
        String eventType,
        String documentId,
        String externalId,
        String documentStatus,
        String rejectionReason
) {

    /** Documenso's event names, as of API v1. */
    public static final String DOCUMENT_CREATED = "DOCUMENT_CREATED";
    public static final String DOCUMENT_SENT = "DOCUMENT_SENT";
    public static final String DOCUMENT_OPENED = "DOCUMENT_OPENED";
    public static final String DOCUMENT_SIGNED = "DOCUMENT_SIGNED";
    public static final String DOCUMENT_COMPLETED = "DOCUMENT_COMPLETED";
    public static final String DOCUMENT_REJECTED = "DOCUMENT_REJECTED";
    public static final String DOCUMENT_CANCELLED = "DOCUMENT_CANCELLED";

    public static DocumensoWebhookEvent parse(ObjectMapper mapper, String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new DocumensoException("Webhook body is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new DocumensoException("Webhook body is not a JSON object");
        }

        String eventType = text(root, "event", "eventType", "type");
        if (eventType == null) {
            throw new DocumensoException("Webhook body has no event name");
        }

        // Older deliveries put the document fields at the top level rather than
        // under "payload".
        JsonNode payload = root.has("payload") && root.get("payload").isObject()
                ? root.get("payload")
                : root;

        return new DocumensoWebhookEvent(
                eventType.trim().toUpperCase(),
                text(payload, "id", "documentId"),
                text(payload, "externalId", "external_id"),
                text(payload, "status"),
                rejectionReason(payload));
    }

    /** Documenso reports the decline reason against the recipient who declined,
     *  not against the document. */
    private static String rejectionReason(JsonNode payload) {
        JsonNode recipients = payload.get("recipients");
        if (recipients == null || !recipients.isArray()) {
            return text(payload, "rejectionReason");
        }
        for (JsonNode recipient : recipients) {
            String reason = text(recipient, "rejectionReason", "reason");
            if (reason != null) {
                return reason;
            }
        }
        return text(payload, "rejectionReason");
    }

    private static String text(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                String text = value.isTextual() ? value.textValue() : value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}
