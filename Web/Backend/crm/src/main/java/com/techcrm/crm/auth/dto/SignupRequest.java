package com.techcrm.crm.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "organizationName is required") @Size(max = 150) String organizationName,
        @NotBlank(message = "fullName is required") @Size(max = 150) String fullName,
        @NotBlank(message = "email is required") @Email(message = "email must be valid") @Size(max = 150) String email,
        @NotBlank(message = "password is required") @Size(min = 8, message = "password must be at least 8 characters") String password
) {
}
