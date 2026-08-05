package com.techcrm.crm.lead;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Partial update of a lead. Every field is optional: null means "leave alone",
 * which is what separates this from {@link LeadRequest}, where an omitted field
 * blanks the column.
 *
 * assignedToId is the one field where blank and null differ — blank clears the
 * assignment, null leaves it untouched.
 */
public record LeadPatchRequest(

        @Size(max = 150) String fullName,
        @Size(max = 150) String company,
        @Size(max = 100) String industry,
        @Size(max = 50) String employeeCount,

        @Email(message = "email must be valid")
        @Size(max = 150) String email,

        @Size(max = 30) String phone,
        @Size(max = 200) String product,

        @PositiveOrZero(message = "estimatedDealValue cannot be negative")
        @DecimalMax(value = "9999999999999.99", message = "estimatedDealValue is too large")
        BigDecimal estimatedDealValue,

        @Size(max = 50) String sourceChannel,
        @Size(max = 1000) String notes,
        @Size(max = 30) String status,
        @Size(max = 64) String assignedToId,

        @Size(max = 30) String contactStatus,
        String contactNotes
) {
}
