-- Workflow definitions and the Lead Output module's meeting records.

CREATE TABLE workflow_definitions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations (id),
    name            VARCHAR(200) NOT NULL,
    trigger_event   VARCHAR(200),
    scope           VARCHAR(100),
    run_mode        VARCHAR(50),
    is_active       BOOLEAN NOT NULL DEFAULT FALSE,
    -- Node list is stored as JSON: [{type,title,label,operation,order}].
    -- The builder treats it as an ordered document, never queried field-wise.
    nodes           JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_by_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_workflow_definitions_organization_id ON workflow_definitions (organization_id);

-- Append-only meeting history. A new meeting never overwrites an earlier one,
-- and each row keeps the before/after score so a lead's scoring trail stays
-- auditable. Deleting a lead removes its meetings.
CREATE TABLE lead_meetings (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id     BIGINT NOT NULL REFERENCES organizations (id),
    lead_id             BIGINT NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
    recorded_by_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    meeting_date        DATE NOT NULL,
    meeting_time        VARCHAR(5) NOT NULL,
    meeting_output      TEXT NOT NULL,
    ai_summary          TEXT NOT NULL,
    previous_score      INTEGER,
    updated_score       INTEGER,
    score_change_reason TEXT,
    ai_model_version    VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lead_meetings_organization_id ON lead_meetings (organization_id);
CREATE INDEX idx_lead_meetings_lead_id ON lead_meetings (lead_id);
