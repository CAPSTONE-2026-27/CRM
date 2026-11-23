package com.techcrm.crm.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Which provider sends customer email, and the SMTP server's settings.
 *
 * {@code email.provider} is the switch: {@code mailjet} (the default, so nothing
 * changes until it is set deliberately) or {@code smtp}.
 */
@Component
@ConfigurationProperties(prefix = "email")
@Getter
@Setter
public class EmailProperties {

    private String provider = "mailjet";

    private Smtp smtp = new Smtp();

    @Getter
    @Setter
    public static class Smtp {

        private String host = "";

        /** 587 with STARTTLS, or 465 with {@code ssl: true}. */
        private int port = 587;

        /** The mailbox to log in as — for Gmail, the full email address. */
        private String username = "";

        /** For Gmail, the 16-character app password, never the account password.
         *  Never commit a real value — set SMTP_PASSWORD, or put it in
         *  application-local.yml, which is gitignored. */
        private String password = "";

        /** Blank means the username. Gmail sends from the mailbox it logged in
         *  as, so another address here only works if it is a verified alias. */
        private String fromEmail = "";

        private String fromName = "";

        /** Implicit TLS (port 465) instead of STARTTLS (port 587). */
        private boolean ssl = false;

        /**
         * Applied to connect, read and write separately.
         *
         * Short on purpose: SAP Build Process Automation abandons a call at 30
         * seconds, and Jakarta Mail's own default is to wait forever.
         */
        private Duration timeout = Duration.ofSeconds(10);

        public boolean isConfigured() {
            return notBlank(host) && notBlank(username) && notBlank(password);
        }

        /** The address mail is sent from. */
        public String resolvedFromEmail() {
            return notBlank(fromEmail) ? fromEmail.trim() : username == null ? "" : username.trim();
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
