package com.techcrm.crm.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserRequest(
        @NotBlank(message = "fullName is required") @Size(max = 150) String fullName,
        @NotBlank(message = "email is required") @Email(message = "email must be valid") @Size(max = 150) String email,
        @NotBlank(message = "password is required") @Size(min = 8, message = "password must be at least 8 characters") String password,
        @Size(max = 100) String username,
        @Size(max = 150) String jobTitle,
        @Size(max = 30) String phone,
        @Size(max = 100) String department,
        @NotBlank(message = "role is required") String role,
        List<String> permissions
) {
}
