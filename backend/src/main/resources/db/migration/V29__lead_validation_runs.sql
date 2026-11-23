-- Lead validation runs, and the indexes the duplicate lookup needs.
--
-- A lead submitted through the Add Lead form or the paste-an-email flow is now
-- validated by an external automation process before the record is created.
-- That process takes seconds rather than milliseconds, so the request that
-- starts it cannot also return its result: the browser polls instead, and this
-- table is what it polls.
--
-- A table rather than an in-process cache, for three reasons. A run in flight
-- survives a restart, which an in-memory map does not. Two backend instances
-- would otherwise disagree about a run's state. And a completed run is a record
-- of why a lead was refused, which is worth keeping long enough to answer the
-- question "why could I not create this lead this morning?".
--
-- Rows are short-lived: a scheduled sweep deletes anything older than an hour.

CREATE TABLE lead_validation_runs (
    id                UUID PRIMARY KEY,
    organization_id   BIGINT      NOT NULL,
    started_by        BIGINT,

    -- PENDING while the process is being started, RUNNING once it has an
    -- instance, COMPLETED when a verdict was read, TIMED_OUT when the process
    -- outlived its budget, FAILED when it could not be started at all.
    state             VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- The furthest milestone observed. The automation calls back into this API
    -- part-way through, so progress is observed rather than reported: see
    -- LeadValidationService.
    step              VARCHAR(40) NOT NULL DEFAULT 'STARTING',

    -- The automation platform's own instance identifier, so a run in this table
    -- can be traced to a run in the platform's monitoring view. Null until the
    -- process has actually been started.
    instance_id       VARCHAR(100),

    -- What was submitted, so a verdict can be read without the browser
    -- re-sending it, and so a refused lead can be inspected afterwards.
    submitted_json    JSONB,

    -- { valid, errors[], warnings[] } once the run completes.
    verdict_json      JSONB,

    -- Why a run failed or timed out, for the message the user sees.
    failure_reason    TEXT,

    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,

    CONSTRAINT lead_validation_runs_state_allowed
        CHECK (state IN ('PENDING', 'RUNNING', 'COMPLETED', 'TIMED_OUT', 'FAILED'))
);

-- The browser polls one run by id; the sweep deletes by age.
CREATE INDEX idx_lead_validation_runs_org ON lead_validation_runs (organization_id, started_at DESC);
CREATE INDEX idx_lead_validation_runs_started ON lead_validation_runs (started_at);

COMMENT ON TABLE lead_validation_runs IS
    'One row per lead validation attempt. Polled by the browser while the external automation runs; swept after an hour.';

/* ------------------------------------------------- duplicate lookup indexes */

-- The duplicate check now runs on every lead submission rather than only on
-- import, and it is called from outside the application, so it is on the
-- critical path of a user waiting at a form. Both lookups are case-insensitive,
-- so plain column indexes would not be used -- these match the expressions
-- LeadRepository actually queries with.
CREATE INDEX IF NOT EXISTS idx_leads_org_email_lower
    ON leads (organization_id, lower(email));

CREATE INDEX IF NOT EXISTS idx_leads_org_fullname_lower
    ON leads (organization_id, lower(full_name));
