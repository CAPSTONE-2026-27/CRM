package com.techcrm.crm.contract.email;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Mailjet settings for emailing a contract to its customer.
 *
 * The backend sends this email itself, rather than handing the PDF to the
 * automation platform: SAP Build Process Automation cuts a text value down to
 * 1,024 characters, and a contract PDF is around 110,000 characters of base64.
 * The workflow triggers the send; the document never passes through it.
 */
@Component
@ConfigurationProperties(prefix = "mailjet")
@Getter
@Setter
public class MailjetProperties {

    private String baseUrl = "https://api.mailjet.com";

    /** The public API key. Never commit a real value — set MAILJET_API_KEY, or
     *  put it in application-local.yml, which is gitignored. */
    private String apiKey = "";

    /** The private secret key. Same rule as the API key. */
    private String secretKey = "";

    /** Must be a sender address verified in the Mailjet account, or Mailjet
     *  refuses every message. */
    private String fromEmail = "";

    private String fromName = "";

    private int requestTimeoutMs = 30000;

    private Templates templates = new Templates();

    /** Blank credentials or sender disable the feature: send-email then answers
     *  503 instead of failing somewhere inside Mailjet. */
    public boolean isConfigured() {
        return notBlank(apiKey) && notBlank(secretKey) && notBlank(fromEmail);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Getter
    @Setter
    public static class Templates {

        /**
         * Where the subject, HTML and plain-text templates are read from.
         *
         * A filesystem folder by default, not the classpath, so the email can be
         * reworded at any time: the files are read on every send, and an edit
         * takes effect on the next email without a rebuild or restart. Relative
         * to the directory the backend is started from.
         */
        private String location = "file:./email-templates/";
    }
}
