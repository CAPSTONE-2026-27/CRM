package com.techcrm.crm.lead;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record LeadRequest(

        @NotBlank(message = "fullName is required")
        @Size(max = 150) String fullName,

        @Size(max = 150)  String company,
        @Size(max = 100)  String industry,
        @Size(max = 50)   String employeeCount,

        @Email(message = "email must be valid")
        @Size(max = 150)  String email,

        @Size(max = 30)   String phone,
        @Size(max = 200)  String product,

        @PositiveOrZero(message = "estimatedDealValue cannot be negative")
        @DecimalMax(value = "9999999999999.99", message = "estimatedDealValue is too large")
        BigDecimal estimatedDealValue,

        /* ---- Lead-scoring factors (see V17) -------------------------------
         * Optional: a rep who does not yet know these should still be able to
         * save the lead. Their absence costs score accuracy, not the record. */

        @PositiveOrZero(message = "productQuantity cannot be negative")
        Integer productQuantity,

        /** Rejected rather than silently accepted when misspelled: the model
         *  looks this value up by exact string, so "within 1 month" would score
         *  zero for urgency and look like a deliberate "no urgency" reading
         *  instead of the typo it is. */
        @Pattern(
                regexp = "Immediately|Within 15 Days|Within 1 Month|Within 2 Months|Within 3 Months|More than 3 Months",
                message = "purchaseTimeline must be one of: Immediately, Within 15 Days, Within 1 Month, "
                        + "Within 2 Months, Within 3 Months, More than 3 Months")
        String purchaseTimeline,

        @Size(max = 50)   String sourceChannel,
        @Size(max = 30)   String captureMethod,
        @Size(max = 1000) String notes,
        @Size(max = 30)   String status,
        @Size(max = 64)   String assignedToId
) {

    /** The only accepted purchaseTimeline values, in descending urgency.
     *
     *  Single source of truth for the frontend dropdown, the CSV importer and
     *  the @Pattern above. These strings are not a UI choice — they must match
     *  the model's training data character for character (TIMELINE_POINTS in
     *  Llama3_CRM/scripts/prompt_format.py is an exact-key lookup). Changing
     *  one here without retraining silently costs that lead its urgency points.
     */
    public static final List<String> PURCHASE_TIMELINES = List.of(
            "Immediately",
            "Within 15 Days",
            "Within 1 Month",
            "Within 2 Months",
            "Within 3 Months",
            "More than 3 Months");
}