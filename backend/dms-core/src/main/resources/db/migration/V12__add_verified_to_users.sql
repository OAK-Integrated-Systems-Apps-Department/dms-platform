-- Tracks whether an invited user has actually finished account setup
-- (verified their email and set a real password), which nothing recorded
-- before now. KeycloakUserService#createUser sends both VERIFY_EMAIL and
-- UPDATE_PASSWORD as required actions on invite, and Keycloak won't issue
-- a valid access token while either is outstanding — so a successful
-- authenticated request from this user IS sufficient proof both are done.
-- See UserServiceImpl#markVerified, called from TenantContextFilter the
-- first time it resolves a not-yet-verified user's JWT. No endpoint lets
-- a caller set this directly.
--
-- Deliberately separate from `status`: an invited-but-still-pending user
-- has status = 'active' (nothing wrong with the account) and
-- verified = false (nobody's actually signed into it yet) at the same
-- time. The User List / View User screens use this to show a "Pending"
-- indicator and disable Reset Password / Deactivate for someone who's
-- never logged in — resetting a password that was never set, or
-- deactivating an account nobody has used yet, aren't meaningful actions.
ALTER TABLE users
    ADD COLUMN verified    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN verified_at TIMESTAMPTZ;

-- Backfill: every user that already exists predates this column entirely,
-- so there's no way to know from stored data alone whether each one has
-- actually logged in. Defaulting them to verified rather than pending
-- avoids retroactively (and almost certainly wrongly, for the typical
-- already-active existing user) disabling Reset Password/Deactivate on
-- accounts that have been in normal use. Any user invited AFTER this
-- migration starts out correctly unverified via the column default above.
UPDATE users SET verified = true WHERE verified = false;