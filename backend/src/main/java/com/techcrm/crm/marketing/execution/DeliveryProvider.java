package com.techcrm.crm.marketing.execution;

/**
 * Hands one rendered campaign email to the service that sends it. Acceptance
 * is all a provider can report synchronously; delivery, bounce, complaint and
 * unsubscribe come back later through the email webhook
 * (POST /api/marketing/public/webhooks/email) and are never inferred from
 * acceptance.
 */
public interface DeliveryProvider {

    record OutgoingEmail(long messageId, String fromName, String fromAddress, String replyTo, String to,
                         String subject, String html, String text, String unsubscribeUrl) {
    }

    enum Kind {
        /** The provider accepted the message for delivery. */
        ACCEPTED,
        /** Worth retrying later: a 4xx reply, a timeout, a dropped connection. */
        TRANSIENT,
        /** Will never succeed for this recipient: a 5xx reply for the address. */
        PERMANENT,
        /** The provider can't send anything (e.g. authentication rejected): stop and alert. */
        PROVIDER_ERROR
    }

    record Result(Kind kind, String providerMessageId, String reason) {

        public static Result accepted(String providerMessageId) {
            return new Result(Kind.ACCEPTED, providerMessageId, null);
        }

        public static Result of(Kind kind, String reason) {
            return new Result(kind, null, reason);
        }
    }

    /** Shown in the UI. */
    String name();

    /** False when credentials or settings are missing: nothing may be sent. */
    boolean configured();

    Result send(OutgoingEmail email);
}
