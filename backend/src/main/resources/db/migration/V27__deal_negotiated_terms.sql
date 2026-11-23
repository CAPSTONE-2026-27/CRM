-- Terms agreed during negotiation, recorded on the deal.
--
-- A proposal is a snapshot of what was offered; it must not change after the
-- customer has seen it. What the parties then agree -- usually a lower price,
-- sometimes different payment or delivery terms -- has until now had nowhere to
-- live. The contract took its payment terms and delivery schedule from
-- configuration defaults, so a negotiated concession could not reach the
-- document that formalises it.
--
-- These two columns are that missing record. The agreed price needs no new
-- column: deals.value already is the deal's current worth, and editing it
-- during negotiation is exactly what "the final price" means. The proposal
-- keeps its own frozen line items, so the earlier offer stays auditable.
--
-- Nullable throughout: a deal that was never negotiated has none of these, and
-- the contract assembler falls back to the configured defaults as before.

ALTER TABLE deals
    ADD COLUMN IF NOT EXISTS payment_terms     VARCHAR(300),
    ADD COLUMN IF NOT EXISTS delivery_timeline VARCHAR(300),
    ADD COLUMN IF NOT EXISTS terms_agreed_at   TIMESTAMPTZ;

COMMENT ON COLUMN deals.payment_terms IS
    'Payment terms agreed during negotiation. NULL means the configured default applies.';
COMMENT ON COLUMN deals.delivery_timeline IS
    'Delivery schedule agreed during negotiation. NULL means the configured default applies.';
COMMENT ON COLUMN deals.terms_agreed_at IS
    'When the negotiated terms were last recorded, so a contract can be traced to the agreement behind it.';
