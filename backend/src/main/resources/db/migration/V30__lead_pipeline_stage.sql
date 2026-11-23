-- Where an open lead sits on the sales pipeline board.
--
-- The board's first three columns (Prospecting, Qualification, Meeting) hold
-- leads, not deals; a lead becomes a deal only when it is dragged into
-- Opportunity and converted. This column is the salesperson's placement of the
-- card. It is deliberately separate from qualification_status (the model's
-- verdict) and contact_status (the outcome of first contact): those answer
-- different questions, and deriving the column from them would make a drag
-- impossible to record.
--
-- Values are the deal-stage names for the same columns, so the board maps both
-- record types with one vocabulary: PROSPECTING | QUALIFICATION | MEETING_SCHEDULED.

ALTER TABLE leads
    ADD COLUMN pipeline_stage VARCHAR(30) NOT NULL DEFAULT 'PROSPECTING';

-- One-off placement of existing leads, so the board does not open with every
-- lead in Prospecting. After this, the column changes only when someone moves
-- the card.
UPDATE leads SET pipeline_stage = 'MEETING_SCHEDULED'
WHERE contact_status = 'MEETING_SCHEDULED';

UPDATE leads SET pipeline_stage = 'QUALIFICATION'
WHERE qualification_status = 'QUALIFIED'
  AND contact_status <> 'MEETING_SCHEDULED';
