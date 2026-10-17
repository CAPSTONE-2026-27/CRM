package com.techcrm.crm.mail;

import com.techcrm.crm.contract.email.MailjetClient;
import com.techcrm.crm.contract.email.MailjetException;
import com.techcrm.crm.contract.email.MailjetProperties;

/**
 * Sends through Mailjet's REST API, exactly as before the SMTP option existed.
 * Kept as the fallback while the college SMTP server is being proven.
 */
public class MailjetEmailSender implements EmailSender {

    private final MailjetClient client;
    private final MailjetProperties properties;

    public MailjetEmailSender(MailjetClient client, MailjetProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return "mailjet";
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }

    @Override
    public String fromEmail() {
        return properties.getFromEmail();
    }

    @Override
    public String fromName() {
        return properties.getFromName();
    }

    @Override
    public String send(OutboundEmail email) {
        try {
            return client.send(new MailjetClient.Email(
                    email.toEmail(), email.toName(),
                    email.subject(), email.textBody(), email.htmlBody(),
                    email.reference(), email.attachmentName(), email.attachment()));
        } catch (MailjetException e) {
            throw new EmailDeliveryException(e.getMessage(), e);
        }
    }
}
