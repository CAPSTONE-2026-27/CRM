package com.techcrm.crm.auth;

import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.auth.dto.ChangePasswordRequest;
import com.techcrm.crm.auth.dto.CurrentUserResponse;
import com.techcrm.crm.auth.dto.LoginHistoryResponse;
import com.techcrm.crm.auth.dto.LoginRequest;
import com.techcrm.crm.auth.dto.SignupRequest;
import com.techcrm.crm.org.Organization;
import com.techcrm.crm.org.OrganizationRepository;
import com.techcrm.crm.rpa.RpaBotService;
import com.techcrm.crm.user.LoginHistory;
import com.techcrm.crm.user.LoginHistoryRepository;
import com.techcrm.crm.user.PermissionDefaults;
import com.techcrm.crm.user.RefreshToken;
import com.techcrm.crm.user.RefreshTokenRepository;
import com.techcrm.crm.user.Role;
import com.techcrm.crm.user.User;
import com.techcrm.crm.user.UserRepository;
import com.techcrm.crm.user.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

@Service
public class AuthService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final AuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RpaBotService rpaBotService;
    private final long refreshTtlDays;

    public AuthService(
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            LoginHistoryRepository loginHistoryRepository,
            AuditLogService auditLogService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RpaBotService rpaBotService,
            @Value("${app.refresh-token.ttl-days:7}") long refreshTtlDays
    ) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.loginHistoryRepository = loginHistoryRepository;
        this.auditLogService = auditLogService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.rpaBotService = rpaBotService;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Transactional
    public TokenPair signup(SignupRequest request) {
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        Organization org = new Organization();
        org.setName(request.organizationName());
        organizationRepository.save(org);

        User user = new User();
        user.setOrganizationId(org.getId());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setRole(Role.ADMIN);
        user.setPermissions(PermissionDefaults.forRole(Role.ADMIN));
        user.setStatus(UserStatus.ACTIVE);
        // Self-service signup: the admin chose this password themselves,
        // no forced change needed (unlike admin-issued employee credentials).
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Every organization ships with the three built-in bots registered, so
        // the control room and the lead/case triggers have something to run
        // against from day one.
        rpaBotService.registerBuiltInBots(org.getId());

        return issueTokenPair(user);
    }

    /** identifier may be either an email or a username — resolved via a
     *  single case-insensitive OR lookup, since login has no org selector
     *  to disambiguate otherwise. Every attempt (success or failure) is
     *  recorded to login_history for forensic/brute-force visibility; a
     *  failure never reveals *why* beyond a generic 401 — not "wrong
     *  password" vs "account deactivated" vs "unknown identifier" — so
     *  identifier-probing can't learn account existence/status for free. */
    @Transactional
    public TokenPair login(LoginRequest request, String ipAddress, String userAgent) {
        String identifier = request.email();
        User user = userRepository.findActiveByEmailOrUsername(identifier).orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            recordLoginAttempt(null, null, identifier, false, "INVALID_CREDENTIALS", ipAddress, userAgent);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            recordLoginAttempt(user.getId(), user.getOrganizationId(), identifier, false, "ACCOUNT_INACTIVE", ipAddress, userAgent);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);
        recordLoginAttempt(user.getId(), user.getOrganizationId(), identifier, true, null, ipAddress, userAgent);

        return issueTokenPair(user);
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing refresh token");
        }

        String hash = RefreshTokenUtil.hash(rawRefreshToken);

        RefreshToken existing = refreshTokenRepository.findByTokenHashAndRevokedFalse(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token"));

        if (existing.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }

        // Rotation-on-use: this token is now spent, whether or not the caller
        // uses the new one — prevents a stolen refresh token from being
        // replayed after the legitimate client has already rotated it.
        existing.setRevoked(true);
        refreshTokenRepository.save(existing);

        User user = userRepository.findById(existing.getUserId())
                .filter(u -> u.getDeletedAt() == null && u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));

        return issueTokenPair(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String hash = RefreshTokenUtil.hash(rawRefreshToken);
        refreshTokenRepository.findByTokenHashAndRevokedFalse(hash).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse me(AuthenticatedUser principal) {
        User user = activeUserOrThrow(principal.userId());
        return toCurrentUserResponse(user);
    }

    /** Operates on principal.userId() only — no {id} path parameter, so
     *  there is no horizontal-privilege surface by construction. Requires
     *  oldPassword even for a forced first change (defense in depth: a
     *  captured access token alone can't silently take over the account).
     *  Reissues a fresh token pair so the new JWT actually carries the
     *  cleared mustChangePassword claim — without this, the old token
     *  keeps asserting the stale claim until it naturally expires. */
    @Transactional
    public TokenPair changePassword(AuthenticatedUser principal, ChangePasswordRequest request) {
        User user = activeUserOrThrow(principal.userId());

        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(user.getId());
        auditLogService.record(user.getOrganizationId(), user.getId(), "PASSWORD_CHANGED", "User", String.valueOf(user.getId()), null);

        return issueTokenPair(user);
    }

    @Transactional(readOnly = true)
    public Page<LoginHistoryResponse> myLoginHistory(AuthenticatedUser principal, Pageable pageable) {
        return loginHistoryRepository.findByUserIdOrderByOccurredAtDesc(principal.userId(), pageable)
                .map(h -> new LoginHistoryResponse(String.valueOf(h.getId()), h.isSuccess(), h.getFailureReason(), h.getIpAddress(), h.getOccurredAt()));
    }

    private User activeUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null && u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    private void recordLoginAttempt(Long userId, Long organizationId, String identifier, boolean success, String failureReason, String ipAddress, String userAgent) {
        LoginHistory entry = new LoginHistory();
        entry.setUserId(userId);
        entry.setOrganizationId(organizationId);
        entry.setAttemptedIdentifier(identifier);
        entry.setSuccess(success);
        entry.setFailureReason(failureReason);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        loginHistoryRepository.save(entry);
    }

    private TokenPair issueTokenPair(User user) {
        AuthenticatedUser principal = new AuthenticatedUser(
                user.getId(), user.getOrganizationId(), user.getRole(), user.getPermissions(), user.isMustChangePassword());
        String accessToken = jwtService.generateAccessToken(principal);

        String rawRefreshToken = RefreshTokenUtil.generateToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(RefreshTokenUtil.hash(rawRefreshToken));
        refreshToken.setExpiresAt(OffsetDateTime.now().plusDays(refreshTtlDays));
        refreshTokenRepository.save(refreshToken);

        return new TokenPair(accessToken, rawRefreshToken);
    }

    private CurrentUserResponse toCurrentUserResponse(User user) {
        return new CurrentUserResponse(
                String.valueOf(user.getId()),
                user.getFullName(),
                user.getEmail(),
                user.getRole().name(),
                user.getPermissions(),
                null,
                "LOCAL",
                true,
                user.getPhone(),
                user.getJobTitle(),
                user.getDepartment(),
                user.isMustChangePassword()
        );
    }
}
