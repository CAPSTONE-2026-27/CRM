package com.techcrm.crm.auth.dto;

import java.util.List;

public record CurrentUserResponse(
        String id,
        String fullName,
        String email,
        String role,
        List<String> permissions,
        String avatarUrl,
        String authProvider,
        boolean emailVerified,
        String phone,
        String jobTitle,
        String department,
        boolean mustChangePassword
) {
}
