package com.techcrm.crm.mail;

import com.techcrm.crm.contract.email.MailjetClient;
import com.techcrm.crm.contract.email.MailjetProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The email.provider switch: one setting decides, and a typo is caught. */
class EmailSenderConfigurationTest {

    MailjetProperties mailjetProperties = new MailjetProperties();
    MailjetClient mailjetClient = new MailjetClient(mailjetProperties);

    private EmailSender senderFor(String provider) {
        EmailProperties properties = new EmailProperties();
        properties.setProvider(provider);
        return new EmailSenderConfiguration().emailSender(properties, mailjetClient, mailjetProperties);
    }

    @Test
    void mailjetIsTheDefault() {
        assertThat(new EmailProperties().getProvider()).isEqualTo("mailjet");
        assertThat(senderFor("mailjet")).isInstanceOf(MailjetEmailSender.class);
    }

    @Test
    void smtpSelectsTheSmtpSender() {
        assertThat(senderFor(" SMTP ")).isInstanceOf(SmtpEmailSender.class);
    }

    @Test
    void aMisspeltProviderStopsStartUp() {
        assertThatThrownBy(() -> senderFor("smpt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'smtp' or 'mailjet'");
    }
}
