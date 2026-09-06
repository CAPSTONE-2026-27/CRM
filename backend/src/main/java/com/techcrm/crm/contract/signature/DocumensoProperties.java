package com.techcrm.crm.contract.signature;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Documenso connection settings.
 *
 * This project had no Documenso integration before, so there was no existing
 * API version to match. The client below is written against Documenso's public
 * <b>API v1</b> — {@code POST /api/v1/documents}, {@code PUT <uploadUrl>},
 * {@code POST /api/v1/documents/{id}/send} — which is what both Documenso Cloud
 * and a self-hosted instance expose.
 *
 * The two things installations actually differ on are exposed as settings rather
 * than guessed at: how the API key is presented, and what the webhook secret
 * header is called.
 */
@Component
@ConfigurationProperties(prefix = "documenso")
@Getter
@Setter
public class DocumensoProperties {

    /** Instance root, without a trailing {@code /api/v1}. Blank disables the
     *  integration: send-for-signature then fails with a clear 503 instead of
     *  posting a contract at a URL nobody configured. */
    private String baseUrl = "";

    /** Never commit a real value. Set DOCUMENSO_API_KEY, or put it in
     *  application-local.yml, which is gitignored. */
    private String apiKey = "";

    /** Documenso v1 takes the raw key in the Authorization header. Some
     *  deployments sit behind a proxy that expects an auth scheme; set this to
     *  {@code Bearer} there. Just the scheme name — the separating space is
     *  added by the client, because Spring's binder would trim it from here. */
    private String apiKeyPrefix = "";

    private String apiKeyHeader = "Authorization";

    private int requestTimeoutMs = 30000;

    /** Whether Documenso should email the signer itself. False when the
     *  automation platform sends its own email using the returned signUrl. */
    private boolean sendEmail = true;

    private Webhook webhook = new Webhook();

    private SignatureField signatureField = new SignatureField();

    /**
     * The signer's signature block.
     *
     * Documenso will not send a document whose signer has no signature field, so
     * one is always placed. All positions are percentages of the page, which is
     * the unit Documenso's field API uses.
     *
     * The defaults put a signature box with the signer's name and the date
     * underneath it, low on the LAST page — where the templates' own signature
     * block sits. Everything here is tunable for a template with different
     * wording, and the fields can also be dragged in Documenso's editor.
     */
    @Getter
    @Setter
    public static class SignatureField {

        /**
         * 1-based page number, or 0 for "the last page" (the default).
         *
         * Last is resolved by counting pages in the generated PDF. If that count
         * cannot be established the field falls back to page 1, because a
         * signature box in an awkward place is better than a document that
         * cannot be sent.
         */
        private int page = 0;

        /** Left edge of the block, as a percentage of page width. */
        private int x = 8;

        /** Top of the signature box, as a percentage of page height. Low enough
         *  to sit under the templates' "Signed for and on behalf of" line. */
        private int y = 62;

        private int width = 34;
        private int height = 10;

        /**
         * Also place NAME and DATE fields under the signature, which is what
         * makes it read as a signature block rather than a lone box. Turn off
         * for a template that already prints the signer's name and date.
         */
        private boolean includeNameAndDate = true;

        /** Vertical gap between the stacked fields, in percentage points. */
        private int rowGap = 2;

        /** Height of the NAME and DATE rows. */
        private int rowHeight = 6;
    }

    @Getter
    @Setter
    public static class Webhook {
        /**
         * Shared secret Documenso sends with each delivery.
         *
         * The webhook endpoint is unauthenticated by necessity — Documenso holds
         * no CRM token — so this is the only thing standing between it and the
         * open internet. Blank means the endpoint refuses every delivery rather
         * than trusting one; see {@code ContractWebhookService}.
         */
        private String secret = "";

        /** Documenso's own header name. Configurable because a reverse proxy
         *  may forward it under a different one. */
        private String secretHeader = "X-Documenso-Secret";
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }
}
