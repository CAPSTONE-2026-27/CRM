package com.techcrm.crm.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh tokens are opaque, high-entropy random strings — not JWTs, since
 * nothing needs to be encoded in them, just looked up. Stored as a SHA-256
 * hash (not bcrypt): unlike passwords they're already high-entropy, so a
 * fast deterministic hash is fine for equality lookup and avoids bcrypt's
 * cost factor on every refresh call.
 */
public final class RefreshTokenUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokenUtil() {
    }

    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
