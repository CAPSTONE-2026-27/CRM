package com.techcrm.crm.mail;

/**
 * The email provider could not be reached, refused the login, or refused the
 * message.
 *
 * Carries the provider's own wording for the log and the audit trail; callers
 * turn it into a fixed 502 so provider detail never reaches a CRM client. Never
 * put a credential in the message.
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
