-- Separates "a contract row exists" from "a contract document exists".
--
-- V19 inserted the row already marked GENERATED, because that was the status it
-- would end up with anyway. Generation is not instant, though: a template read,
-- a docx4j render and a LibreOffice conversion take tens of seconds, and for all
-- of that time the database claimed a document existed that did not.
--
-- That gap is only a lie until the run finishes -- unless the run never
-- finishes. A dropped connection during the final commit (Neon closes idle
-- connections, and the render holds none for the whole conversion) leaves the
-- row saying GENERATED for ever, with no file behind it. The generate endpoint
-- then treats it as a completed contract on every retry and returns it with null
-- document URLs, and because GENERATED occupies the deal's live-contract slot,
-- nothing else can be generated for that deal either.
--
-- DRAFTING is that intermediate state made explicit.

ALTER TABLE contracts ALTER COLUMN status SET DEFAULT 'DRAFTING';

COMMENT ON COLUMN contracts.status IS
    'DRAFTING | GENERATED | SENT | VIEWED | SIGNED | REJECTED | FAILED | SUPERSEDED. '
    'DRAFTING means the row is reserved but no document has been produced yet; '
    'attachDocuments promotes it to GENERATED once one has.';

-- DRAFTING holds the deal's slot for the duration of the render. It has to: two
-- retries arriving together must not both produce an agreement for the same
-- opportunity, and this index is what stops them -- the loser gets a clean 409
-- instead of a duplicate contract. Kept in step with ContractStatus.LIVE, which
-- ContractStatusTest verifies against this file.
DROP INDEX IF EXISTS uq_contracts_live_per_deal;
CREATE UNIQUE INDEX uq_contracts_live_per_deal ON contracts (deal_id)
    WHERE status IN ('DRAFTING', 'GENERATED', 'SENT', 'VIEWED', 'SIGNED');

-- Rows already stranded by the bug above. They claim GENERATED but no document
-- was ever recorded against them, so they are failed generations that were
-- mislabelled -- FAILED is what they always were, and it releases the deal's
-- slot so the contract can simply be generated again.
--
-- Deliberately not deleted: a failed generation is auditable history, and the
-- rest of this module keeps failures rather than tidying them away.
UPDATE contracts
SET status = 'FAILED',
    failure_reason = COALESCE(
        failure_reason,
        'Generation did not complete: no document was recorded against this contract. '
        || 'Marked FAILED by V20 so the deal can be re-contracted.')
WHERE status = 'GENERATED'
  AND docx_path IS NULL;

-- Deals mirroring one of those contracts are now pointing at a status that no
-- longer exists on the contract itself.
UPDATE deals d
SET contract_status = 'FAILED'
WHERE d.contract_status = 'GENERATED'
  AND NOT EXISTS (
      SELECT 1 FROM contracts c
      WHERE c.deal_id = d.id
        AND c.status IN ('DRAFTING', 'GENERATED', 'SENT', 'VIEWED', 'SIGNED'));
