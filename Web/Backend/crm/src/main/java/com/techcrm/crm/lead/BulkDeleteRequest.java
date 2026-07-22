package com.techcrm.crm.lead;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BulkDeleteRequest(
        @NotEmpty(message = "ids is required") List<String> ids
) {
}
