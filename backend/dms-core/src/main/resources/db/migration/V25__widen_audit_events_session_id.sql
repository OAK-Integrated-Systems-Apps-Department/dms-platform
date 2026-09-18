-- audit_events.session_id was originally typed UUID, on the assumption
-- that Keycloak's JWT "sid"/"session_state" claim is UUID-shaped. It never
-- is - Keycloak generates these as arbitrary opaque strings (e.g.
-- "NhFcDsECmgpa7Nk0VILq29SY"), so every UUID.fromString() attempt in
-- AuditRequestContextFilter#resolveSessionId threw, was swallowed, and
-- left this column NULL on every single row written so far - safe to
-- widen with no data to convert.
ALTER TABLE audit_events ALTER COLUMN session_id TYPE VARCHAR(255);
