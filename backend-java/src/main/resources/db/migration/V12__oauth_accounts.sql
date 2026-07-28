-- Google / Microsoft sign-in.

-- OAuth-only accounts never have a password, so the column can no longer be
-- mandatory. Local accounts are still required to set one in code.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users
    ADD COLUMN auth_provider        VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN provider_account_id  VARCHAR(255),
    ADD COLUMN avatar_url           VARCHAR(500),
    ADD COLUMN email_verified       BOOLEAN NOT NULL DEFAULT FALSE;

-- One CRM account per external identity. Local users leave
-- provider_account_id NULL, and Postgres treats NULLs as distinct, so this
-- constrains only real OAuth identities.
CREATE UNIQUE INDEX uq_users_auth_provider_account
    ON users (auth_provider, provider_account_id);

-- Accounts that already exist were created with a password.
UPDATE users SET email_verified = TRUE WHERE password_hash IS NOT NULL;
