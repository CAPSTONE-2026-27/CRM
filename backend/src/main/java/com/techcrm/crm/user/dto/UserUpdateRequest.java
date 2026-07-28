package com.techcrm.crm.user.dto;

import java.util.List;

/** Partial update — any null field is left untouched. */
public record UserUpdateRequest(
        String fullName,
        String jobTitle,
        String phone,
        String department,
        String role,
        List<String> permissions
) {
}
