-- The product reports in rupees, so INR becomes the default currency for deals.
-- V9 is already applied and its checksum is recorded, so the change belongs in
-- a new migration rather than an edit to that file.

ALTER TABLE deals ALTER COLUMN currency SET DEFAULT 'INR';

-- Existing rows carry the old default rather than a deliberate choice, so they
-- move across too. A deal explicitly set to another currency is left alone.
UPDATE deals SET currency = 'INR' WHERE currency = 'USD';
