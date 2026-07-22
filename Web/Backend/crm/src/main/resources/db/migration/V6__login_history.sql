-- user_id/organization_id are nullable: a failed login against a
-- non-existent identifier has no user row to attach to, but that attempt
-- is exactly the forensic signal worth keeping (brute-force/enumeration
-- monitoring). attempted_identifier records what was actually typed.
CREATE TABLE login_history (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id               BIGINT REFERENCES users (id) ON DELETE SET NULL,
    organization_id       BIGINT REFERENCES organizations (id) ON DELETE SET NULL,
    attempted_identifier  VARCHAR(150) NOT NULL,
    success               BOOLEAN NOT NULL,
    failure_reason        VARCHAR(100),
    ip_address            VARCHAR(64),
    user_agent            VARCHAR(255),
    occurred_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_history_user_id ON login_history (user_id);
CREATE INDEX idx_login_history_organization_id ON login_history (organization_id);
CREATE INDEX idx_login_history_occurred_at ON login_history (occurred_at);
