ALTER TABLE users
    ADD COLUMN username             VARCHAR(100),
    ADD COLUMN status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN employee_id           VARCHAR(50),
    ADD COLUMN manager_id            BIGINT REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN must_change_password  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN last_login_at         TIMESTAMPTZ,
    ADD COLUMN deleted_at            TIMESTAMPTZ;

-- A soft-deleted employee's email must not permanently squat the unique
-- constraint (they could be re-invited later, or a new hire could share
-- the same email). Replace the plain UNIQUE with a partial index scoped
-- to still-active rows.
ALTER TABLE users DROP CONSTRAINT uq_users_email;
CREATE UNIQUE INDEX uq_users_email_active ON users (email) WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_users_username_active ON users (username) WHERE deleted_at IS NULL AND username IS NOT NULL;

CREATE INDEX idx_users_manager_id ON users (manager_id);
CREATE INDEX idx_users_deleted_at ON users (deleted_at);
