-- Contract generation, signature and the audit trail behind them.
--
-- V19, not V18: the shared development database already has a V18
-- (lead_score_fluctuation, applied 2026-08-08) whose script is not in this
-- repository. Reusing 18 would have collided with it on checksum validation.
--
-- Contracts hang off an existing deal rather than duplicating any of it: the
-- customer, contact, owner and value are all reachable through deal_id, and are
-- referenced here only where the contract needs to pin *which* one it used.
-- A deal's account can gain contacts after a contract is drafted, so contact_id
-- records the person the document was actually addressed to.

CREATE TABLE contracts (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id        BIGINT NOT NULL REFERENCES organizations (id),

    -- Human-facing reference ("CTR-000042"), derived from id the same way
    -- deals.opportunity_id is. Nullable only for the instant between the
    -- insert allocating an id and the follow-up update that fills this in.
    contract_number        VARCHAR(30),

    deal_id                BIGINT NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    -- Denormalised copy of deals.opportunity_id so a contract stays readable
    -- (and greppable in logs) without a join.
    opportunity_id         VARCHAR(30),
    account_id             BIGINT NOT NULL REFERENCES accounts (id),
    contact_id             BIGINT REFERENCES contacts (id) ON DELETE SET NULL,
    owner_id               BIGINT REFERENCES users (id) ON DELETE SET NULL,

    -- STANDARD_SALES_AGREEMENT | ENTERPRISE_SUBSCRIPTION_AGREEMENT
    -- | PROFESSIONAL_SERVICES_AGREEMENT
    contract_type          VARCHAR(40) NOT NULL,
    -- The template file actually rendered, kept so a contract stays explainable
    -- after the template set changes.
    template_key           VARCHAR(120) NOT NULL,

    -- GENERATED | SENT | VIEWED | SIGNED | REJECTED | FAILED | SUPERSEDED
    status                 VARCHAR(20) NOT NULL DEFAULT 'GENERATED',
    failure_reason         TEXT,

    -- Commercial terms frozen at generation time. The deal can be re-priced
    -- afterwards; the signed document cannot.
    currency               VARCHAR(3)  NOT NULL,
    total_amount           NUMERIC(14, 2) NOT NULL,
    start_date             DATE,
    end_date               DATE,
    payment_terms          VARCHAR(300),
    delivery_timeline      VARCHAR(300),

    -- Paths are relative to contract.storage.root, so relocating the store is
    -- configuration rather than a data migration.
    docx_path              VARCHAR(500),
    pdf_path               VARCHAR(500),
    docx_size_bytes        BIGINT,
    pdf_size_bytes         BIGINT,

    documenso_document_id  VARCHAR(80),
    documenso_recipient_id VARCHAR(80),
    sign_url               VARCHAR(1000),
    signer_name            VARCHAR(200),
    signer_email           VARCHAR(255),
    sent_at                TIMESTAMPTZ,
    signed_at              TIMESTAMPTZ,
    rejected_at            TIMESTAMPTZ,
    rejection_reason       TEXT,

    generated_by           BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT contracts_total_amount_positive CHECK (total_amount > 0),
    CONSTRAINT contracts_dates_ordered CHECK (
        start_date IS NULL OR end_date IS NULL OR end_date > start_date)
);

CREATE UNIQUE INDEX uq_contracts_contract_number ON contracts (contract_number);
CREATE INDEX idx_contracts_organization_id ON contracts (organization_id);
CREATE INDEX idx_contracts_deal_id ON contracts (deal_id);
CREATE INDEX idx_contracts_account_id ON contracts (account_id);
CREATE INDEX idx_contracts_documenso_document_id ON contracts (documenso_document_id);

-- Idempotency for POST /api/contracts/generate. A retry from the automation
-- platform must not produce a second live contract for the same opportunity.
-- Partial, not total: a FAILED, REJECTED or SUPERSEDED contract has released
-- the slot, so the deal can legitimately be re-contracted.
CREATE UNIQUE INDEX uq_contracts_live_per_deal ON contracts (deal_id)
    WHERE status IN ('GENERATED', 'SENT', 'VIEWED', 'SIGNED');

-- What was sold, snapshotted. This is not a product catalogue and does not try
-- to become one -- the CRM has no product entity, so these lines are resolved
-- at generation time from the originating lead (leads.product /
-- product_quantity) or from the deal itself, and then frozen. Re-deriving them
-- when the document is reprinted would let a later lead edit rewrite a signed
-- contract's schedule.
CREATE TABLE contract_line_items (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id BIGINT NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    line_number INTEGER NOT NULL,
    description VARCHAR(300) NOT NULL,
    quantity    NUMERIC(12, 2) NOT NULL DEFAULT 1,
    unit_price  NUMERIC(14, 2) NOT NULL,
    line_total  NUMERIC(14, 2) NOT NULL,
    -- LEAD_PRODUCT | DEAL_VALUE -- where the line came from, so a reviewer can
    -- tell a real product line from the single-line fallback.
    source      VARCHAR(30) NOT NULL,

    CONSTRAINT uq_contract_line_items_line UNIQUE (contract_id, line_number),
    CONSTRAINT contract_line_items_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_contract_line_items_contract_id ON contract_line_items (contract_id);

-- Every Documenso webhook delivery that reached us, and the guard that makes
-- processing idempotent. Documenso retries failed deliveries, so the same event
-- arrives more than once; the unique constraint turns the second one into a
-- no-op instead of a second onboarding record.
--
-- The digest, not just (contract, event_type): DOCUMENT_OPENED can legitimately
-- fire twice with different timestamps, and dropping the second would lose real
-- history. A genuine redelivery is byte-identical and so collides.
CREATE TABLE contract_signature_events (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id           BIGINT NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    event_type            VARCHAR(60) NOT NULL,
    documenso_document_id VARCHAR(80),
    payload_digest        VARCHAR(64) NOT NULL,
    received_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_contract_signature_events UNIQUE (contract_id, event_type, payload_digest)
);

CREATE INDEX idx_contract_signature_events_contract_id ON contract_signature_events (contract_id);

/* ------------------------------------------------------------------ deals */

-- The opportunity's view of its contract. Deliberately NOT folded into
-- deals.stage: the stage vocabulary is shared with the frontend pipeline board
-- and adding a value to it would change how every existing deal is bucketed.
-- Contract progress is a second axis, so it gets its own column.
ALTER TABLE deals
    ADD COLUMN contract_status    VARCHAR(20),
    ADD COLUMN contract_signed_at TIMESTAMPTZ;

COMMENT ON COLUMN deals.contract_status IS
    'Mirror of the deal''s live contract status. NULL means no contract has been generated.';
