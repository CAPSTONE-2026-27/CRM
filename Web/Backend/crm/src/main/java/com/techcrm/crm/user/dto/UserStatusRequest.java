package com.techcrm.crm.user.dto;

import jakarta.validation.constraints.NotBlank;

public record UserStatusRequest(
        @NotBlank(message = "status is required") String status
) {
}
