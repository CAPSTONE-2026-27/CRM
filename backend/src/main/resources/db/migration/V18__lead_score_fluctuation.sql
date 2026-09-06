-- Three more lead-scoring inputs: where the customer is, what kind of customer
-- they are, and how the rep has prioritised them.
--
-- RECONSTRUCTED. This migration was applied to the shared development database
-- on 2026-08-08, but its script was never committed on this branch. It was
-- reconstructed from the live schema (columns, lengths and check constraints
-- verified against information_schema and pg_constraint) so that the repository
-- describes the database again -- without it, Flyway reports version 18 as
-- applied-but-missing the moment any later migration exists.
--
-- Rerunning this against a database that already has it is not expected: the
-- schema history was repaired to point at this file rather than replayed. The
-- IF NOT EXISTS guards make a fresh database and an existing one converge
-- anyway.

ALTER TABLE leads
    ADD COLUMN IF NOT EXISTS company_location VARCHAR(150),
    ADD COLUMN IF NOT EXISTS customer_type    VARCHAR(30),
    ADD COLUMN IF NOT EXISTS lead_priority    VARCHAR(20);

-- Enforced as check constraints rather than trusted, for the same reason as
-- leads_purchase_timeline_allowed in V17: the scoring model looks these values
-- up by exact string, so a misspelling scores zero rather than failing visibly.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'leads_customer_type_allowed') THEN
        ALTER TABLE leads
            ADD CONSTRAINT leads_customer_type_allowed
            CHECK (customer_type IS NULL OR customer_type IN (
                'New Business',
                'Existing Customer',
                'Renewal',
                'Upsell',
                'Partner'
            ));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'leads_lead_priority_allowed') THEN
        ALTER TABLE leads
            ADD CONSTRAINT leads_lead_priority_allowed
            CHECK (lead_priority IS NULL OR lead_priority IN (
                'High Priority',
                'Medium Priority',
                'Low Priority',
                'Very Low Priority'
            ));
    END IF;
END $$;
