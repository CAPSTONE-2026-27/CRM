package com.techcrm.crm.mail;

/**
 * Delivers one customer email. The only thing the email services know about how
 * mail leaves the CRM.
 *
 * Which implementation runs is the {@code email.provider} setting — see
 * {@link EmailSenderConfiguration}. Everything above this line (eligibility,
 * templates, the PDF, idempotency, the audit trail) is the same whichever
 * provider delivers the message.
 */
public interface EmailSender {

    /** Short name recorded in the audit log: {@code smtp} or {@code mailjet}. */
    String provider();

    /** Blank credentials or sender make send-email answer 503 rather than fail
     *  inside the provider. */
    boolean isConfigured();

    String fromEmail();

    String fromName();

    /**
     * Hands the message to the provider.
     *
     * A return means the provider <em>accepted</em> the message, not that it
     * reached the inbox — a bounce arrives later, by email, if at all.
     *
     * @return the provider's message id, recorded so the message can be traced
     * @throws EmailDeliveryException the provider could not be reached or refused it
     */
    String send(OutboundEmail email);
}
