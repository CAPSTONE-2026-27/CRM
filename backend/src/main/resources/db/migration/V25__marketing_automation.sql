-- Marketing Automation (Module 11): AI-assisted campaigns with human approval
-- checkpoints. Separate from the legacy `campaigns` table (V9), which keeps
-- serving /api/campaigns unchanged; everything here is prefixed mkt_.
--
-- Two AI pipelines write into this schema — per-lead eligibility scoring
-- (mkt_campaign_leads) and content generation (mkt_content_variants) — and
-- nothing they write is ever used until a person approves it. mkt_activity is
-- the campaign-scoped audit trail recording who (AI, human or system) did what.

CREATE TABLE mkt_campaigns (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id             BIGINT       NOT NULL REFERENCES organizations (id),
    name                        VARCHAR(160) NOT NULL,
    campaign_type               VARCHAR(40)  NOT NULL,
    objective                   VARCHAR(300),
    description                 TEXT,
    start_date                  DATE,
    end_date                    DATE,
    status                      VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',

    -- Section B: product / offer. Confidential by nature — only the content
    -- pipeline ever sees these; the lead pipeline is sent the product name only.
    product_name                VARCHAR(200),
    product_description         TEXT,
    main_benefits               TEXT,
    offer                       TEXT,
    discount                    VARCHAR(120),
    pricing_info                TEXT,
    selling_points              TEXT,
    call_to_action              VARCHAR(200),

    -- Sections C and D as structured JSON (see MarketingDtos.Audience / ContentConfig).
    audience_json               TEXT         NOT NULL DEFAULT '{}',
    content_config_json         TEXT         NOT NULL DEFAULT '{}',
    -- Section E: how mkt_campaign_criteria rows combine.
    rule_match                  VARCHAR(3)   NOT NULL DEFAULT 'ALL',

    -- Strategy / schedule
    strategy_json               TEXT         NOT NULL DEFAULT '{}',
    scheduled_at                TIMESTAMPTZ,

    -- Pipeline progress. *_run increments on every (re)start so a stale worker
    -- from an earlier run can recognise it has been superseded and stop writing.
    lead_pipeline_status        VARCHAR(20)  NOT NULL DEFAULT 'IDLE',
    lead_pipeline_done          INTEGER      NOT NULL DEFAULT 0,
    lead_pipeline_total         INTEGER      NOT NULL DEFAULT 0,
    lead_pipeline_error         VARCHAR(500),
    lead_pipeline_run           INTEGER      NOT NULL DEFAULT 0,
    content_pipeline_status     VARCHAR(20)  NOT NULL DEFAULT 'IDLE',
    content_pipeline_error      VARCHAR(500),
    content_pipeline_run        INTEGER      NOT NULL DEFAULT 0,

    created_by                  BIGINT REFERENCES users (id),
    launched_at                 TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT mkt_campaigns_status_allowed CHECK (status IN (
        'DRAFT', 'AI_PROCESSING', 'PENDING_LEAD_APPROVAL', 'PENDING_CONTENT_APPROVAL',
        'READY_TO_LAUNCH', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT mkt_campaigns_rule_match_allowed CHECK (rule_match IN ('ALL', 'ANY')),
    CONSTRAINT mkt_campaigns_dates_ordered CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_mkt_campaigns_org ON mkt_campaigns (organization_id, created_at DESC);
CREATE INDEX idx_mkt_campaigns_status ON mkt_campaigns (status);

CREATE TABLE mkt_campaign_criteria (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id  BIGINT       NOT NULL REFERENCES mkt_campaigns (id) ON DELETE CASCADE,
    position     INTEGER      NOT NULL,
    field        VARCHAR(40)  NOT NULL,
    operator     VARCHAR(12)  NOT NULL,
    value        VARCHAR(300) NOT NULL
);

CREATE INDEX idx_mkt_criteria_campaign ON mkt_campaign_criteria (campaign_id, position);

-- One row per (campaign, lead): the AI's individual evaluation plus the
-- Marketing Head's decision on it. AI fields and human fields are kept apart
-- so the audit question "what did the model say vs. what did we do" always
-- has an answer.
CREATE TABLE mkt_campaign_leads (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id            BIGINT       NOT NULL REFERENCES mkt_campaigns (id) ON DELETE CASCADE,
    lead_id                BIGINT       NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
    ai_score               INTEGER,
    ai_eligibility         VARCHAR(20),
    ai_confidence          DOUBLE PRECISION,
    positive_factors       TEXT         NOT NULL DEFAULT '[]',
    negative_factors       TEXT         NOT NULL DEFAULT '[]',
    ai_reason              TEXT,
    rule_results           TEXT         NOT NULL DEFAULT '[]',
    -- True when the deterministic rule check overruled an "eligible" from the
    -- model because a Marketing Head rule definitively failed.
    rule_guard_applied     BOOLEAN      NOT NULL DEFAULT FALSE,
    evaluation_source      VARCHAR(20),
    model_name             VARCHAR(120),
    evaluated_at           TIMESTAMPTZ,
    approval_status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    override_eligibility   VARCHAR(20),
    review_note            VARCHAR(500),
    reviewed_by            BIGINT REFERENCES users (id),
    reviewed_at            TIMESTAMPTZ,
    CONSTRAINT mkt_campaign_leads_unique UNIQUE (campaign_id, lead_id),
    CONSTRAINT mkt_campaign_leads_score_range CHECK (ai_score IS NULL OR ai_score BETWEEN 0 AND 100),
    CONSTRAINT mkt_campaign_leads_eligibility_allowed CHECK (ai_eligibility IS NULL OR ai_eligibility IN ('ELIGIBLE', 'NOT_ELIGIBLE')),
    CONSTRAINT mkt_campaign_leads_override_allowed CHECK (override_eligibility IS NULL OR override_eligibility IN ('ELIGIBLE', 'NOT_ELIGIBLE')),
    CONSTRAINT mkt_campaign_leads_approval_allowed CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'REMOVED'))
);

CREATE INDEX idx_mkt_campaign_leads_campaign ON mkt_campaign_leads (campaign_id, approval_status);

CREATE TABLE mkt_content_variants (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id          BIGINT      NOT NULL REFERENCES mkt_campaigns (id) ON DELETE CASCADE,
    channel              VARCHAR(20) NOT NULL,
    variant              VARCHAR(1)  NOT NULL DEFAULT 'A',
    fields_json          TEXT        NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW',
    provenance           VARCHAR(20) NOT NULL,
    model_name           VARCHAR(120),
    generation_count     INTEGER     NOT NULL DEFAULT 1,
    validation_warnings  TEXT        NOT NULL DEFAULT '[]',
    review_note          VARCHAR(500),
    reviewed_by          BIGINT REFERENCES users (id),
    reviewed_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mkt_content_unique UNIQUE (campaign_id, channel, variant),
    CONSTRAINT mkt_content_channel_allowed CHECK (channel IN ('CORE', 'EMAIL', 'POSTER', 'SMS', 'NOTIFICATION', 'SOCIAL')),
    CONSTRAINT mkt_content_variant_allowed CHECK (variant IN ('A', 'B')),
    CONSTRAINT mkt_content_status_allowed CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT mkt_content_provenance_allowed CHECK (provenance IN ('AI_GENERATED', 'HUMAN_EDITED', 'SYSTEM_GENERATED', 'HUMAN_AUTHORED'))
);

CREATE TABLE mkt_ab_tests (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id             BIGINT      NOT NULL UNIQUE REFERENCES mkt_campaigns (id) ON DELETE CASCADE,
    enabled                 BOOLEAN     NOT NULL DEFAULT FALSE,
    test_element            VARCHAR(20) NOT NULL DEFAULT 'EMAIL_SUBJECT',
    split_a_pct             INTEGER     NOT NULL DEFAULT 50,
    -- Share of the approved audience that takes part in the test. The rest is
    -- held back and receives whichever variant the decision rule selects.
    test_audience_pct       INTEGER     NOT NULL DEFAULT 100,
    duration_hours          INTEGER     NOT NULL DEFAULT 72,
    primary_metric          VARCHAR(20) NOT NULL DEFAULT 'OPEN_RATE',
    decision_rule           VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    min_sample_per_variant  INTEGER     NOT NULL DEFAULT 100,
    status                  VARCHAR(20) NOT NULL DEFAULT 'CONFIGURED',
    started_at              TIMESTAMPTZ,
    ends_at                 TIMESTAMPTZ,
    winner_variant          VARCHAR(1),
    decided_by              BIGINT REFERENCES users (id),
    decided_at              TIMESTAMPTZ,
    decision_note           VARCHAR(500),
    CONSTRAINT mkt_ab_split_range CHECK (split_a_pct BETWEEN 1 AND 99),
    CONSTRAINT mkt_ab_audience_range CHECK (test_audience_pct BETWEEN 10 AND 100),
    CONSTRAINT mkt_ab_duration_range CHECK (duration_hours BETWEEN 1 AND 720),
    CONSTRAINT mkt_ab_metric_allowed CHECK (primary_metric IN ('OPEN_RATE', 'CLICK_RATE', 'CONVERSION_RATE', 'RESPONSE_RATE')),
    CONSTRAINT mkt_ab_rule_allowed CHECK (decision_rule IN ('MANUAL', 'HIGHEST_PRIMARY_METRIC')),
    CONSTRAINT mkt_ab_status_allowed CHECK (status IN ('CONFIGURED', 'RUNNING', 'AWAITING_DECISION', 'DECIDED')),
    CONSTRAINT mkt_ab_winner_allowed CHECK (winner_variant IS NULL OR winner_variant IN ('A', 'B'))
);

CREATE TABLE mkt_approvals (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT      NOT NULL REFERENCES organizations (id),
    campaign_id      BIGINT      NOT NULL REFERENCES mkt_campaigns (id) ON DELETE CASCADE,
    kind             VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    submitted_by     BIGINT REFERENCES users (id),
    submitted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by       BIGINT REFERENCES users (id),
    decided_at       TIMESTAMPTZ,
    note             VARCHAR(500),
    CONSTRAINT mkt_approvals_kind_allowed CHECK (kind IN ('LEADS', 'CONTENT', 'CAMPAIGN')),
    CONSTRAINT mkt_approvals_status_allowed CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
);

CREATE INDEX idx_mkt_approvals_queue ON mkt_approvals (organization_id, status, submitted_at);

-- One row per recipient per campaign: the outbox and the performance ledger.
-- Every metric the dashboards show is counted from these timestamps — there
-- is no separately-stored aggregate that could drift from them.
CREATE TABLE mkt_messages (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id       BIGINT      NOT NULL REFERENCES mkt_campaigns (id) ON DELETE CASCADE,
    campaign_lead_id  BIGINT      NOT NULL REFERENCES mkt_campaign_leads (id) ON DELETE CASCADE,
    lead_id           BIGINT      NOT NULL,
    channel           VARCHAR(20) NOT NULL,
    variant           VARCHAR(1),
    status            VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    queued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ,
    opened_at         TIMESTAMPTZ,
    clicked_at        TIMESTAMPTZ,
    responded_at      TIMESTAMPTZ,
    converted_at      TIMESTAMPTZ,
    failure_reason    VARCHAR(300),
    CONSTRAINT mkt_messages_status_allowed CHECK (status IN ('HELD', 'QUEUED', 'SENT', 'FAILED', 'CANCELLED')),
    CONSTRAINT mkt_messages_variant_allowed CHECK (variant IS NULL OR variant IN ('A', 'B'))
);

CREATE INDEX idx_mkt_messages_dispatch ON mkt_messages (status, campaign_id);
CREATE INDEX idx_mkt_messages_campaign ON mkt_messages (campaign_id, variant);

CREATE TABLE mkt_activity (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organizations (id),
    campaign_id      BIGINT       NOT NULL REFERENCES mkt_campaigns (id) ON DELETE CASCADE,
    actor_type       VARCHAR(10)  NOT NULL,
    actor_user_id    BIGINT REFERENCES users (id),
    action           VARCHAR(60)  NOT NULL,
    summary          VARCHAR(500) NOT NULL,
    detail_json      TEXT,
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT mkt_activity_actor_allowed CHECK (actor_type IN ('AI', 'HUMAN', 'SYSTEM'))
);

CREATE INDEX idx_mkt_activity_campaign ON mkt_activity (campaign_id, occurred_at DESC);
