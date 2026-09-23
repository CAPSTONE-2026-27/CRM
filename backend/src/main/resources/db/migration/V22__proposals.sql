-- Proposal generation, signature and the audit trail behind them.
--
-- Deliberately parallel to V19 (contracts) rather than shared with it. The two
-- documents are produced at different points in the pipeline, carry different
-- commercial meaning, and move through different statuses -- a proposal is an
-- offer with an expiry, a contract is an agreement with a term. One table with a
-- document_kind column would have had to make every column nullable that only
-- one kind uses, and the partial index that enforces "one live document per
-- deal" would have needed to span that column too.
--
-- Proposals hang off an existing deal rather than duplicating any of it: the
-- customer, contact, owner and value are all reachable through deal_id, and are
-- referenced here only where the proposal needs to pin *which* one it used.

CREATE TABLE proposals (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id        BIGINT NOT NULL REFERENCES organizations (id),

    -- Human-facing reference ("PRP-000042"), derived from id the same way
    -- contracts.contract_number and deals.opportunity_id are. Nullable only for
    -- the instant between the insert allocating an id and the follow-up update
    -- that fills this in.
    proposal_number        VARCHAR(30),

    deal_id                BIGINT NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    -- Denormalised copy of deals.opportunity_id so a proposal stays readable
    -- (and greppable in logs) without a join.
    opportunity_id         VARCHAR(30),
    account_id             BIGINT NOT NULL REFERENCES accounts (id),
    contact_id             BIGINT REFERENCES contacts (id) ON DELETE SET NULL,
    owner_id               BIGINT REFERENCES users (id) ON DELETE SET NULL,

    -- STANDARD_SALES_PROPOSAL | SOFTWARE_IMPLEMENTATION_PROPOSAL
    -- | PROFESSIONAL_SERVICES_PROPOSAL
    proposal_type          VARCHAR(40) NOT NULL,
    -- The template file actually rendered, kept so a proposal stays explainable
    -- after the template set changes.
    template_key           VARCHAR(120) NOT NULL,

    -- GENERATING | DRAFT | SENT_FOR_SIGNATURE | VIEWED | SIGNED | REJECTED
    -- | FAILED | SUPERSEDED
    --
    -- VARCHAR(30), not the contracts table's 20: SENT_FOR_SIGNATURE is 18
    -- characters and the review statuses this workflow will grow later
    -- (CHANGES_REQUIRED is 16) would sit uncomfortably close to a 20-char limit.
    status                 VARCHAR(30) NOT NULL DEFAULT 'GENERATING',
    failure_reason         TEXT,

    -- Commercial terms frozen at generation time. The deal can be re-priced
    -- afterwards; the document the customer was quoted cannot.
    currency               VARCHAR(3)  NOT NULL,
    total_amount           NUMERIC(14, 2) NOT NULL,

    -- The offer's expiry, and the whole difference between this table and
    -- contracts: a proposal is valid *until* a date, where a contract runs
    -- *between* two. After this the quoted pricing is no longer ours to honour.
    valid_until            DATE,

    payment_terms          VARCHAR(300),
    delivery_timeline      VARCHAR(300),

    -- Paths are relative to contract.storage.root. Proposals share the existing
    -- document store rather than opening a second one, so a per-tenant backup or
    -- deletion still covers everything that tenant has generated.
    docx_path              VARCHAR(500),
    pdf_path               VARCHAR(500),
    docx_size_bytes        BIGINT,
    pdf_size_bytes         BIGINT,

    documenso_document_id  VARCHAR(80),
    documenso_recipient_id VARCHAR(80),
    sign_url               VARCHAR(1000),
    signer_name            VARCHAR(200),
    signer_email           VARCHAR(255),
    sent_for_signature_at  TIMESTAMPTZ,
    signed_at              TIMESTAMPTZ,
    rejected_at            TIMESTAMPTZ,
    rejection_reason       TEXT,

    generated_by           BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT proposals_total_amount_positive CHECK (total_amount > 0)
);

