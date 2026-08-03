package com.techcrm.crm.auth;

import com.techcrm.crm.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * Completes a Google/Microsoft sign-in.
 *
 * The provider identity is resolved to a CRM user, the same httpOnly refresh
 * cookie normal login issues is set, and the browser is redirected back to the
 * frontend with no token in the URL — the app's existing silent-refresh-on-mount
 * then picks up the session. Deliberately not returning the access token in a
 * query string, which would leak it into history and referrer headers.
 */
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2SuccessHandler.class);
    private static final String REFRESH_COOKIE_NAME = "refreshToken";

    private final OAuthLoginService oAuthLoginService;
    private final AuthService authService;
    private final String frontendUrl;
    private final long refreshTtlDays;

    public OAuth2SuccessHandler(OAuthLoginService oAuthLoginService,
                                AuthService authService,
                                @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl,
                                @Value("${app.refresh-token.ttl-days:7}") long refreshTtlDays) {
        this.oAuthLoginService = oAuthLoginService;
        this.authService = authService;
        this.frontendUrl = frontendUrl;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String registrationId = token.getAuthorizedClientRegistrationId();
        String provider = "azure".equalsIgnoreCase(registrationId) ? "MICROSOFT" : "GOOGLE";
        OAuth2User principal = token.getPrincipal();

        try {
            User user = oAuthLoginService.resolve(
                    provider,
                    providerAccountId(principal, provider),
                    attribute(principal, "email", "mail", "userPrincipalName", "preferred_username"),
                    attribute(principal, "name", "displayName", "given_name"),
                    attribute(principal, "picture", "avatar_url"));

            TokenPair tokens = authService.issueTokensFor(user);
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE_NAME, tokens.rawRefreshToken())
                    .httpOnly(true)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(Duration.ofDays(refreshTtlDays))
                    .build()
                    .toString());
            // The cookie is set during a redirect the app never observes, so
            // tell it a session now exists — otherwise it skips the silent
            // refresh and shows the login screen despite a valid cookie.
            response.sendRedirect(frontendUrl + "/?signed_in=1");
        } catch (Exception e) {
            log.warn("{} sign-in failed: {}", provider, e.getMessage());
            response.sendRedirect(frontendUrl + "/?oauth_error=" + provider.toLowerCase());
        }
    }

    /** Google exposes "sub"; Microsoft Graph uses "id" (and "oid" on v2 tokens). */
    private String providerAccountId(OAuth2User principal, String provider) {
        String id = attribute(principal, "sub", "id", "oid");
        if (id == null) {
            throw new IllegalStateException(provider + " returned no stable account id");
        }
        return id;
    }

    private String attribute(OAuth2User principal, String... names) {
        for (String name : names) {
            Object value = principal.getAttributes().get(name);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
