-- Lead Score Fluctuation Engine.
--
-- Replaces "the model produces a score and the rep may edit it" with "the model
-- extracts five business signals and the backend scores them against a fixed
-- rule table". Two consequences drive this schema:
--
--   1. The score becomes a *derived* value with an audit trail. Every movement
--      must be explainable after the fact -- which meeting caused it, which
--      signals, and what each contributed -- so history is append-only and the
--      per-meeting signals are stored, not just the resulting number.
--   2. The rep no longer supplies the score, so nothing user-entered may reach
--      it. lead_score_history rows are written only by the engine.
--
-- Scoped to the LEAD module only. The deal module keeps its existing pipeline
-- (dealflow -> XGBoost) untouched; nothing here references deals.
--
-- Modelled as history + analysis tables hanging off a meeting rather than as
-- columns on `leads`, for the same reason V15 did it for the deal pipeline:
-- overwriting columns destroys exactly the progression a sales manager needs to
-- see. The lead's live score stays on `leads` as a denormalised "current value"
-- so existing list and filter queries keep working untouched.

/* ------------------------------------------------------- leads: new context */

-- Context the meeting model needs for proper reasoning but the CRM never
-- captured. All nullable: existing leads have none of them, and a lead is still
-- worth working without them.
ALTER TABLE leads
    ADD COLUMN company_location VARCHAR(150),
    ADD COLUMN customer_type    VARCHAR(30),
    ADD COLUMN lead_priority    VARCHAR(20);

-- Constrained rather than free text: these reach the model as categorical
-- context, and an unconstrained value would be read as a category it has never
-- seen rather than rejected at the boundary.
ALTER TABLE leads
    ADD CONSTRAINT leads_customer_type_allowed
    CHECK (customer_type IS NULL OR customer_type IN (
        'New Business', 'Existing Customer', 'Renewal', 'Upsell', 'Partner'
    ));

-- Matches the engine's priority bands (LeadScoreFluctuationEngine.priorityFor).
ALTER TABLE leads
    ADD CONSTRAINT leads_lead_priority_allowed
    CHECK (lead_priority IS NULL OR lead_priority IN (
        'High Priority', 'Medium Priority', 'Low Priority', 'Very Low Priority'
    ));

