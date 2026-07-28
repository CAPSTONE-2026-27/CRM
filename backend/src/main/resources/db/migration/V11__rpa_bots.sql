-- RPA bot registry and run history.

CREATE TABLE rpa_bots (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id      BIGINT NOT NULL REFERENCES organizations (id),
    name                 VARCHAR(200) NOT NULL,
    platform             VARCHAR(30) NOT NULL DEFAULT 'UIPATH',
    bot_type             VARCHAR(20) NOT NULL DEFAULT 'UNATTENDED',
    trigger_source       VARCHAR(200),
    credential_vault_ref VARCHAR(200),
    environment          VARCHAR(50),
    region               VARCHAR(50),
    version              VARCHAR(50),
    status               VARCHAR(20) NOT NULL DEFAULT 'REGISTERED',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rpa_bots_organization_id ON rpa_bots (organization_id);

CREATE TABLE rpa_bot_runs (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id        BIGINT NOT NULL REFERENCES organizations (id),
    bot_id                 BIGINT NOT NULL REFERENCES rpa_bots (id) ON DELETE CASCADE,
    workflow_definition_id BIGINT REFERENCES workflow_definitions (id) ON DELETE SET NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    tasks_completed        INTEGER NOT NULL DEFAULT 0,
    started_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at            TIMESTAMPTZ,
    logs                   TEXT,
    -- "event" | "schedule" | "manual"
    triggered_by           VARCHAR(20),
    error_message          TEXT,
    -- Provenance for AI-driven decisions made during the run.
    ai_model_version       VARCHAR(100),
    ai_confidence          DOUBLE PRECISION
);

CREATE INDEX idx_rpa_bot_runs_organization_id ON rpa_bot_runs (organization_id);
CREATE INDEX idx_rpa_bot_runs_bot_id ON rpa_bot_runs (bot_id);
CREATE INDEX idx_rpa_bot_runs_started_at ON rpa_bot_runs (started_at);
