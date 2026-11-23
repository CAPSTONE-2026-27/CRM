package com.techcrm.crm.mail;

/**
 * One email with one PDF attachment, in no provider's format.
 *
 * @param reference      the document number, e.g. CTR-000042 — carried with the
 *                       message so it can be found in the provider's logs
 * @param attachment     the PDF's bytes, exactly as read from the document store
 */
public record OutboundEmail(
        String toEmail,
        String toName,
        String subject,
        String textBody,
        String htmlBody,
        String reference,
        String attachmentName,
        byte[] attachment
) {
}
