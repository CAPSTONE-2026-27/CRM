package com.techcrm.crm.marketing.email;

import com.techcrm.crm.marketing.api.MarketingDtos.EmailDeliveryStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Email delivery configuration for Marketing Automation (marketing.email.*).
 * Credentials stay here on the backend; {@link #status()} reports only what is
 * set, never the values.
 *
 * Sending is "configured" only when an SMTP host, a verified sender and a
 * public base URL exist. The public URL is required, not optional, because
 * every campaign email must carry a working unsubscribe link.
 */
@Component
public class EmailSettings {

    public enum SmtpSecurity { STARTTLS, SSL, NONE }

    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUsername;
    private final String smtpPassword;
    private final SmtpSecurity smtpSecurity;
    private final int smtpTimeoutMs;
    private final String fromAddress;
    private final List<String> verifiedSenders;
    private final String publicBaseUrl;
    private final String linkSecret;
    private final String webhookSecret;
    private final String postalAddress;
    private final boolean trackingEnabled;
    private final int maxAttempts;

    public EmailSettings(@Value("${marketing.email.smtp.host:}") String smtpHost,
                         @Value("${marketing.email.smtp.port:587}") int smtpPort,
                         @Value("${marketing.email.smtp.username:}") String smtpUsername,
                         @Value("${marketing.email.smtp.password:}") String smtpPassword,
                         @Value("${marketing.email.smtp.security:STARTTLS}") String smtpSecurity,
                         @Value("${marketing.email.smtp.timeout-ms:15000}") int smtpTimeoutMs,
                         @Value("${marketing.email.from-address:}") String fromAddress,
                         @Value("${marketing.email.verified-senders:}") String verifiedSenders,
                         @Value("${marketing.email.public-base-url:}") String publicBaseUrl,
                         @Value("${marketing.email.link-secret:${app.jwt.access-secret:}}") String linkSecret,
                         @Value("${marketing.email.webhook-secret:}") String webhookSecret,
                         @Value("${marketing.email.postal-address:}") String postalAddress,
                         @Value("${marketing.email.tracking-enabled:true}") boolean trackingEnabled,
                         @Value("${marketing.email.max-attempts:3}") int maxAttempts) {
        this.smtpHost = blankToNull(smtpHost);
        this.smtpPort = smtpPort;
        this.smtpUsername = blankToNull(smtpUsername);
        this.smtpPassword = smtpPassword;
        this.smtpSecurity = parseSecurity(smtpSecurity);
        this.smtpTimeoutMs = smtpTimeoutMs;
        this.fromAddress = normalise(fromAddress);
        this.verifiedSenders = Arrays.stream((verifiedSenders == null ? "" : verifiedSenders).split(","))
                .map(EmailSettings::normalise).filter(s -> s != null).toList();
        String base = blankToNull(publicBaseUrl);
        this.publicBaseUrl = base == null ? null : base.replaceAll("/+$", "");
        this.linkSecret = blankToNull(linkSecret);
        this.webhookSecret = blankToNull(webhookSecret);
        this.postalAddress = blankToNull(postalAddress);
        this.trackingEnabled = trackingEnabled;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** What is missing before email can be sent, as the settings to add. Empty when configured. */
    public List<String> missing() {
        List<String> out = new ArrayList<>();
        if (smtpHost == null) out.add("MARKETING_SMTP_HOST (SMTP server)");
        if (fromAddress == null && verifiedSenders.isEmpty()) {
            out.add("MARKETING_EMAIL_FROM (a sender address verified with your email provider)");
        }
        if (publicBaseUrl == null) {
            out.add("MARKETING_PUBLIC_BASE_URL (public URL of this backend, for unsubscribe and tracking links)");
        }
        if (linkSecret == null) out.add("MARKETING_LINK_SECRET (signs unsubscribe and tracking links)");
        return out;
    }

    public boolean configured() {
        return missing().isEmpty();
    }

    public EmailDeliveryStatus status() {
        List<String> senders = new ArrayList<>();
        if (fromAddress != null) senders.add(fromAddress);
        verifiedSenders.stream().filter(s -> !senders.contains(s)).forEach(senders::add);
        return new EmailDeliveryStatus(configured(), smtpHost == null ? "Not configured" : "SMTP (" + smtpHost + ")",
                missing(), trackingActive(), webhookSecret != null, senders);
    }

    /**
     * True when the address is one the provider is set up to send from: the
     * default from-address, or listed in verified-senders either exactly or
     * by domain ("@example.com"). The CRM can't verify ownership itself; this
     * list is the operator's statement of what the provider has verified.
     */
    public boolean isVerifiedSender(String address) {
        String a = normalise(address);
        if (a == null) return false;
        if (a.equals(fromAddress)) return true;
        String domain = a.substring(a.indexOf('@'));
        return verifiedSenders.stream().anyMatch(v -> v.equals(a) || v.equals(domain));
    }

    /** The sender for a campaign: its own address when set, otherwise the default. */
    public String senderFor(String campaignSender) {
        String s = normalise(campaignSender);
        return s != null ? s : fromAddress;
    }

    public static boolean isAddress(String value) {
        return value != null && ADDRESS.matcher(value.trim()).matches();
    }

    public static String normalise(String address) {
        if (address == null) return null;
        String t = address.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        if (t.startsWith("@")) return t.length() > 1 ? t : null;
        return isAddress(t) ? t : null;
    }

    /** Open and click tracking need a reachable public URL and can be turned off. */
    public boolean trackingActive() {
        return trackingEnabled && publicBaseUrl != null && linkSecret != null;
    }

    public String smtpHost() { return smtpHost; }
    public int smtpPort() { return smtpPort; }
    public String smtpUsername() { return smtpUsername; }
    public String smtpPassword() { return smtpPassword; }
    public SmtpSecurity smtpSecurity() { return smtpSecurity; }
    public int smtpTimeoutMs() { return smtpTimeoutMs; }
    public String publicBaseUrl() { return publicBaseUrl; }
    public String linkSecret() { return linkSecret; }
    public String webhookSecret() { return webhookSecret; }
    public String postalAddress() { return postalAddress; }
    public int maxAttempts() { return maxAttempts; }

    private static SmtpSecurity parseSecurity(String value) {
        try {
            return SmtpSecurity.valueOf(value == null ? "STARTTLS" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SmtpSecurity.STARTTLS;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
