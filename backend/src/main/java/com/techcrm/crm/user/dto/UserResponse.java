package com.techcrm.crm.user.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record UserResponse(
        String id,
        String fullName,
        String email,
        String username,
        String jobTitle,
        String department,
        String role,
        String status,
        boolean mustChangePassword,
        OffsetDateTime lastLoginAt,
        List<String> permissions
) {
}
