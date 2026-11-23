package com.techcrm.crm.contract.email;

/**
 * Mailjet could not be reached, or refused the message.
 *
 * Carries Mailjet's own wording for the log and the audit trail; callers turn it
 * into a fixed 502 so provider detail never reaches a CRM client.
 */
public class MailjetException extends RuntimeException {

    public MailjetException(String message) {
        super(message);
    }

    public MailjetException(String message, Throwable cause) {
        super(message, cause);
    }
}
