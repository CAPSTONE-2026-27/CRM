-- assigned_to_id was a free string (never actually populated by any client);
-- convert it into a real FK now that users exist.
ALTER TABLE leads DROP COLUMN assigned_to_id;
ALTER TABLE leads ADD COLUMN assigned_to_id BIGINT REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE leads ADD CONSTRAINT fk_leads_organization FOREIGN KEY (organization_id) REFERENCES organizations (id);

CREATE INDEX idx_leads_assigned_to_id ON leads (assigned_to_id);
