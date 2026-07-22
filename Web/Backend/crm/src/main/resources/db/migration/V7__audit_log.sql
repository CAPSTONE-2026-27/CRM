CREATE TABLE audit_log (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id  BIGINT NOT NULL REFERENCES organizations (id),
    actor_user_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    action           VARCHAR(100) NOT NULL,
    entity_type      VARCHAR(50) NOT NULL,
    entity_id        VARCHAR(50),
    detail           VARCHAR(1000),
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_organization_id ON audit_log (organization_id);
CREATE INDEX idx_audit_log_actor_user_id ON audit_log (actor_user_id);
CREATE INDEX idx_audit_log_occurred_at ON audit_log (occurred_at);
