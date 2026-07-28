package com.techcrm.crm.user;

import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.user.dto.ResetPasswordResponse;
import com.techcrm.crm.user.dto.UserRequest;
import com.techcrm.crm.user.dto.UserResponse;
import com.techcrm.crm.user.dto.UserStatusRequest;
import com.techcrm.crm.user.dto.UserUpdateRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_PASSWORD_ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789!@#$%";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public UserService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            AuditLogService auditLogService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /** Lighter counterpart to Lead search — no pagination this pass (see
     *  UserSpecifications Javadoc). Org-scope and exclude-soft-deleted are
     *  always ANDed in first, before any caller-supplied filter. */
    @Transactional(readOnly = true)
    public List<UserResponse> search(AuthenticatedUser caller, String q, String role, String department, String status) {
        Specification<User> spec = Specification.where(UserSpecifications.organizationId(caller.organizationId()))
                .and(UserSpecifications.notDeleted());

        if (q != null && !q.isBlank()) spec = spec.and(UserSpecifications.nameOrEmailContains(q.trim()));
        if (role != null && !role.isBlank()) spec = spec.and(UserSpecifications.role(parseRole(role)));
        if (department != null && !department.isBlank()) spec = spec.and(UserSpecifications.departmentEquals(department.trim()));
        if (status != null && !status.isBlank()) spec = spec.and(UserSpecifications.status(parseStatus(status)));

        return userRepository.findAll(spec).stream().map(this::toResponse).toList();
    }

    @Transactional
    public UserResponse create(AuthenticatedUser caller, UserRequest request) {
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        Role role = parseRole(request.role());

        User user = new User();
        user.setOrganizationId(caller.organizationId());
        user.setEmail(request.email());
        user.setUsername(resolveUsername(request.username(), request.email()));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setJobTitle(request.jobTitle());
        user.setPhone(request.phone());
        user.setDepartment(request.department());
        user.setRole(role);
        user.setPermissions(request.permissions() != null ? request.permissions() : PermissionDefaults.forRole(role));
        user.setStatus(UserStatus.ACTIVE);
        // Admin-issued credentials always require a change on first login —
        // the admin knows this password too, so it can't stay valid past
        // the new employee's first real session.
        user.setMustChangePassword(true);

        User saved = userRepository.save(user);
        auditLogService.record(caller.organizationId(), caller.userId(), "USER_CREATED", "User", String.valueOf(saved.getId()),
                "Created employee " + saved.getEmail() + " with role " + role);
        return toResponse(saved);
    }

    @Transactional
    public UserResponse update(AuthenticatedUser caller, Long id, UserUpdateRequest request) {
        User user = getOrThrow(caller, id);

        if (request.fullName() != null) user.setFullName(request.fullName());
        if (request.jobTitle() != null) user.setJobTitle(request.jobTitle());
        if (request.phone() != null) user.setPhone(request.phone());
        if (request.department() != null) user.setDepartment(request.department());
        if (request.role() != null) user.setRole(parseRole(request.role()));
        if (request.permissions() != null) user.setPermissions(request.permissions());

        User saved = userRepository.save(user);
        auditLogService.record(caller.organizationId(), caller.userId(), "USER_UPDATED", "User", String.valueOf(id), null);
        return toResponse(saved);
    }

    /** Activate/deactivate an employee. Deactivating immediately revokes
     *  their refresh tokens (their still-live access token remains valid
     *  until its own natural expiry — an accepted, documented tradeoff of
     *  never re-checking the DB per request). Blocked from deactivating the
     *  organization's last remaining active admin. */
    @Transactional
    public UserResponse setStatus(AuthenticatedUser caller, Long id, UserStatusRequest request) {
        User user = getOrThrow(caller, id);
        UserStatus newStatus = parseStatus(request.status());

        if (newStatus == UserStatus.INACTIVE) {
            guardLastActiveAdmin(caller.organizationId(), user);
            refreshTokenRepository.revokeAllForUser(user.getId());
        }

        user.setStatus(newStatus);
        User saved = userRepository.save(user);
        auditLogService.record(caller.organizationId(), caller.userId(),
                newStatus == UserStatus.ACTIVE ? "USER_ACTIVATED" : "USER_DEACTIVATED",
                "User", String.valueOf(id), null);
        return toResponse(saved);
    }

    /** Admin-supplied or randomly generated new password. Revokes existing
     *  refresh tokens and forces a change on next login. The plaintext is
     *  returned exactly once in the response — never logged, never stored. */
    @Transactional
    public ResetPasswordResponse resetPassword(AuthenticatedUser caller, Long id, String adminSuppliedPassword) {
        User user = getOrThrow(caller, id);

        String plaintext = (adminSuppliedPassword != null && !adminSuppliedPassword.isBlank())
                ? adminSuppliedPassword
                : generateTemporaryPassword();

        user.setPasswordHash(passwordEncoder.encode(plaintext));
        user.setMustChangePassword(true);
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(user.getId());

        auditLogService.record(caller.organizationId(), caller.userId(), "USER_PASSWORD_RESET", "User", String.valueOf(id), null);
        return new ResetPasswordResponse(plaintext);
    }

    /** Soft delete: marks the row deleted/inactive and revokes tokens
     *  instead of removing the row, so no history/foreign-key data is lost
     *  (leads.created_by, audit_log.actor_user_id, etc. keep resolving). */
    @Transactional
    public void delete(AuthenticatedUser caller, Long id) {
        User user = getOrThrow(caller, id);
        guardLastActiveAdmin(caller.organizationId(), user);

        user.setDeletedAt(OffsetDateTime.now());
        user.setStatus(UserStatus.INACTIVE);
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(user.getId());

        auditLogService.record(caller.organizationId(), caller.userId(), "USER_DELETED", "User", String.valueOf(id), null);
    }

    private void guardLastActiveAdmin(Long organizationId, User user) {
        if (user.getRole() == Role.ADMIN && user.getStatus() == UserStatus.ACTIVE
                && userRepository.countByOrganizationIdAndRoleAndStatusAndDeletedAtIsNull(organizationId, Role.ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove or deactivate the last admin of an organization");
        }
    }

    private User getOrThrow(AuthenticatedUser caller, Long id) {
        return userRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found with id " + id));
    }

    private Role parseRole(String raw) {
        try {
            return Role.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role: " + raw);
        }
    }

    private UserStatus parseStatus(String raw) {
        try {
            return UserStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + raw);
        }
    }

    private String resolveUsername(String requested, String email) {
        String base = (requested != null && !requested.isBlank())
                ? requested.trim().toLowerCase()
                : email.substring(0, email.indexOf('@')).toLowerCase().replaceAll("[^a-z0-9._-]", "");

        String candidate = base;
        int suffix = 1;
        while (userRepository.findActiveByEmailOrUsername(candidate).isPresent()) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }

    private String generateTemporaryPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(TEMP_PASSWORD_ALPHABET.charAt(RANDOM.nextInt(TEMP_PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(
                String.valueOf(u.getId()),
                u.getFullName(),
                u.getEmail(),
                u.getUsername(),
                u.getJobTitle(),
                u.getDepartment(),
                u.getRole().name(),
                u.getStatus().name(),
                u.isMustChangePassword(),
                u.getLastLoginAt(),
                u.getPermissions()
        );
    }
}
