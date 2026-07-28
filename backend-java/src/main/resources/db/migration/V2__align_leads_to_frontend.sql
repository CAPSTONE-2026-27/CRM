-- Align the leads table with the frontend Lead model

ALTER TABLE leads RENAME COLUMN lead_name        TO full_name;
ALTER TABLE leads RENAME COLUMN product_interest TO product;
ALTER TABLE leads RENAME COLUMN estimated_value  TO estimated_deal_value;
ALTER TABLE leads RENAME COLUMN source           TO source_channel;

ALTER TABLE leads DROP COLUMN owner_name;

ALTER TABLE leads ADD COLUMN employee_count  VARCHAR(50);
ALTER TABLE leads ADD COLUMN capture_method  VARCHAR(30);
ALTER TABLE leads ADD COLUMN assigned_to_id  VARCHAR(64);
ALTER TABLE leads ADD COLUMN ai_score        INTEGER;
ALTER TABLE leads ADD COLUMN ai_score_label  VARCHAR(30);
ALTER TABLE leads ADD COLUMN ai_score_reason VARCHAR(1000);