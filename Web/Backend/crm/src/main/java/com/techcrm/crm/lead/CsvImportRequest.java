package com.techcrm.crm.lead;

import jakarta.validation.constraints.NotBlank;

public record CsvImportRequest(
        @NotBlank(message = "csv is required") String csv
) {
}
