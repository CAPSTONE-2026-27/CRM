-- Proposals follow the contract lifecycle: GENERATING -> GENERATED -> SENT.
--
-- Two changes, both driven by the same thing -- the proposal bot and the
-- contract bot are now the same design, and a status vocabulary that differed
-- only in spelling meant two bots that looked alike but read differently.
--
--   1. DRAFT becomes GENERATED. "Draft" described the document; "generated"
--      describes what the automation platform is waiting for, which is what the
--      status is actually polled for. The contract module already had it right.
--   2. sent_at records when the PDF reached the customer, and SENT joins the
--      live set so an emailed proposal still holds its deal's slot.

ALTER TABLE proposals
    ADD COLUMN IF NOT EXISTS sent_at TIMESTAMPTZ;

-- Existing rows. Every DRAFT proposal in the database is a rendered document
-- nobody has sent, which is exactly what GENERATED means -- so this is a
-- rename, not a reinterpretation.
UPDATE proposals SET status = 'GENERATED' WHERE status = 'DRAFT';

-- SENT and GENERATED join the live set.
--
-- Without SENT the index would treat an emailed proposal as having released the
-- deal's slot, and the next generate would quietly produce a second proposal for
-- a deal whose customer is already holding the first -- two different prices in
-- one inbox, which is exactly what uq_proposals_live_per_deal exists to prevent.
--
-- Recreated rather than altered because Postgres has no ALTER INDEX for a
-- partial index's predicate.
DROP INDEX IF EXISTS uq_proposals_live_per_deal;
CREATE UNIQUE INDEX uq_proposals_live_per_deal ON proposals (deal_id)
    WHERE status IN ('GENERATING', 'GENERATED', 'SENT', 'SENT_FOR_SIGNATURE', 'VIEWED', 'SIGNED');

COMMENT ON COLUMN proposals.sent_at IS
    'When the proposal PDF was emailed to the customer. NULL means it never was.';