/* --------------------------------------------------- lead_ai_analysis */
-- What the fine-tuned model extracted from one qualification meeting.
-- One row per meeting.
--
-- The five scored signals are typed columns rather than rows in a generic
-- name/value table (the shape dealflow's ExtractedParameter uses) because the
-- engine reads them directly on every save: a CHECK constraint makes an
-- out-of-vocabulary value fail at write time instead of silently scoring zero,
-- and the engine stays a pure function of a typed record rather than a map
-- lookup with defaults scattered through it.
--
-- The narrative the engine does not score on lives alongside, where its shape
-- can change without a migration.

CREATE TABLE lead_ai_analysis (
    id                        BIGSERIAL PRIMARY KEY,
    organization_id           BIGINT      NOT NULL,
    lead_id                   BIGINT      NOT NULL,
    -- Nullable: an analysis exists as a preview before its meeting is saved.
    meeting_id                BIGINT,

    -- ---- the five signals the engine scores on ----
    customer_sentiment        VARCHAR(20) NOT NULL,
    buying_intent             VARCHAR(20) NOT NULL,
    decision_maker_involvement VARCHAR(20) NOT NULL,
    customer_urgency          VARCHAR(20) NOT NULL,
    product_interest_level    VARCHAR(20) NOT NULL,

    -- ---- narrative, not scored ----
    meeting_summary           TEXT,
    objections                TEXT,
    key_points                TEXT,
    recommended_next_action   TEXT,

    -- The model's own confidence in its reading, 0-1. Distinct from
    -- qualification_probability: one says "how sure am I of these five values",
    -- the other "how likely is this lead worth pursuing".
    confidence_score          DOUBLE PRECISION,
    qualification_probability DOUBLE PRECISION,

    -- The reply verbatim, for diagnosing a bad reading after the fact.
    raw_response              TEXT,
    -- Anything the model returned that this schema does not model yet, so a
    -- prompt change does not silently discard data until a migration lands.
    extra                     JSONB,

    -- Which adapter produced this. A score is only reproducible if you know
    -- which model read the meeting; without it, comparing two months of history
    -- silently compares two different models.
    model_version             VARCHAR(100),
    -- HEURISTIC when the model was unreachable and the rule-based fallback ran,
    -- REPAIRED when a value had to be snapped onto the vocabulary. A weak
    -- reading must stay distinguishable from a confident one.
    source                    VARCHAR(20) NOT NULL DEFAULT 'MODEL',

    created_by                BIGINT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_lead_ai_analysis_lead FOREIGN KEY (lead_id)
        REFERENCES leads (id) ON DELETE CASCADE,
    CONSTRAINT lead_ai_analysis_source_allowed
        CHECK (source IN ('MODEL', 'REPAIRED', 'HEURISTIC')),
    CONSTRAINT lead_ai_analysis_confidence_range
        CHECK (confidence_score IS NULL OR confidence_score BETWEEN 0 AND 1),
    CONSTRAINT lead_ai_analysis_probability_range
        CHECK (qualification_probability IS NULL OR qualification_probability BETWEEN 0 AND 100)
);

-- The accepted vocabulary. Enforced here rather than trusted from the model:
-- the engine's rule table is keyed on these exact strings, and an unrecognised
-- value would score 0 and read like a deliberate worst-case judgement instead
-- of the parse failure it is.
ALTER TABLE lead_ai_analysis
    ADD CONSTRAINT lead_ai_analysis_vocabulary CHECK (
        customer_sentiment         IN ('Positive', 'Neutral', 'Negative')
    AND buying_intent              IN ('High', 'Medium', 'Low')
    AND decision_maker_involvement IN ('Present', 'Indirect', 'Absent')
    AND customer_urgency           IN ('High', 'Medium', 'Low')
    AND product_interest_level     IN ('High', 'Medium', 'Low')
);

CREATE INDEX idx_lead_ai_analysis_lead ON lead_ai_analysis (organization_id, lead_id, created_at DESC);
CREATE INDEX idx_lead_ai_analysis_meeting ON lead_ai_analysis (meeting_id);

/* ------------------------------------------------- lead_score_history */
-- Append-only record of every score movement: the lead's initial score, then
-- one row per qualification meeting.

CREATE TABLE lead_score_history (
    id                        BIGSERIAL PRIMARY KEY,
    organization_id           BIGINT      NOT NULL,
    lead_id                   BIGINT      NOT NULL,
    -- Null for the initial score, which precedes any meeting.
    meeting_id                BIGINT,
    analysis_id               BIGINT,

    -- Sequence position, so the timeline can label rows "Meeting 3" without
    -- counting rows in the query and without depending on created_at ordering
    -- when two meetings are logged on the same day.
    meeting_number            INTEGER,

    -- The 0-100 score for this meeting alone, from the rule table.
    meeting_score             INTEGER,
    -- The band meeting_score falls into.
    priority                  VARCHAR(20),

    -- Null on the first row: there is no previous score to move from.
    previous_score            INTEGER,
    updated_score             INTEGER     NOT NULL,
    -- Stored rather than derived on read. It is what the timeline shows on
    -- every row, and computing it would mean a window function over a table
    -- that is only ever appended to.
    score_difference          INTEGER     NOT NULL,

    qualification_probability DOUBLE PRECISION,

    -- Per-parameter contributions: [{"parameter":"buying_intent",
    -- "value":"High","points":30,"maxPoints":30}, ...]. This is what makes a
    -- movement explainable rather than merely visible -- the manager sees not
    -- just "-4" but which signal cost it.
    reason_json               JSONB,
    change_reason             TEXT,
    meeting_summary           TEXT,

    -- ENGINE for a computed movement, INITIAL for the lead's first score,
    -- MANUAL for an admin correction. The rep cannot write MANUAL rows; the
    -- value exists so that if a correction path is ever added, corrections stay
    -- distinguishable from engine output.
    source                    VARCHAR(20) NOT NULL DEFAULT 'ENGINE',
    model_version             VARCHAR(100),

    meeting_date              DATE,
    created_by                BIGINT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_lead_score_history_lead FOREIGN KEY (lead_id)
        REFERENCES leads (id) ON DELETE CASCADE,
    CONSTRAINT fk_lead_score_history_analysis FOREIGN KEY (analysis_id)
        REFERENCES lead_ai_analysis (id) ON DELETE SET NULL,
    CONSTRAINT lead_score_history_source_allowed
        CHECK (source IN ('INITIAL', 'ENGINE', 'MANUAL')),
    -- The engine clamps to 0-100; this makes a bug that bypasses it fail loudly
    -- at the database rather than quietly storing a 140.
    CONSTRAINT lead_score_history_score_range
        CHECK (updated_score BETWEEN 0 AND 100),
    CONSTRAINT lead_score_history_previous_range
        CHECK (previous_score IS NULL OR previous_score BETWEEN 0 AND 100),
    CONSTRAINT lead_score_history_meeting_score_range
        CHECK (meeting_score IS NULL OR meeting_score BETWEEN 0 AND 100),
    CONSTRAINT lead_score_history_priority_allowed
        CHECK (priority IS NULL OR priority IN (
            'High Priority', 'Medium Priority', 'Low Priority', 'Very Low Priority'
        ))
);

-- The timeline query: every movement for one lead, oldest first.
CREATE INDEX idx_lead_score_history_lead ON lead_score_history (organization_id, lead_id, created_at);
CREATE INDEX idx_lead_score_history_meeting ON lead_score_history (meeting_id);

/* ----------------------------------------------------------- comments */

COMMENT ON TABLE lead_ai_analysis IS
    'The five business signals the fine-tuned model extracted from one qualification meeting. The model never produces a score; LeadScoreFluctuationEngine computes it from these columns.';
COMMENT ON TABLE lead_score_history IS
    'Append-only record of every lead score movement. Written only by LeadScoreFluctuationEngine -- never from user input.';
COMMENT ON COLUMN lead_score_history.meeting_score IS
    'The 0-100 score for this meeting alone. Rule table: sentiment 20, buying intent 30, decision maker 20, urgency 15, product interest 15.';
COMMENT ON COLUMN lead_score_history.reason_json IS
    'Per-parameter point contributions, so a movement is explainable and not merely visible.';
