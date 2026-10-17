package com.techcrm.crm.mail;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the customer receives over SMTP: the right sender and recipient, both
 * bodies, and the PDF byte-for-byte — and a refused login reported without the
 * password in it.
 */
class SmtpEmailSenderTest {

    private static final byte[] PDF = "%PDF-1.7 contract".getBytes();

    EmailProperties.Smtp properties = new EmailProperties.Smtp();
    JavaMailSender mailSender = mock(JavaMailSender.class);
    SmtpEmailSender sender;

    @BeforeEach
    void setUp() {
        properties.setHost("smtp.gmail.com");
        properties.setUsername("crm@college.example");
        properties.setPassword("abcdefghijklmnop");
        properties.setFromName("TechCRM");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        sender = new SmtpEmailSender(properties, mailSender);
    }

    private static OutboundEmail email() {
        return new OutboundEmail("asha.menon@northwind.example", "Asha Menon",
                "Your contract CTR-000042", "plain text", "<p>html</p>",
                "CTR-000042", "CTR-000042.pdf", PDF);
    }

    private MimeMessage sent() {
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(message.capture());
        return message.getValue();
    }

    @Test
    void sendsFromTheMailboxToTheCustomer() throws Exception {
        sender.send(email());

        MimeMessage message = sent();
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        InternetAddress to = (InternetAddress) message.getRecipients(Message.RecipientType.TO)[0];
        assertThat(from.getAddress()).isEqualTo("crm@college.example");
        assertThat(from.getPersonal()).isEqualTo("TechCRM");
        assertThat(to.getAddress()).isEqualTo("asha.menon@northwind.example");
        assertThat(to.getPersonal()).isEqualTo("Asha Menon");
        assertThat(message.getSubject()).isEqualTo("Your contract CTR-000042");
        assertThat(message.getHeader("X-CRM-Reference")[0]).isEqualTo("CTR-000042");
    }

    @Test
    void attachesThePdfUnchanged() throws Exception {
        sender.send(email());

        BodyPart attachment = null;
        Multipart parts = (Multipart) sent().getContent();
        for (int i = 0; i < parts.getCount(); i++) {
            if (Part.ATTACHMENT.equalsIgnoreCase(parts.getBodyPart(i).getDisposition())) {
                attachment = parts.getBodyPart(i);
            }
        }
        assertThat(attachment).isNotNull();
        assertThat(attachment.getFileName()).isEqualTo("CTR-000042.pdf");
        assertThat(attachment.getContentType()).startsWith("application/pdf");
        assertThat(attachment.getInputStream().readAllBytes()).isEqualTo(PDF);
    }

    /** The receipt recorded in the audit log is the message's own Message-ID. */
    @Test
    void returnsTheMessageId() throws Exception {
        String messageId = sender.send(email());

        assertThat(messageId).isNotBlank().isEqualTo(sent().getMessageID());
    }

    @Test
    void aRefusedLoginIsADeliveryFailureThatNeverContainsThePassword() {
        doThrow(new MailAuthenticationException("535-5.7.8 Username and Password not accepted"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.send(email()))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("refused the login")
                .hasMessageNotContaining("abcdefghijklmnop");
    }

    @Test
    void anUnreachableServerIsADeliveryFailure() {
        doThrow(new MailSendException("Couldn't connect to host, port: smtp.gmail.com, 587"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.send(email()))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("Couldn't connect");
    }

    @Test
    void isConfiguredOnlyWithHostUsernameAndPassword() {
        assertThat(sender.isConfigured()).isTrue();

        properties.setPassword("");
        assertThat(sender.isConfigured()).isFalse();
    }

    /** Gmail sends from the mailbox it logged in as. */
    @Test
    void theSenderAddressDefaultsToTheUsername() {
        assertThat(sender.fromEmail()).isEqualTo("crm@college.example");

        properties.setFromEmail("sales@college.example");
        assertThat(sender.fromEmail()).isEqualTo("sales@college.example");
    }

    /** The password must never travel unencrypted, and no call may outlive
     *  SAP's 30-second limit. */
    @Test
    void theConnectionRequiresStartTlsAndTimesOut() {
        properties.setTimeout(Duration.ofSeconds(10));

        JavaMailSenderImpl real = SmtpEmailSender.mailSender(properties);
        Properties mail = real.getJavaMailProperties();

        assertThat(real.getHost()).isEqualTo("smtp.gmail.com");
        assertThat(real.getPort()).isEqualTo(587);
        assertThat(mail.getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(mail.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(mail.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
        assertThat(mail.getProperty("mail.smtp.timeout")).isEqualTo("10000");
        assertThat(mail.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
    }

    @Test
    void port465UsesImplicitTls() {
        properties.setPort(465);
        properties.setSsl(true);

        Properties mail = SmtpEmailSender.mailSender(properties).getJavaMailProperties();

        assertThat(mail.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(mail.getProperty("mail.smtp.starttls.enable")).isNull();
    }
}
