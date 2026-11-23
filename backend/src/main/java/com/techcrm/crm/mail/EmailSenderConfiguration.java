package com.techcrm.crm.mail;

import com.techcrm.crm.contract.email.MailjetClient;
import com.techcrm.crm.contract.email.MailjetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Picks the one {@link EmailSender} the CRM uses, from {@code email.provider}.
 *
 * Switching provider is a configuration change and a restart, never a code
 * change. A misspelt value stops start-up with a message naming the valid ones,
 * rather than quietly sending through the wrong provider.
 */
@Configuration
public class EmailSenderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderConfiguration.class);

    @Bean
    EmailSender emailSender(EmailProperties properties,
                                   MailjetClient mailjetClient,
                                   MailjetProperties mailjetProperties) {
        String provider = properties.getProvider() == null
                ? "" : properties.getProvider().trim().toLowerCase(Locale.ROOT);

        EmailSender sender = switch (provider) {
            case "smtp" -> new SmtpEmailSender(properties.getSmtp());
            case "mailjet" -> new MailjetEmailSender(mailjetClient, mailjetProperties);
            default -> throw new IllegalStateException(
                    "email.provider must be 'smtp' or 'mailjet', not '" + properties.getProvider() + "'");
        };

        log.info("Customer email is sent through {} as {}", sender.provider(), sender.fromEmail());
        return sender;
    }
}
