-- Backs the User List's "Last Login" column, which previously had no data
-- source at all - dms-core never tracked this and nothing called out to
-- Keycloak's separate Events subsystem for it either (see
-- AuthService#login, which only wrote session cookies and logged a line).
--
-- Nullable, no backfill: an existing user who hasn't logged in again
-- since this migration ran has genuinely never had a login recorded
-- under this tracking - NULL ("never logged in") is the correct value
-- for them, not a fabricated timestamp.
ALTER TABLE users
    ADD COLUMN last_login_at TIMESTAMPTZ;
