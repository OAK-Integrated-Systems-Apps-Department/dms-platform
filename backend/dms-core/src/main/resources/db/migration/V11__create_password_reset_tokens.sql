-- Self-service "forgot password" flow (dms-core-issued tokens, no
-- redirect through Keycloak's hosted UI - see PasswordResetService).
--
-- Only the SHA-256 hash of the token is stored, never the raw value: the
-- raw token only ever exists in the emailed link and the browser's
-- address bar. A DB read (backup, replica, leaked query log) therefore
-- never yields a usable token, matching how Keycloak itself only ever
-- persists a hash of its own action tokens.
CREATE TABLE password_reset_tokens (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID NOT NULL REFERENCES users (user_id),
    token_hash           VARCHAR(64) NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    used_at              TIMESTAMPTZ,
    requested_ip         VARCHAR(64),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_password_reset_tokens_token_hash UNIQUE (token_hash)
);

-- Looked up on every /reset-password/{validate,confirm} call.
CREATE INDEX ix_password_reset_tokens_token_hash ON password_reset_tokens (token_hash);

-- Used to invalidate a user's other outstanding tokens when a new one is
-- requested, or all of them once one is successfully redeemed.
CREATE INDEX ix_password_reset_tokens_user_id ON password_reset_tokens (user_id);
