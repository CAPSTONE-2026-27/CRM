-- The exact user prompt the marketing model was sent, frozen at evaluation /
-- generation time. ai/adapters/marketing/data/collect_feedback.py pairs it
-- with the Marketing Head's decision to build retraining examples.
--
-- Rebuilding the prompt later from the live lead and campaign would not work:
-- either may have been edited since, and a training example must show the model
-- what it actually saw next to what the human decided about it.
--
-- NULL when no model call was made (rules engine, email check, hand-written
-- content), which is also how the collector knows to skip the row.

ALTER TABLE mkt_campaign_leads ADD COLUMN ai_input TEXT;
ALTER TABLE mkt_content_variants ADD COLUMN ai_input TEXT;
