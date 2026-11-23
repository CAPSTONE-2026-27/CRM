package com.techcrm.crm.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Sends through an SMTP server — the college's Google Workspace mailbox, via
 * smtp.gmail.com.
 *
 * <pre>
 *   multipart/mixed
 *   ├── multipart/alternative   plain-text body + HTML body
 *   └── application/pdf         the document, base64-encoded by Jakarta Mail
 * </pre>
 *
 * The receipt is the message's own Message-ID header, generated before sending
 * and kept by Spring when it sends, so the audit entry names the exact message
 * that can be found in the mailbox's Sent folder.
 */
public class SmtpEmailSender implements EmailSender {

    private final EmailProperties.Smtp properties;
    private final JavaMailSender mailSender;

    public SmtpEmailSender(EmailProperties.Smtp properties) {
        this(properties, mailSender(properties));
    }

    SmtpEmailSender(EmailProperties.Smtp properties, JavaMailSender mailSender) {
        this.properties = properties;
        this.mailSender = mailSender;
    }

    @Override
    public String provider() {
        return "smtp";
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public String fromEmail() {
        return properties.resolvedFromEmail();
    }

    @Override
    public String fromName() {
        return properties.getFromName();
    }

    @Override
    public String send(OutboundEmail email) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(address(fromEmail(), fromName()));
            helper.setTo(address(email.toEmail(), email.toName()));
            helper.setSubject(email.subject());
            helper.setText(email.textBody(), email.htmlBody());
            helper.addAttachment(email.attachmentName(),
                    new ByteArrayResource(email.attachment()), MediaType.APPLICATION_PDF_VALUE);
            if (email.reference() != null) {
                message.setHeader("X-CRM-Reference", email.reference());
            }

            message.saveChanges();
            String messageId = message.getMessageID();
            mailSender.send(message);
            return messageId;

        } catch (MailAuthenticationException e) {
            throw new EmailDeliveryException("The SMTP server refused the login for " + properties.getUsername()
                    + " - check the app password: " + e.getMessage(), e);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            throw new EmailDeliveryException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    private static InternetAddress address(String email, String name)
            throws MessagingException, UnsupportedEncodingException {
        return name == null || name.isBlank()
                ? new InternetAddress(email)
                : new InternetAddress(email, name, StandardCharsets.UTF_8.name());
    }

    static JavaMailSenderImpl mailSender(EmailProperties.Smtp properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        sender.setPort(properties.getPort());
        sender.setUsername(properties.getUsername() == null ? null : properties.getUsername().trim());
        sender.setPassword(properties.getPassword() == null ? null : properties.getPassword().trim());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties mail = sender.getJavaMailProperties();
        mail.put("mail.transport.protocol", "smtp");
        mail.put("mail.smtp.auth", "true");
        if (properties.isSsl()) {
            mail.put("mail.smtp.ssl.enable", "true");
        } else {
            // Required, not just enabled: never fall back to sending the
            // password over an unencrypted connection.
            mail.put("mail.smtp.starttls.enable", "true");
            mail.put("mail.smtp.starttls.required", "true");
        }
        mail.put("mail.smtp.ssl.checkserveridentity", "true");

        String timeoutMs = String.valueOf(properties.getTimeout().toMillis());
        mail.put("mail.smtp.connectiontimeout", timeoutMs);
        mail.put("mail.smtp.timeout", timeoutMs);
        mail.put("mail.smtp.writetimeout", timeoutMs);
        return sender;
    }
}
