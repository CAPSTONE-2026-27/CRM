-- Deal coach: from one running chat per user (V23) to separate conversations
-- the user can go back to. A new migration rather than an edit to V23, which is
-- already applied to the shared database.
CREATE TABLE coach_conversations (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT NOT NULL REFERENCES organizations (id),
    user_id          BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- The first question, clipped. Shown in the "Past chats" list.
    title            VARCHAR(120) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Bumped on every message, so the list can show the most recent chat first.
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_coach_conversations_user ON coach_conversations (organization_id, user_id, updated_at DESC);

ALTER TABLE coach_messages
    ADD COLUMN conversation_id BIGINT REFERENCES coach_conversations (id) ON DELETE CASCADE;

-- Messages saved under V23 become one conversation per user, titled from that
-- user's first question, so no existing history disappears.
INSERT INTO coach_conversations (organization_id, user_id, title, created_at, updated_at)
SELECT m.organization_id,
       m.user_id,
       COALESCE(
           LEFT((SELECT f.content FROM coach_messages f
                 WHERE f.organization_id = m.organization_id AND f.user_id = m.user_id AND f.role = 'user'
                 ORDER BY f.id LIMIT 1), 120),
           'Earlier conversation'),
       MIN(m.created_at),
       MAX(m.created_at)
FROM coach_messages m
GROUP BY m.organization_id, m.user_id;

UPDATE coach_messages m
SET conversation_id = c.id
FROM coach_conversations c
WHERE c.organization_id = m.organization_id AND c.user_id = m.user_id;

ALTER TABLE coach_messages ALTER COLUMN conversation_id SET NOT NULL;

DROP INDEX idx_coach_messages_user;
CREATE INDEX idx_coach_messages_conversation ON coach_messages (conversation_id, id);