CREATE UNIQUE INDEX uq_proposals_proposal_number ON proposals (proposal_number);
CREATE INDEX idx_proposals_organization_id ON proposals (organization_id);
CREATE INDEX idx_proposals_deal_id ON proposals (deal_id);
CREATE INDEX idx_proposals_account_id ON proposals (account_id);
CREATE INDEX idx_proposals_documenso_document_id ON proposals (documenso_document_id);

-- Idempotency for POST /api/proposals/generate. A retry from the automation
-- platform must not produce a second quotation for the same opportunity -- two
-- different prices in one customer's inbox is worse than a failed retry.
--
-- Partial, not total: a FAILED, REJECTED or SUPERSEDED proposal has released the
-- slot, so the deal can legitimately be re-proposed. GENERATING is inside the
-- index because a render takes tens of seconds and two retries arriving together
-- must not both get through that window.
CREATE UNIQUE INDEX uq_proposals_live_per_deal ON proposals (deal_id)
    WHERE status IN ('GENERATING', 'DRAFT', 'SENT_FOR_SIGNATURE', 'VIEWED', 'SIGNED');

-- What was quoted, snapshotted. As with contract_line_items this is not a
-- product catalogue: the CRM has no product entity, so lines are resolved at
-- generation time from the originating lead (leads.product / product_quantity)
-- or from the deal itself, and then frozen. Re-deriving them when the document is
-- reprinted would let a later lead edit change what a customer was quoted.
--
-- There is no discount or tax column because the CRM records neither, anywhere.
-- Adding them here would mean a quotation printing a Discount row that is always
-- zero.
CREATE TABLE proposal_line_items (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposal_id BIGINT NOT NULL REFERENCES proposals (id) ON DELETE CASCADE,
    line_number INTEGER NOT NULL,
    description VARCHAR(300) NOT NULL,
    quantity    NUMERIC(12, 2) NOT NULL DEFAULT 1,
    unit_price  NUMERIC(14, 2) NOT NULL,
    line_total  NUMERIC(14, 2) NOT NULL,
    -- LEAD_PRODUCT | DEAL_VALUE -- where the line came from, so a reviewer can
    -- tell a real product line from the single-line fallback.
    source      VARCHAR(30) NOT NULL,

    CONSTRAINT uq_proposal_line_items_line UNIQUE (proposal_id, line_number),
    CONSTRAINT proposal_line_items_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_proposal_line_items_proposal_id ON proposal_line_items (proposal_id);

-- Every Documenso webhook delivery that reached us about a proposal, and the
-- guard that makes processing idempotent. Documenso retries failed deliveries,
-- so the same event arrives more than once; the unique constraint turns the
-- second one into a no-op.
--
-- The digest, not just (proposal, event_type): DOCUMENT_OPENED can legitimately
-- fire twice with different timestamps, and dropping the second would lose real
-- history about a customer reading the quotation. A genuine redelivery is
-- byte-identical and so collides.
CREATE TABLE proposal_signature_events (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposal_id           BIGINT NOT NULL REFERENCES proposals (id) ON DELETE CASCADE,
    event_type            VARCHAR(60) NOT NULL,
    documenso_document_id VARCHAR(80),
    payload_digest        VARCHAR(64) NOT NULL,
    received_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_proposal_signature_events UNIQUE (proposal_id, event_type, payload_digest)
);

CREATE INDEX idx_proposal_signature_events_proposal_id ON proposal_signature_events (proposal_id);

/* ------------------------------------------------------------------ deals */

-- The opportunity's view of its proposal. Separate columns from
-- contract_status / contract_signed_at rather than a shared "document status",
-- because a deal legitimately has both at once: an accepted proposal is what
-- justifies drafting the contract that follows it, and collapsing the two would
-- lose which of them a status referred to.
--
-- Deliberately NOT folded into deals.stage, for the reason V19 gives: the stage
-- vocabulary is shared with the frontend pipeline board and adding a value to it
-- would change how every existing deal is bucketed.
ALTER TABLE deals
    ADD COLUMN proposal_status    VARCHAR(30),
    ADD COLUMN proposal_signed_at TIMESTAMPTZ;

COMMENT ON COLUMN deals.proposal_status IS
    'Mirror of the deal''s live proposal status. NULL means no proposal has been generated.';
