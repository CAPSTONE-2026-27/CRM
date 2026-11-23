-- V28, not V26/V27: those versions are already applied to the shared database
-- from other branches, and Flyway rejects reusing a version.
--
-- Indexes for the analytics module (/api/analytics/*).
--
-- Every analytics query is "this organization, within this date window", so
-- each table it aggregates gets an (organization_id, <time column>) index. The
-- existing single-column organization_id indexes force a scan of the whole
-- tenant and a filter on time; these let Postgres range-scan the window.
--
-- IF NOT EXISTS so the migration is safe on a database where an index was
-- already added by hand while diagnosing a slow report.

CREATE INDEX IF NOT EXISTS idx_leads_org_created_at
    ON leads (organization_id, created_at);

CREATE INDEX IF NOT EXISTS idx_deals_org_created_at
    ON deals (organization_id, created_at);

-- Won/lost reporting windows on the close time: closed_at, falling back to
-- updated_at for deals closed before V15 added closed_at. Indexed on exactly
-- that expression (AnalyticsRepository.CLOSED_AT) and only for closed deals,
-- which is the predicate every won/lost query carries.
CREATE INDEX IF NOT EXISTS idx_deals_org_close_time
    ON deals (organization_id, (COALESCE(closed_at, updated_at)))
    WHERE stage IN ('CLOSED_WON', 'CLOSED_LOST');

CREATE INDEX IF NOT EXISTS idx_deals_org_scored_at
    ON deals (organization_id, deal_scored_at)
    WHERE deal_scored_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cases_org_created_at
    ON cases (organization_id, created_at);

CREATE INDEX IF NOT EXISTS idx_lead_meetings_org_meeting_date
    ON lead_meetings (organization_id, meeting_date);

CREATE INDEX IF NOT EXISTS idx_deal_meeting_outputs_org_meeting_date
    ON deal_meeting_outputs (organization_id, meeting_date);

CREATE INDEX IF NOT EXISTS idx_deal_predictions_org_predicted_at
    ON deal_predictions (organization_id, predicted_at);

CREATE INDEX IF NOT EXISTS idx_deal_analyses_org_created_at
    ON deal_analyses (organization_id, created_at);

CREATE INDEX IF NOT EXISTS idx_deal_manager_reviews_org_created_at
    ON deal_manager_reviews (organization_id, created_at);

CREATE INDEX IF NOT EXISTS idx_lead_ai_analysis_org_created_at
    ON lead_ai_analysis (organization_id, created_at);

CREATE INDEX IF NOT EXISTS idx_mkt_messages_campaign_sent_at
    ON mkt_messages (campaign_id, sent_at);
