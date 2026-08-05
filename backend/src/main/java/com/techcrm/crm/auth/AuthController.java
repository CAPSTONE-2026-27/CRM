package com.techcrm.crm.auth;

import com.techcrm.crm.auth.dto.AuthResponse;
import com.techcrm.crm.auth.dto.ChangePasswordRequest;
import com.techcrm.crm.auth.dto.CurrentUserResponse;
import com.techcrm.crm.auth.dto.LoginHistoryResponse;
import com.techcrm.crm.auth.dto.LoginRequest;
import com.techcrm.crm.auth.dto.SignupRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String REFRESH_COOKIE_NAME = "refreshToken";
    // Scoped to the auth endpoints: the cookie is only ever needed by
    // refresh/logout, so it is not sent with every other API call.
    static final String REFRESH_COOKIE_PATH = "/api/auth";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request, HttpServletResponse response) {
        TokenPair tokens = authService.signup(request);
        setRefreshCookie(response, tokens.rawRefreshToken());
        return ResponseEntity.ok(new AuthResponse(tokens.accessToken()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        TokenPair tokens = authService.login(request, httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"));
        setRefreshCookie(response, tokens.rawRefreshToken());
        return ResponseEntity.ok(new AuthResponse(tokens.accessToken()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
            HttpServletResponse response
    ) {
        TokenPair tokens = authService.refresh(refreshCookie);
        setRefreshCookie(response, tokens.rawRefreshToken());
        return ResponseEntity.ok(new AuthResponse(tokens.accessToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
            HttpServletResponse response
    ) {
        authService.logout(refreshCookie);
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.me(principal);
    }

    @PostMapping("/change-password")
    public ResponseEntity<AuthResponse> changePassword(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletResponse response
    ) {
        TokenPair tokens = authService.changePassword(principal, request);
        setRefreshCookie(response, tokens.rawRefreshToken());
        return ResponseEntity.ok(new AuthResponse(tokens.accessToken()));
    }

    // No {id} in the path at all — every role reads only their own history,
    // by construction, rather than needing a 404-not-403 guard on an
    // id-parameterized route.
    @GetMapping("/me/login-history")
    public Page<LoginHistoryResponse> myLoginHistory(@AuthenticationPrincipal AuthenticatedUser principal, Pageable pageable) {
        return authService.myLoginHistory(principal, pageable);
    }

    private void setRefreshCookie(HttpServletResponse response, String rawRefreshToken) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, rawRefreshToken)
                .httpOnly(true)
                .secure(false) // local dev over http; must be true behind HTTPS
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ofDays(7))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
