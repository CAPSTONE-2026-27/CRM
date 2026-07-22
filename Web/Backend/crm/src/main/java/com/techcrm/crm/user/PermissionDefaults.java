package com.techcrm.crm.user;

import java.util.List;
import java.util.Map;

/**
 * Server-side mirror of the frontend's ROLE_DEFAULT_PERMISSIONS
 * (Sidebar.tsx) — used only to seed a new user's permissions column at
 * creation time. Runtime authorization always reads the stored column,
 * never recomputes from role, so admins can override individual users.
 */
public final class PermissionDefaults {

    private static final List<String> ALL = List.of(
            "leads", "pipeline", "accounts", "cases", "workflow",
            "rpa", "marketing", "analytics", "security", "copilot"
    );

    private static final Map<Role, List<String>> DEFAULTS = Map.of(
            Role.ADMIN, ALL,
            Role.MANAGER, ALL,
            Role.SALES_REP, List.of("leads", "pipeline", "accounts", "copilot"),
            Role.SUPPORT_AGENT, List.of("cases", "copilot"),
            Role.MARKETING, List.of("marketing", "analytics", "copilot")
    );

    private PermissionDefaults() {
    }

    public static List<String> forRole(Role role) {
        return DEFAULTS.getOrDefault(role, List.of());
    }
}
