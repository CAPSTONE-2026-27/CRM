package com.techcrm.crm.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "oldPassword is required") String oldPassword,
        @NotBlank(message = "newPassword is required") @Size(min = 8, message = "newPassword must be at least 8 characters") String newPassword
) {
}
