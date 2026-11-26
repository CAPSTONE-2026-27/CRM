package com.techcrm.crm.marketing.email;

import com.techcrm.crm.marketing.execution.DeliveryProvider;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;

/**
 * Sends campaign email over SMTP, which every transactional email service
 * (SES, SendGrid, Mailgun, Postmark, Brevo, Microsoft 365, Gmail/Workspace)
 * accepts. Uses jakarta.mail, already on the classpath for the IMAP lead
 * poller, so no new dependency.
 *
 * Each message is multipart/alternative (plain text + HTML) with a
 * Message-ID we choose, so provider webhooks can be matched back to it, and
 * RFC 8058 one-click List-Unsubscribe headers. Neither the recipient nor the
 * body is logged.
 */
@Component
public class SmtpDeliveryProvider implements DeliveryProvider {

    private static final Logger log = LoggerFactory.getLogger(SmtpDeliveryProvider.class);

    private final EmailSettings settings;

    public SmtpDeliveryProvider(EmailSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return settings.status().provider();
    }

    @Override
    public boolean configured() {
        return settings.configured();
    }

    @Override
    public Result send(OutgoingEmail email) {
        if (!configured()) return Result.of(Kind.PROVIDER_ERROR, "Email sending is not configured");
        String messageIdHeader = "<mkt-" + email.messageId() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@" + domainOf(email.fromAddress()) + ">";
        try {
            Session session = Session.getInstance(properties());
            MimeMessage msg = new MimeMessage(session) {
                @Override
                protected void updateMessageID() throws MessagingException {
                    setHeader("Message-ID", messageIdHeader);
                }
            };
            msg.setFrom(new InternetAddress(email.fromAddress(), email.fromName(), StandardCharsets.UTF_8.name()));
            if (email.replyTo() != null) msg.setReplyTo(new InternetAddress[]{new InternetAddress(email.replyTo(), true)});
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(email.to(), true));
            msg.setSubject(email.subject(), StandardCharsets.UTF_8.name());
            msg.setSentDate(new Date());
            msg.setHeader("List-Unsubscribe", "<" + email.unsubscribeUrl() + ">");
            msg.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            msg.setHeader("X-TechCRM-Message-Id", String.valueOf(email.messageId()));

            MimeBodyPart text = new MimeBodyPart();
            text.setText(email.text(), StandardCharsets.UTF_8.name(), "plain");
            MimeBodyPart html = new MimeBodyPart();
            html.setText(email.html(), StandardCharsets.UTF_8.name(), "html");
            MimeMultipart alternative = new MimeMultipart("alternative");
            alternative.addBodyPart(text);
            alternative.addBodyPart(html);
            msg.setContent(alternative);
            msg.saveChanges();

            if (settings.smtpUsername() != null) {
                Transport.send(msg, settings.smtpUsername(), settings.smtpPassword());
            } else {
                Transport.send(msg);
            }
            return Result.accepted(messageIdHeader);
        } catch (UnsupportedEncodingException e) {
            return Result.of(Kind.PERMANENT, "Invalid sender name encoding");
        } catch (MessagingException e) {
            Result r = classify(e);
            log.warn("SMTP send for campaign message {} failed: {} ({})", email.messageId(), r.kind(), r.reason());
            return r;
        }
    }

    /** Maps a jakarta.mail failure to retry semantics. Package-private for tests. */
    static Result classify(MessagingException e) {
        if (e instanceof AuthenticationFailedException) {
            return Result.of(Kind.PROVIDER_ERROR, "The SMTP server rejected the credentials");
        }
        for (Throwable t = e; t != null; t = next(t)) {
            if (t instanceof SMTPAddressFailedException a) return byCode(a.getReturnCode(), "Recipient rejected: " + a.getMessage());
            if (t instanceof SMTPSendFailedException f) return byCode(f.getReturnCode(), "Send rejected: " + f.getMessage());
        }
        if (e instanceof SendFailedException sf && sf.getInvalidAddresses() != null && sf.getInvalidAddresses().length > 0) {
            return Result.of(Kind.PERMANENT, "Invalid recipient address");
        }
        for (Throwable t = e; t != null; t = next(t)) {
            if (t instanceof IOException) return Result.of(Kind.TRANSIENT, "Could not reach the SMTP server: " + t.getClass().getSimpleName());
        }
        return Result.of(Kind.TRANSIENT, "SMTP error: " + e.getMessage());
    }

    private static Result byCode(int code, String reason) {
        String clipped = reason.length() > 280 ? reason.substring(0, 280) : reason;
        if (code == 530 || code == 535 || code == 534) return Result.of(Kind.PROVIDER_ERROR, "SMTP authentication required or rejected");
        return Result.of(code >= 500 ? Kind.PERMANENT : Kind.TRANSIENT, clipped.trim());
    }

    private static Throwable next(Throwable t) {
        Throwable n = t instanceof MessagingException m && m.getNextException() != null ? m.getNextException() : t.getCause();
        return n == t ? null : n;
    }

    private Properties properties() {
        Properties p = new Properties();
        String timeout = String.valueOf(settings.smtpTimeoutMs());
        p.put("mail.transport.protocol", settings.smtpSecurity() == EmailSettings.SmtpSecurity.SSL ? "smtps" : "smtp");
        String prefix = settings.smtpSecurity() == EmailSettings.SmtpSecurity.SSL ? "mail.smtps." : "mail.smtp.";
        p.put(prefix + "host", settings.smtpHost());
        p.put(prefix + "port", String.valueOf(settings.smtpPort()));
        p.put(prefix + "auth", String.valueOf(settings.smtpUsername() != null));
        p.put(prefix + "connectiontimeout", timeout);
        p.put(prefix + "timeout", timeout);
        p.put(prefix + "writetimeout", timeout);
        if (settings.smtpSecurity() == EmailSettings.SmtpSecurity.STARTTLS) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        if (settings.smtpSecurity() == EmailSettings.SmtpSecurity.SSL) {
            p.put("mail.smtps.ssl.checkserveridentity", "true");
        } else {
            p.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        return p;
    }

    private static String domainOf(String address) {
        int at = address == null ? -1 : address.indexOf('@');
        return at < 0 ? "techcrm.local" : address.substring(at + 1);
    }
}
