package com.techcrm.crm.lead;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** Request and response shapes for lead flow steps 4-6. */
public final class LeadFlowDtos {

    private LeadFlowDtos() {
    }

    /** Blank clears the assignment; null is rejected as a missing field. */
    public record AssignRequest(@NotBlank String assignedToId) {
    }

    public record ContactStatusRequest(
            @NotBlank @Size(max = 30) String contactStatus,
            String contactNotes
    ) {
    }

    /** Meeting details are optional: a lead can convert on "Interested" alone
     *  and have the meeting booked afterwards. Supplying them moves the new
     *  opportunity straight to the Meeting scheduled stage. */
    public record ConvertRequest(
            OffsetDateTime meetingScheduledAt,
            @Size(max = 20) String meetingMode,
            String meetingParticipants
    ) {
    }

    public record ConversionResponse(
            String leadId,
            String dealId,
            String opportunityId,
            String accountId,
            boolean accountCreated
    ) {
    }
}
