package com.techcrm.crm.marketing.campaign;

import java.util.EnumSet;
import java.util.Set;

/**
 * Campaign lifecycle. The review states (PENDING_* and READY_TO_LAUNCH) are
 * never set by hand: {@code ApprovalService.recompute} derives them from what
 * has actually been approved, so the status can never claim more than the data.
 */
public enum CampaignStatus {
    DRAFT,
    AI_PROCESSING,
    PENDING_LEAD_APPROVAL,
    PENDING_CONTENT_APPROVAL,
    READY_TO_LAUNCH,
    SCHEDULED,
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED;

    /** States in which campaign inputs and AI output may still be edited. */
    public static final Set<CampaignStatus> EDITABLE = EnumSet.of(
            DRAFT, AI_PROCESSING, PENDING_LEAD_APPROVAL, PENDING_CONTENT_APPROVAL, READY_TO_LAUNCH);

    /** States derived from approval progress. */
    public static final Set<CampaignStatus> REVIEW = EnumSet.of(
            PENDING_LEAD_APPROVAL, PENDING_CONTENT_APPROVAL, READY_TO_LAUNCH);

    /** Nothing further can happen to the campaign. */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
