-- Accounts, contacts, deals, cases and campaigns — the CRM modules the
-- frontend consumes that this backend did not yet implement. Column sets
-- mirror the shapes the frontend already expects.

CREATE TABLE accounts (
    id                            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id               BIGINT NOT NULL REFERENCES organizations (id),
    name                          VARCHAR(200) NOT NULL,
    industry                      VARCHAR(100),
    annual_revenue                NUMERIC(14, 2),
    employee_count                VARCHAR(50),
    billing_address               VARCHAR(500),
    parent_account_id             BIGINT REFERENCES accounts (id) ON DELETE SET NULL,
    owner_id                      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    relationship_value            NUMERIC(14, 2),
    ai_sentiment_score            INTEGER,
    email_integration_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    telephony_integration_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    doc_repo_sync_enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_accounts_organization_id ON accounts (organization_id);
CREATE INDEX idx_accounts_owner_id ON accounts (owner_id);

CREATE TABLE contacts (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id     BIGINT NOT NULL REFERENCES organizations (id),
    account_id          BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    full_name           VARCHAR(200) NOT NULL,
    job_title           VARCHAR(150),
    email               VARCHAR(255),
    phone               VARCHAR(50),
    role                VARCHAR(30),
    is_primary          BOOLEAN NOT NULL DEFAULT FALSE,
    email_notifications BOOLEAN NOT NULL DEFAULT TRUE,
    sms_notifications   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_contacts_organization_id ON contacts (organization_id);
CREATE INDEX idx_contacts_account_id ON contacts (account_id);

CREATE TABLE deals (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id          BIGINT NOT NULL REFERENCES organizations (id),
    name                     VARCHAR(200) NOT NULL,
    account_id               BIGINT NOT NULL REFERENCES accounts (id),
    value                    NUMERIC(14, 2) NOT NULL,
    currency                 VARCHAR(3) NOT NULL DEFAULT 'USD',
    expected_close_date      TIMESTAMPTZ,
    stage                    VARCHAR(30) NOT NULL DEFAULT 'PROSPECTING',
    probability              INTEGER,
    owner_id                 BIGINT REFERENCES users (id) ON DELETE SET NULL,
    forecast_category        VARCHAR(50),
    weighted_forecast_value  NUMERIC(14, 2),
    best_case_value          NUMERIC(14, 2),
    auto_generate_proposal   BOOLEAN NOT NULL DEFAULT FALSE,
    push_to_erp_on_close     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deals_organization_id ON deals (organization_id);
CREATE INDEX idx_deals_account_id ON deals (account_id);
CREATE INDEX idx_deals_owner_id ON deals (owner_id);

-- case_number is per-organization and user-facing ("#1042"), so it is
-- allocated in the service rather than from a global sequence.
CREATE TABLE cases (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations (id),
    case_number     INTEGER NOT NULL,
    subject         VARCHAR(300) NOT NULL,
    source          VARCHAR(100),
    priority        VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    sla_deadline    TIMESTAMPTZ,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    account_id      BIGINT REFERENCES accounts (id) ON DELETE SET NULL,
    assigned_to_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cases_organization_case_number UNIQUE (organization_id, case_number)
);

CREATE INDEX idx_cases_organization_id ON cases (organization_id);
CREATE INDEX idx_cases_assigned_to_id ON cases (assigned_to_id);

CREATE TABLE campaigns (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations (id),
    name            VARCHAR(200) NOT NULL,
    channel         VARCHAR(30) NOT NULL DEFAULT 'EMAIL',
    goal            VARCHAR(300),
    budget          NUMERIC(14, 2),
    owner_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    start_date      TIMESTAMPTZ,
    end_date        TIMESTAMPTZ,
    segment         VARCHAR(150),
    region          VARCHAR(100),
    estimated_reach INTEGER,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    sent_count      INTEGER NOT NULL DEFAULT 0,
    open_rate_pct   INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_campaigns_organization_id ON campaigns (organization_id);
CREATE INDEX idx_campaigns_owner_id ON campaigns (owner_id);
