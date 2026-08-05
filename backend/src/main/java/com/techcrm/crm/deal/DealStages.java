package com.techcrm.crm.deal;

import java.util.List;
import java.util.Set;

/**
 * The deal pipeline's stage vocabulary, in the order a deal moves through it.
 *
 * OPPORTUNITY_CREATED and MEETING_SCHEDULED were added ahead of the original
 * stages rather than replacing them: existing deals sit on PROSPECTING and
 * QUALIFICATION, and renaming those would rewrite history to match a workflow
 * they were never run through.
 */
public final class DealStages {

    public static final String OPPORTUNITY_CREATED = "OPPORTUNITY_CREATED";
    public static final String MEETING_SCHEDULED = "MEETING_SCHEDULED";
    public static final String PROSPECTING = "PROSPECTING";
    public static final String QUALIFICATION = "QUALIFICATION";
    public static final String PROPOSAL = "PROPOSAL";
    public static final String NEGOTIATION = "NEGOTIATION";
    public static final String CLOSED_WON = "CLOSED_WON";
    public static final String CLOSED_LOST = "CLOSED_LOST";

    public static final List<String> ORDERED = List.of(
            OPPORTUNITY_CREATED, MEETING_SCHEDULED, PROSPECTING, QUALIFICATION,
            PROPOSAL, NEGOTIATION, CLOSED_WON, CLOSED_LOST);

    public static final Set<String> ALL = Set.copyOf(ORDERED);

    private DealStages() {
    }

    public static boolean isClosed(String stage) {
        return CLOSED_WON.equals(stage) || CLOSED_LOST.equals(stage);
    }

    /** "OPP-000042" — the deal's own id, zero-padded so references sort. */
    public static String opportunityReference(Long dealId) {
        return String.format("OPP-%06d", dealId);
    }
}
