-- Inputs and output for the XGBoost deal-scoring model.
--
-- The 17 inputs are stored on the deal rather than in a side table: they are
-- the sales rep's assessment of *this* deal at a point in time, they are edited
-- together with it, and every one of them is single-valued. A separate table
-- would buy nothing but a join.
--
-- Free-text/enum-like values are kept as the labels the model was trained on
-- (e.g. 'Fully Approved') rather than being re-encoded here. Encoding belongs to
-- the model's own pipeline, which owns the ordinal ordering and one-hot layout;
-- duplicating it in SQL would mean two definitions to keep in step.

ALTER TABLE deals
    ADD COLUMN total_meetings             INTEGER,
    ADD COLUMN lead_score                 DOUBLE PRECISION,
    ADD COLUMN customer_sentiment         VARCHAR(40),
    ADD COLUMN buying_intent              VARCHAR(40),
    ADD COLUMN relationship_strength      DOUBLE PRECISION,
    ADD COLUMN budget_status              VARCHAR(40),
    ADD COLUMN decision_maker_involvement VARCHAR(40),
    ADD COLUMN customer_urgency           VARCHAR(40),
    ADD COLUMN main_objections            TEXT,
    ADD COLUMN product_interest_level     VARCHAR(40),
    ADD COLUMN meeting_outcome            VARCHAR(60),
    ADD COLUMN customer_requirements      VARCHAR(80),
    ADD COLUMN risk_factors               VARCHAR(80),
    ADD COLUMN competitor_mention         VARCHAR(20),
    ADD COLUMN engagement_score           DOUBLE PRECISION,
    ADD COLUMN implementation_readiness   VARCHAR(40),
    ADD COLUMN upsell_opportunity         VARCHAR(20);

-- Model output. deal_score_model_version records which model produced the
-- score, so a number can still be explained after the model is retrained.
ALTER TABLE deals
    ADD COLUMN deal_score               DOUBLE PRECISION,
    ADD COLUMN deal_score_band          VARCHAR(20),
    ADD COLUMN deal_score_action        VARCHAR(200),
    ADD COLUMN deal_score_model_version VARCHAR(40),
    ADD COLUMN deal_scored_at           TIMESTAMPTZ;

-- Supports "show me the deals worth working" without a full scan once the
-- pipeline board and forecast start sorting on it.
CREATE INDEX idx_deals_deal_score ON deals (organization_id, deal_score DESC);
