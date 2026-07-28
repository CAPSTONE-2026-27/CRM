package com.techcrm.crm.auth;

import com.techcrm.crm.user.Role;

import java.util.List;

/**
 * The JWT-derived principal attached to the SecurityContext for every
 * authenticated request. Never re-fetched from the DB per-request — the
 * token is the source of truth for role/permissions until it expires.
 */
public record AuthenticatedUser(
        Long userId,
        Long organizationId,
        Role role,
        List<String> permissions,
        boolean mustChangePassword
) {
}
