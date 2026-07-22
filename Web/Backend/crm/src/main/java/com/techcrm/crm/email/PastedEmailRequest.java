package com.techcrm.crm.email;

import jakarta.validation.constraints.NotBlank;

public record PastedEmailRequest(
        String from,
        String subject,
        @NotBlank(message = "body is required") String body
) {
}
