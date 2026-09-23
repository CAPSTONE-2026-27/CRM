package com.techcrm.crm.contract;

import java.util.Set;

/**
 * A contract's lifecycle.
 *
 * A real enum rather than the documented-String pattern used by
 * {@code deals.stage} and {@code rpa_bots.status}: those vocabularies are shared
 * with the frontend and with model training data, so they have to tolerate
 * values this codebase does not know about. Contract status is owned entirely
 * here, and every transition is driven by our own code — the same situation as {@code UserStatus}, which is
 * an enum for the same reason.
 */
public enum ContractStatus {

    /**
     * The row exists and its number is allocated, but no document has been
     * produced yet.
     *
     * Generation takes tens of seconds — a template read, a docx4j render and a
     * LibreOffice conversion — and the row has to exist for the whole of it so
     * that the deal's contract slot is reserved and a concurrent retry is
     * rejected by the index rather than producing a second agreement.
     *
     * It is deliberately not GENERATED. A row saying GENERATED with no file
     * behind it is a lie that outlives the request: if the run dies before
     * {@code attachDocuments} commits — a dropped database connection is enough
     * — the CRM would keep claiming a document exists, and every later retry
     * would be handed that empty record as a success.
     */
    DRAFTING,

    /** Document rendered and stored. Nothing has been sent to the customer. */
    GENERATED,

    /** Emailed to the customer for review. */
    SENT,

    /*
     * VIEWED, SIGNED and REJECTED were set by the former e-signature integration.
     * Nothing sets them now, but existing contracts carry them, and the
     * live-contract index still names them.
     */

    /** The customer opened the document but had not decided yet. */
    VIEWED,

    /** Signed by the customer. Terminal. */
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
    public static final Set<ContractStatus> LIVE =
            Set.of(DRAFTING, GENERATED, SENT, VIEWED, SIGNED);

    public boolean isLive() {
        return LIVE.contains(this);
    }

    /** Nothing moves a contract out of these. */
    public boolean isTerminal() {
        return this == SIGNED || this == REJECTED || this == SUPERSEDED;
    }
}
