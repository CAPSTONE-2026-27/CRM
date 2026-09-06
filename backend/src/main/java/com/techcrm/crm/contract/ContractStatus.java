package com.techcrm.crm.contract;

import java.util.Set;

/**
 * A contract's lifecycle.
 *
 * A real enum rather than the documented-String pattern used by
 * {@code deals.stage} and {@code rpa_bots.status}: those vocabularies are shared
 * with the frontend and with model training data, so they have to tolerate
 * values this codebase does not know about. Contract status is owned entirely
 * here, and every transition is driven by our own code or by a Documenso
 * webhook we map ourselves — the same situation as {@code UserStatus}, which is
 * an enum for the same reason.
 */
public enum ContractStatus {

    /** Document rendered and stored. Nothing has been sent to the customer. */
    GENERATED,

    /** Handed to Documenso and dispatched to the signer. */
    SENT,

    /** The signer opened the document but has not decided yet. */
    VIEWED,

    /** Signed by the customer. Terminal, and the only status that activates the customer. */
    SIGNED,

    /** Declined by the customer. Terminal for this document; the deal may be re-contracted. */
    REJECTED,

    /** Generation or conversion failed. Kept rather than deleted so the failure is auditable. */
    FAILED,

    /** Replaced by a newer contract for the same deal. */
    SUPERSEDED;

    /**
     * The statuses that occupy a deal's single live-contract slot, mirroring the
     * {@code uq_contracts_live_per_deal} partial index. Kept in sync with that
     * index by {@code ContractStatusTest}, since a drift between the two would
     * show up as an opaque constraint violation rather than a clear rejection.
     */
    public static final Set<ContractStatus> LIVE = Set.of(GENERATED, SENT, VIEWED, SIGNED);

    public boolean isLive() {
        return LIVE.contains(this);
    }

    /** No further Documenso event can move a contract out of these. */
    public boolean isTerminal() {
        return this == SIGNED || this == REJECTED || this == SUPERSEDED;
    }
}
