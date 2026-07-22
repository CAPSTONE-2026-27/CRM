package com.techcrm.crm.auth;

import com.techcrm.crm.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlMillis;

    public JwtService(
            @Value("${app.jwt.access-secret}") String secret,
            @Value("${app.jwt.access-ttl-minutes:30}") long accessTtlMinutes
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMillis = Duration.ofMinutes(accessTtlMinutes).toMillis();
    }

    public String generateAccessToken(AuthenticatedUser user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.userId().toString())
                .claim("organizationId", user.organizationId())
                .claim("role", user.role().name())
                .claim("permissions", user.permissions())
                .claim("mustChangePassword", user.mustChangePassword())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTtlMillis))
                .signWith(key)
                .compact();
    }

    public AuthenticatedUser parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        @SuppressWarnings("unchecked")
        List<String> permissions = claims.get("permissions", List.class);
        Boolean mustChangePassword = claims.get("mustChangePassword", Boolean.class);

        return new AuthenticatedUser(
                Long.valueOf(claims.getSubject()),
                claims.get("organizationId", Number.class).longValue(),
                Role.valueOf(claims.get("role", String.class)),
                permissions,
                Boolean.TRUE.equals(mustChangePassword)
        );
    }
}
