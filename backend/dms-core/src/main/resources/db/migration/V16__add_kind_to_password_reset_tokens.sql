-- Distinguishes a brand-new-user "set your password to activate your
-- account" token from an ordinary "forgot/reset your password" token
-- (both self-service and admin-triggered resets share the latter - end
-- users experience them identically). Previously nothing on the row (or
-- anywhere else) recorded which of PasswordResetService's three issuance
-- paths (requestReset/issueForAdminReset/issueForNewUser) created a given
-- token - only the one-time email subject/body differed, via the
-- in-memory-only EmailKind enum, discarded once the email was sent. That
-- made it impossible for /update-password to show different copy for
-- "welcome, set your password" vs "reset your password" once the link
-- was actually clicked.
--
-- Defaulted to 'PASSWORD_RESET' for the ADD COLUMN itself so any
-- already-issued, still-outstanding token (necessarily short-lived, per
-- app.password-reset.token-ttl-minutes) resolves to the more generic of
-- the two copies rather than failing to parse - every token issued after
-- this migration always sets it explicitly (see
-- PasswordResetService#issueTokenAndSendEmail).
ALTER TABLE password_reset_tokens
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'PASSWORD_RESET';

ALTER TABLE password_reset_tokens
    ADD CONSTRAINT ck_password_reset_tokens_kind CHECK (kind IN ('NEW_USER', 'PASSWORD_RESET'));
