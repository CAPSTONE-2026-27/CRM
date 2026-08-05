-- Lead qualification -> assignment -> conversion, and the deal analysis pipeline
-- that turns a meeting write-up into a scored prediction.
--
-- The pipeline is modelled as a chain of one-to-one stages hanging off a single
-- meeting output rather than as columns on `deals`, because every stage is
-- versioned: submitting a second meeting produces a second full chain, and the
-- first stays queryable. A deal's "current" score is simply its newest
-- prediction. Overwriting columns would destroy exactly the progression the
-- sales manager needs to see.

/* ------------------------------------------------------------------ leads */

-- Qualification is the model's verdict, kept separate from `status` (the
-- Hot/Warm/Cold temperature) because they answer different questions: whether
-- to work the lead at all, versus how urgently.
ALTER TABLE leads
    ADD COLUMN qualification_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN qualification_probability DOUBLE PRECISION,
    ADD COLUMN qualification_reasoning   TEXT,

    -- Assignment metadata. assigned_to_id already exists; these record when and
    -- in what state, so an assignment can be audited and chased.
    ADD COLUMN assigned_at               TIMESTAMPTZ,
    ADD COLUMN assignment_status         VARCHAR(20) NOT NULL DEFAULT 'UNASSIGNED',

    -- Outcome of the executive's first contact attempt.
    ADD COLUMN contact_status            VARCHAR(30) NOT NULL DEFAULT 'NOT_CONTACTED',
    ADD COLUMN contact_status_updated_at TIMESTAMPTZ,
    ADD COLUMN contact_notes             TEXT,

    -- Set once the lead becomes an opportunity. Nullable and never reset: a
    -- converted lead keeps pointing at the deal it produced.
    ADD COLUMN converted_deal_id         BIGINT REFERENCES deals (id) ON DELETE SET NULL,
    ADD COLUMN converted_at              TIMESTAMPTZ;

-- Backfills existing rows so the assignment state matches reality rather than
-- claiming every already-assigned lead is unassigned.
UPDATE leads
SET assignment_status = 'ASSIGNED',
    assigned_at       = created_at
WHERE assigned_to_id IS NOT NULL;

CREATE INDEX idx_leads_qualification_status ON leads (organization_id, qualification_status);
CREATE INDEX idx_leads_contact_status ON leads (organization_id, contact_status);

/* ------------------------------------------------------------------ deals */

ALTER TABLE deals
    -- Human-facing opportunity key ("OPP-000042"), derived from the deal's own
    -- id. The numeric id stays the join key; this exists because sales teams
    -- quote a reference in email and "42" is not one. Deriving it rather than
    -- allocating a per-tenant counter avoids a race on concurrent creates for
    -- no loss: uniqueness is inherited from the primary key.
    ADD COLUMN opportunity_id        VARCHAR(30),
    ADD COLUMN lead_id               BIGINT REFERENCES leads (id) ON DELETE SET NULL,

    -- Meeting scheduling (deal flow step 2).
    ADD COLUMN meeting_scheduled_at  TIMESTAMPTZ,
    ADD COLUMN meeting_mode          VARCHAR(20),
    ADD COLUMN meeting_participants  TEXT,

    -- Denormalised newest prediction. deal_predictions is the record of truth;
    -- these exist so the pipeline board can sort and colour thousands of cards
    -- without a correlated subquery per row.
    ADD COLUMN win_probability       DOUBLE PRECISION,
    ADD COLUMN risk_level            VARCHAR(20),

    -- Final decision (step 13).
    ADD COLUMN closing_reason        TEXT,
    ADD COLUMN closed_at             TIMESTAMPTZ;

-- Opportunity ids are unique within an organization, not globally.
CREATE UNIQUE INDEX idx_deals_opportunity_id ON deals (organization_id, opportunity_id)
    WHERE opportunity_id IS NOT NULL;

CREATE INDEX idx_deals_lead_id ON deals (lead_id);

-- Existing deals predate the opportunity key; give them one so every deal can
-- be referenced the same way. LPAD to 6 digits matches the allocator in Java.
UPDATE deals
SET opportunity_id = 'OPP-' || LPAD(id::text, 6, '0')
WHERE opportunity_id IS NULL;

/* --------------------------------------------------- meeting output (step 4) */

