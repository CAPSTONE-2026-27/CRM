package com.techcrm.crm.proposal;

import java.util.Set;

/**
 * A proposal's lifecycle.
 *
 * Parallel to {@link com.techcrm.crm.contract.ContractStatus} but not the same
 * vocabulary, because the two documents mean different things to a customer: a
 * contract is GENERATED and then SENT, and a proposal now follows the same
 * progression so one bot design drives both. The enums stay separate because the
 * tails differ -- a proposal is accepted or declined, a contract is signed -- and
 * sharing one would make every status string ambiguous in the audit log.
 *
 * The review states this workflow will eventually grow — UNDER_REVIEW,
 * APPROVED, CHANGES_REQUIRED — are deliberately absent. They arrive with the
 * frontend that drives them, and adding them now would mean shipping statuses
 * nothing can reach and an index that reserves slots for them.
 */
public enum ProposalStatus {

    /**
     * The row exists and its number is allocated, but no document has been
     * produced yet.
     *
     * The same reasoning as {@code ContractStatus.DRAFTING}: generation takes
     * tens of seconds, the row has to exist for all of it so the deal's slot is
     * reserved, and a row claiming to be a finished DRAFT with no file behind it
     * is a lie that outlives the request when the run dies mid-flight.
     */
    GENERATING,

    /** Document rendered and stored. Nothing has been sent to the customer. */
    GENERATED,

    /**
     * Emailed to the customer with the PDF attached, and awaiting their reply.
     *
     * Distinct from SENT_FOR_SIGNATURE, which meant "handed to the e-signature
     * provider": this proposal is in the customer's inbox, and the reply comes
     * back to a person rather than to a webhook.
     */
    SENT,

    /*
     * SENT_FOR_SIGNATURE, VIEWED, SIGNED and REJECTED were set by the former
     * e-signature integration. Nothing sets them now, but existing proposals
     * carry them, and the live-proposal index still names them.
     */

    /** Handed to the former e-signature provider and dispatched to the signer. */
    SENT_FOR_SIGNATURE,

    /** The customer opened the proposal but had not decided. */
    VIEWED,

    /** Accepted and signed by the customer. Terminal. */
    SIGNED,

    /** Declined by the customer. Terminal for this document; the deal may be
     *  re-proposed. */
    REJECTED,

    /** Generation or conversion failed. Kept rather than deleted so the failure
     *  is auditable. */
    FAILED,

    /** Replaced by a newer proposal for the same deal. */
    SUPERSEDED;

    /**
     * The statuses that occupy a deal's single live-proposal slot, mirroring the
     * {@code uq_proposals_live_per_deal} partial index. Kept in step with that
     * index by {@code ProposalStatusTest}, since a drift between the two shows
     * up as an opaque constraint violation rather than a clear rejection.
     *
     * GENERATING is included for the same reason DRAFTING is on the contract
     * side: two retries arriving together must not both produce a proposal, and
     * the index is what stops them during the seconds a render takes.
     */
    public static final Set<ProposalStatus> LIVE =
            Set.of(GENERATING, GENERATED, SENT, SENT_FOR_SIGNATURE, VIEWED, SIGNED);

    public boolean isLive() {
        return LIVE.contains(this);
    }

    /** Nothing moves a proposal out of these. */
    public boolean isTerminal() {
        return this == SIGNED || this == REJECTED || this == SUPERSEDED;
    }
}
