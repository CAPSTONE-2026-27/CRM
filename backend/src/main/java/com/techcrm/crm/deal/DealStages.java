package com.techcrm.crm.deal;

import java.util.List;
import java.util.Set;

/**
 * The deal pipeline's stage vocabulary, in the order a deal moves through it.
 *
 * MEETING_SCHEDULED and OPPORTUNITY_CREATED were added alongside the original
 * stages rather than replacing them: existing deals sit on PROSPECTING and
 * QUALIFICATION, and renaming those would rewrite history to match a workflow
 * they were never run through.
 *
 * CONTRACT sits between NEGOTIATION and CLOSED_WON. It is the stage a deal
 * reaches once terms are agreed and the paperwork is outstanding, and it is the
 * only stage the contract bot fires from. Separating it from NEGOTIATION means
 * the act of moving the card is the record that negotiation finished -- without
 * it, a contract could be generated while the price was still being discussed,
 * since nothing in this system reads the customer's reply.
 */
public final class DealStages {

    public static final String OPPORTUNITY_CREATED = "OPPORTUNITY_CREATED";

    /**
     * The meeting held with a lead, before it becomes an opportunity.
     *
     * Named MEETING_SCHEDULED rather than LEAD_MEETING because it is the
     * original stage and renaming the value would rewrite the history of every
     * deal that passed through it. The board labels it "Lead Meeting"; this is
     * the same stage under its old name.
     */
    public static final String MEETING_SCHEDULED = "MEETING_SCHEDULED";

    /**
     * The meeting held with an opportunity, after conversion.
     *
     * A lead meeting decides whether there is a deal; this one decides what the
     * deal is worth. The deal scoring model reads what was discussed here, so
     * the stage exists to give that conversation a place on the board rather
     * than leaving it implicit between Opportunity and Proposal.
     */
    public static final String DEAL_MEETING = "DEAL_MEETING";
    public static final String PROSPECTING = "PROSPECTING";
    public static final String QUALIFICATION = "QUALIFICATION";
    public static final String PROPOSAL = "PROPOSAL";
    public static final String NEGOTIATION = "NEGOTIATION";
    public static final String CONTRACT = "CONTRACT";
    public static final String CLOSED_WON = "CLOSED_WON";
    public static final String CLOSED_LOST = "CLOSED_LOST";

    /** Must match DEAL_FLOW_STEPS in the frontend's types.ts — the board's
     *  column order. A converted lead enters at OPPORTUNITY_CREATED, after the
     *  early Prospecting / Qualification / Meeting stages. */
    public static final List<String> ORDERED = List.of(
            PROSPECTING, QUALIFICATION, MEETING_SCHEDULED, OPPORTUNITY_CREATED,
            DEAL_MEETING, PROPOSAL, NEGOTIATION, CONTRACT, CLOSED_WON, CLOSED_LOST);

    public static final Set<String> ALL = Set.copyOf(ORDERED);

    /** The board columns that hold open leads rather than deals. A lead moves
     *  freely among these; reaching OPPORTUNITY_CREATED means converting it. */
    public static final List<String> LEAD_PHASE = List.of(PROSPECTING, QUALIFICATION, MEETING_SCHEDULED);

    private DealStages() {
    }

    /**
     * True when {@code stage} sits earlier in the pipeline than {@code than}.
     *
     * Used where a side effect should advance a deal but never drag it
     * backwards — re-booking a meeting on a deal already at Contract must not
     * return it to Meeting. Comparing positions rather than naming a single
     * stage means the check survives a change to the stage order, which is what
     * went wrong when the first four stages were reordered.
     *
     * Closed stages are terminal and are never earlier than anything.
     */
    public static boolean isBefore(String stage, String than) {
        int a = ORDERED.indexOf(stage);
        int b = ORDERED.indexOf(than);
        if (a < 0 || b < 0 || isClosed(stage)) {
            return false;
        }
        return a < b;
    }

    public static boolean isClosed(String stage) {
        return CLOSED_WON.equals(stage) || CLOSED_LOST.equals(stage);
    }

    /** "OPP-000042" — the deal's own id, zero-padded so references sort. */
    public static String opportunityReference(Long dealId) {
        return String.format("OPP-%06d", dealId);
    }
}
