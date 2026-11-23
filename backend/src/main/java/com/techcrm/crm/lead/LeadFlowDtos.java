package com.techcrm.crm.lead;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes for lead flow steps 4-6 and the pipeline board. */
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

    /**
     * A person's verdict on whether a lead is worth working.
     *
     * The scoring model normally decides this, and does so the moment a lead is
     * created. This exists for the leads it never reached — a model that was
     * unavailable leaves the verdict at PENDING, and a lead stuck there is one
     * nobody can act on.
     *
     * @param reason why, in the words of whoever decided. Optional, and kept
     *        beside the model's own reasoning rather than replacing it.
     */
    public record QualifyRequest(
            boolean qualified,
            @Size(max = 500) String reason
    ) {
    }

    /** PROSPECTING | QUALIFICATION | MEETING_SCHEDULED — the board's lead columns. */
    public record StageRequest(@NotBlank String stage) {
    }

    public record ConversionResponse(
            String leadId,
            String dealId,
            String opportunityId,
            String accountId,
            boolean accountCreated,
            /** The deal's primary contact, made from (or matched to) the lead's person. */
            String contactId,
            boolean contactCreated
    ) {
    }
}
