-- Marketing Automation becomes email-only, and sends for real.
--
-- Additive only: no table, column or row is dropped. Poster/SMS/notification/
-- social content rows written before this change stay readable (the
-- application ignores them); mkt_content_variants keeps its channel CHECK as is.

-- Product section: where the email's call-to-action points.
ALTER TABLE mkt_campaigns ADD COLUMN landing_page_url VARCHAR(500);

-- Per-message delivery tracking. sent_at already means "accepted by the
-- provider"; delivered_at is set only by a provider delivery event.
ALTER TABLE mkt_messages ADD COLUMN recipient_email     VARCHAR(254);
ALTER TABLE mkt_messages ADD COLUMN provider_message_id VARCHAR(255);
ALTER TABLE mkt_messages ADD COLUMN attempts            INTEGER     NOT NULL DEFAULT 0;
ALTER TABLE mkt_messages ADD COLUMN next_attempt_at     TIMESTAMPTZ;
ALTER TABLE mkt_messages ADD COLUMN last_attempt_at     TIMESTAMPTZ;
ALTER TABLE mkt_messages ADD COLUMN bounced_at          TIMESTAMPTZ;
ALTER TABLE mkt_messages ADD COLUMN bounce_type         VARCHAR(10);
ALTER TABLE mkt_messages ADD COLUMN complained_at       TIMESTAMPTZ;
ALTER TABLE mkt_messages ADD COLUMN unsubscribed_at     TIMESTAMPTZ;

-- SENDING: claimed by the dispatcher and handed to the provider; never retried
-- if the outcome is lost, so a crash can't cause a duplicate send.
-- SUPPRESSED: not sent because the address is unsubscribed, bounced,
-- complained, missing, or duplicated within the campaign.
ALTER TABLE mkt_messages DROP CONSTRAINT mkt_messages_status_allowed;
ALTER TABLE mkt_messages ADD CONSTRAINT mkt_messages_status_allowed
    CHECK (status IN ('HELD', 'QUEUED', 'SENDING', 'SENT', 'FAILED', 'CANCELLED', 'SUPPRESSED'));
ALTER TABLE mkt_messages ADD CONSTRAINT mkt_messages_bounce_type_allowed
    CHECK (bounce_type IS NULL OR bounce_type IN ('HARD', 'SOFT'));

-- One message per recipient per campaign.
CREATE UNIQUE INDEX uq_mkt_messages_recipient ON mkt_messages (campaign_id, campaign_lead_id, channel);
CREATE INDEX idx_mkt_messages_provider_id ON mkt_messages (provider_message_id) WHERE provider_message_id IS NOT NULL;

-- Addresses that must not be emailed again by this organization's campaigns.
CREATE TABLE mkt_email_suppressions (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id    BIGINT       NOT NULL REFERENCES organizations (id),
    email              VARCHAR(254) NOT NULL,
    reason             VARCHAR(20)  NOT NULL,
    source_message_id  BIGINT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT mkt_email_suppressions_unique UNIQUE (organization_id, email),
    CONSTRAINT mkt_email_suppressions_reason_allowed CHECK (reason IN ('UNSUBSCRIBED', 'HARD_BOUNCE', 'COMPLAINT', 'MANUAL'))
);

-- Execution log: every send attempt and every provider/tracking event, per message.
CREATE TABLE mkt_message_events (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id   BIGINT       NOT NULL REFERENCES mkt_messages (id) ON DELETE CASCADE,
    campaign_id  BIGINT       NOT NULL REFERENCES mkt_campaigns (id) ON DELETE CASCADE,
    type         VARCHAR(30)  NOT NULL,
    detail       VARCHAR(500),
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_mkt_message_events_campaign ON mkt_message_events (campaign_id, occurred_at DESC);
