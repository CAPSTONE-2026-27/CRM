-- Nullable: the IMAP poller path has no authenticated caller to attribute
-- leads to. Every other creation path sets this from caller.userId().
ALTER TABLE leads ADD COLUMN created_by BIGINT REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_leads_created_by ON leads (created_by);
