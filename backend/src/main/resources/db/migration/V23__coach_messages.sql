-- Deal coach chat history, so a conversation survives navigating away, a reload,
-- or switching devices. One row per message; a user has one running
-- conversation, which they can clear.
--
-- V23, not V19/V20/V22: those versions are already applied to the shared
-- database from other branches, and Flyway rejects reusing a version.
--
-- Scoped by organization and user like every other table. Deleting a user
-- deletes their chat: it quotes their own pipeline, and keeping it would outlive
-- the access that produced it.
CREATE TABLE coach_messages (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT NOT NULL REFERENCES organizations (id),
    user_id          BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role             VARCHAR(10) NOT NULL CHECK (role IN ('user', 'assistant')),
    content          TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The only query: this user's most recent messages.
CREATE INDEX idx_coach_messages_user ON coach_messages (organization_id, user_id, id);
