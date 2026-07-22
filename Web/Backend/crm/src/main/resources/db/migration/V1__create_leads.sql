CREATE TABLE leads (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lead_name         VARCHAR(150) NOT NULL,
    company           VARCHAR(150),
    email             VARCHAR(150),
    phone             VARCHAR(30),
    industry          VARCHAR(100),
    source            VARCHAR(50),
    product_interest  VARCHAR(200),
    estimated_value   NUMERIC(15, 2),
    location          VARCHAR(150),
    notes             VARCHAR(1000),
    status            VARCHAR(30) NOT NULL DEFAULT 'NEW',
    organization_id   BIGINT,
    owner_name        VARCHAR(150),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_leads_organization_id ON leads (organization_id);
CREATE INDEX idx_leads_status ON leads (status);