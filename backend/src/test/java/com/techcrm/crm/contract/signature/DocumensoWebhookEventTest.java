package com.techcrm.crm.contract.signature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payload parsing, deliberately lenient.
 *
 * A rejected webhook is a signature that never reaches the CRM, so the parser
 * has to survive Documenso adding fields, renaming keys between releases, and
 * flattening the payload — while still refusing something that is not a webhook
 * at all.
 */
class DocumensoWebhookEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesTheDocumentedShape() {
        var event = DocumensoWebhookEvent.parse(mapper, """
                {"event":"DOCUMENT_COMPLETED","createdAt":"2026-09-05T10:00:00.000Z",
                 "payload":{"id":881,"externalId":"CTR-000042","status":"COMPLETED"}}
                """);

        assertThat(event.eventType()).isEqualTo("DOCUMENT_COMPLETED");
        assertThat(event.documentId()).isEqualTo("881");
        assertThat(event.externalId()).isEqualTo("CTR-000042");
        assertThat(event.documentStatus()).isEqualTo("COMPLETED");
    }

    /** Ids arrive as JSON numbers but are stored and compared as text. */
    @Test
    void normalisesANumericDocumentIdToText() {
        var event = DocumensoWebhookEvent.parse(mapper,
                "{\"event\":\"DOCUMENT_SENT\",\"payload\":{\"id\":12345}}");

        assertThat(event.documentId()).isEqualTo("12345");
    }

    @Test
    void acceptsAFlattenedPayload() {
        var event = DocumensoWebhookEvent.parse(mapper,
                "{\"event\":\"DOCUMENT_SIGNED\",\"id\":881,\"externalId\":\"CTR-000042\"}");

        assertThat(event.documentId()).isEqualTo("881");
        assertThat(event.externalId()).isEqualTo("CTR-000042");
    }

    @Test
    void acceptsTheAlternativeEventKeySpellings() {
        assertThat(DocumensoWebhookEvent.parse(mapper,
                "{\"eventType\":\"DOCUMENT_OPENED\",\"payload\":{\"id\":1}}").eventType())
                .isEqualTo("DOCUMENT_OPENED");
        assertThat(DocumensoWebhookEvent.parse(mapper,
                "{\"type\":\"DOCUMENT_OPENED\",\"payload\":{\"id\":1}}").eventType())
                .isEqualTo("DOCUMENT_OPENED");
    }

    @Test
    void upperCasesTheEventName() {
        assertThat(DocumensoWebhookEvent.parse(mapper,
                "{\"event\":\" document_completed \",\"payload\":{\"id\":1}}").eventType())
                .isEqualTo("DOCUMENT_COMPLETED");
    }

    /** Documenso records the decline against the recipient who declined, not
     *  against the document. */
    @Test
    void findsTheRejectionReasonOnTheRecipient() {
        var event = DocumensoWebhookEvent.parse(mapper, """
                {"event":"DOCUMENT_REJECTED",
                 "payload":{"id":881,
                            "recipients":[{"id":45,"rejectionReason":null},
                                          {"id":46,"rejectionReason":"Pricing not approved"}]}}
                """);

        assertThat(event.rejectionReason()).isEqualTo("Pricing not approved");
    }

    @Test
    void toleratesFieldsItDoesNotKnowAbout() {
        var event = DocumensoWebhookEvent.parse(mapper, """
                {"event":"DOCUMENT_COMPLETED","webhookEndpoint":"https://crm.example/api/contracts/sign-callback",
                 "payload":{"id":881,"documentData":{"type":"S3_PATH","data":"..."},
                            "somethingAddedInAFutureRelease":{"nested":true}}}
                """);

        assertThat(event.eventType()).isEqualTo("DOCUMENT_COMPLETED");
        assertThat(event.documentId()).isEqualTo("881");
    }

    @Test
    void leavesAbsentFieldsNull() {
        var event = DocumensoWebhookEvent.parse(mapper, "{\"event\":\"DOCUMENT_CREATED\"}");

        assertThat(event.documentId()).isNull();
        assertThat(event.externalId()).isNull();
        assertThat(event.rejectionReason()).isNull();
    }

    @Test
    void refusesSomethingThatIsNotAWebhook() {
        assertThatThrownBy(() -> DocumensoWebhookEvent.parse(mapper, "not json"))
                .isInstanceOf(DocumensoException.class);
        assertThatThrownBy(() -> DocumensoWebhookEvent.parse(mapper, "[1,2,3]"))
                .isInstanceOf(DocumensoException.class)
                .hasMessageContaining("not a JSON object");
        assertThatThrownBy(() -> DocumensoWebhookEvent.parse(mapper, "{\"payload\":{\"id\":1}}"))
                .isInstanceOf(DocumensoException.class)
                .hasMessageContaining("no event name");
    }
}
