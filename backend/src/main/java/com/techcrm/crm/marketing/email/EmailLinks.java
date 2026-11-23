package com.techcrm.crm.marketing.email;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Signed links placed in campaign emails: unsubscribe, click and open. A token
 * is "{messageId}.{signature}", where the signature is an HMAC over the
 * purpose and the id, so a link can't be forged or reused for another
 * purpose or message. Links point at the public endpoints in
 * EmailTrackingController; they carry no lead or campaign data.
 */
@Component
public class EmailLinks {

    public enum Purpose { UNSUBSCRIBE, CLICK, OPEN }

    public static final String PUBLIC_PATH = "/api/marketing/public";

    private final EmailSettings settings;

    public EmailLinks(EmailSettings settings) {
        this.settings = settings;
    }

    public String unsubscribeUrl(long messageId) {
        return url("u", Purpose.UNSUBSCRIBE, messageId);
    }

    /** Null when tracking is off: the CTA then links straight to its destination. */
    public String clickUrl(long messageId) {
        return settings.trackingActive() ? url("c", Purpose.CLICK, messageId) : null;
    }

    public String openPixelUrl(long messageId) {
        return settings.trackingActive() ? url("o", Purpose.OPEN, messageId) + ".gif" : null;
    }

    private String url(String path, Purpose purpose, long messageId) {
        if (settings.publicBaseUrl() == null) return null;
        return settings.publicBaseUrl() + PUBLIC_PATH + "/" + path + "/" + token(purpose, messageId);
    }

    public String token(Purpose purpose, long messageId) {
        return messageId + "." + sign(purpose, messageId);
    }

    /** The message id the token was issued for, or null when it is malformed or forged. */
    public Long verify(Purpose purpose, String token) {
        if (token == null || settings.linkSecret() == null) return null;
        int dot = token.indexOf('.');
        if (dot <= 0) return null;
        long id;
        try {
            id = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return null;
        }
        byte[] expected = sign(purpose, id).getBytes(StandardCharsets.US_ASCII);
        byte[] given = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given) ? id : null;
    }

    private String sign(Purpose purpose, long messageId) {
        String secret = settings.linkSecret();
        if (secret == null) throw new IllegalStateException("marketing.email.link-secret is not set");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(("mkt-email:" + purpose.name() + ":" + messageId).getBytes(StandardCharsets.UTF_8));
            // 16 bytes (128 bits) is ample for a link token and keeps URLs short.
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(sig, 16));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