-- The structured post-meeting record. Long-form text throughout: the whole
-- point is to capture what was said, and truncating it would cost the analysis
-- model the detail it reasons from.
--
-- version increments per deal, so "meeting 3 of 5" is answerable without
-- counting rows in the client.
CREATE TABLE deal_meeting_outputs (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id       BIGINT NOT NULL REFERENCES organizations (id),
    deal_id               BIGINT NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    opportunity_id        VARCHAR(30),
    lead_id               BIGINT REFERENCES leads (id) ON DELETE SET NULL,
    version               INTEGER NOT NULL,

    meeting_date          DATE NOT NULL,
    meeting_time          VARCHAR(5) NOT NULL,
    meeting_type          VARCHAR(30),
    participants          TEXT,

    meeting_summary       TEXT,
    customer_requirements TEXT,
    key_discussion_points TEXT,
    customer_questions    TEXT,
    competitor_mentioned  TEXT,
    objections            TEXT,
    budget_discussion     TEXT,
    timeline              TEXT,
    next_steps            TEXT,
    executive_remarks     TEXT,

    submitted_by_id       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deal_meeting_outputs_deal_id ON deal_meeting_outputs (deal_id, version DESC);
CREATE INDEX idx_deal_meeting_outputs_organization_id ON deal_meeting_outputs (organization_id);
CREATE UNIQUE INDEX idx_deal_meeting_outputs_version ON deal_meeting_outputs (deal_id, version);

/* ------------------------------------------------- LLM analysis (steps 5-6) */

-- One analysis per meeting output. raw_response keeps the model's reply
-- verbatim: when an extraction looks wrong, the only way to tell a bad prompt
-- from a bad parse is to read what actually came back.
CREATE TABLE deal_analyses (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id   BIGINT NOT NULL REFERENCES organizations (id),
    deal_id           BIGINT NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    opportunity_id    VARCHAR(30),
    lead_id           BIGINT REFERENCES leads (id) ON DELETE SET NULL,
    meeting_output_id BIGINT NOT NULL REFERENCES deal_meeting_outputs (id) ON DELETE CASCADE,

    -- SUCCEEDED | DEGRADED (model unreachable, heuristic fallback used) | FAILED
    status            VARCHAR(20) NOT NULL,
    model_version     VARCHAR(100),
    latency_ms        INTEGER,
    raw_response      TEXT,
    error_message     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deal_analyses_deal_id ON deal_analyses (deal_id, created_at DESC);
CREATE UNIQUE INDEX idx_deal_analyses_meeting_output_id ON deal_analyses (meeting_output_id);

-- One row per extracted business parameter. A table rather than a JSON blob
-- because these are queried across deals ("show every deal where budget_status
-- was extracted with confidence below 0.5") and that is the whole point of
-- storing the confidence.
CREATE TABLE deal_extracted_parameters (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations (id),
    analysis_id     BIGINT NOT NULL REFERENCES deal_analyses (id) ON DELETE CASCADE,
    deal_id         BIGINT NOT NULL REFERENCES deals (id) ON DELETE CASCADE,

    name            VARCHAR(60) NOT NULL,
    value           TEXT,
    confidence      DOUBLE PRECISION,
    explanation     TEXT,
    display_order   INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_deal_extracted_parameters_analysis_id ON deal_extracted_parameters (analysis_id, display_order);
CREATE INDEX idx_deal_extracted_parameters_name ON deal_extracted_parameters (organization_id, name);

/* --------------------------------------- feature engineering (step 7) */

-- The bridge between the language model and the numeric model.
--
-- `features` holds the engineered numeric vector (Positive sentiment -> 0.92);
-- `model_inputs` holds the same information as the categorical labels the
-- XGBoost bundle was trained on. Both are kept because they answer different
-- questions: the numbers are what a human reviews, the labels are what was
-- actually sent to the model. Storing only one would make a disputed score
-- impossible to reproduce.
CREATE TABLE deal_feature_sets (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations (id),
    deal_id         BIGINT NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    analysis_id     BIGINT NOT NULL REFERENCES deal_analyses (id) ON DELETE CASCADE,

    features        JSONB NOT NULL DEFAULT '{}'::jsonb,
    model_inputs    JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Parameters the model had to fall back on a default for, so a score built
    -- on thin evidence can be spotted rather than trusted equally.
    imputed_fields  JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_deal_feature_sets_analysis_id ON deal_feature_sets (analysis_id);
CREATE INDEX idx_deal_feature_sets_deal_id ON deal_feature_sets (deal_id, created_at DESC);

/* ------------------------------------------------ XGBoost prediction (step 8) */

CREATE TABLE deal_predictions (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT NOT NULL REFERENCES organizations (id),
    deal_id            BIGINT NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    opportunity_id     VARCHAR(30),
    lead_id            BIGINT REFERENCES leads (id) ON DELETE SET NULL,
    feature_set_id     BIGINT REFERENCES deal_feature_sets (id) ON DELETE SET NULL,

    deal_score         DOUBLE PRECISION NOT NULL,
    win_probability    DOUBLE PRECISION,
    band               VARCHAR(20),
    risk_level         VARCHAR(20),
    -- Mean extraction confidence: how much evidence the score rests on, which
    -- is not the same thing as how high the score is.
    confidence         DOUBLE PRECISION,
    recommended_action VARCHAR(200),
    positive_factors   JSONB NOT NULL DEFAULT '[]'::jsonb,
    negative_factors   JSONB NOT NULL DEFAULT '[]'::jsonb,
    model_version      VARCHAR(40),
    predicted_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deal_predictions_deal_id ON deal_predictions (deal_id, predicted_at DESC);
CREATE INDEX idx_deal_predictions_organization_id ON deal_predictions (organization_id);

/* ------------------------------------------- manager review (step 10) */

CREATE TABLE deal_manager_reviews (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id     BIGINT NOT NULL REFERENCES organizations (id),
    deal_id             BIGINT NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    opportunity_id      VARCHAR(30),
    prediction_id       BIGINT REFERENCES deal_predictions (id) ON DELETE SET NULL,

    -- APPROVED | REJECTED | OVERRIDDEN
    decision            VARCHAR(20) NOT NULL,
    -- What the model recommended at the time, frozen. The recommendation is
    -- derived from the score, and the score can be recomputed — without this
    -- snapshot a later retrain would silently rewrite what the manager
    -- actually approved.
    recommended_action  VARCHAR(200),
    overridden_action   VARCHAR(200),
    comments            TEXT,
    reviewed_by_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deal_manager_reviews_deal_id ON deal_manager_reviews (deal_id, created_at DESC);

/* --------------------------------------------- customer onboarding (step 14) */

CREATE TABLE customer_onboardings (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations (id),
    deal_id         BIGINT NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    opportunity_id  VARCHAR(30),
    account_id      BIGINT REFERENCES accounts (id) ON DELETE SET NULL,

    -- INITIATED | IN_PROGRESS | COMPLETED | CANCELLED
    status          VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    owner_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    notes           TEXT,
    initiated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

-- A deal is onboarded once, however many times it is re-saved as Closed Won.
CREATE UNIQUE INDEX idx_customer_onboardings_deal_id ON customer_onboardings (deal_id);
