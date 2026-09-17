-- Seeds a fixed, well-known "system" actor so audit_events.actor_id never
-- has to be NULL for a system-triggered event (see the actor_id note in
-- docs/audit_events_schema_spec.md: NULL can't distinguish "the system did
-- this" from "actor data went missing due to a bug"). SystemActor in
-- application code is the single source of truth for these two ids -
-- nothing here should ever be re-typed elsewhere.
--
-- Needs its own tenant rather than borrowing an existing one, and does NOT
-- relax users.tenant_id to nullable: that column is NOT NULL for every
-- other row in this schema, and column-wide relaxation for one row would
-- be a bigger, riskier change than adding one dedicated platform-level
-- tenant that this single system user belongs to.
--
-- keycloak_id is a required, unique column on users, but this account
-- never authenticates through Keycloak - the value below is a fixed
-- placeholder, not a real Keycloak subject id.
--
-- Follow-up (not done here): User List / Tenant List screens query users
-- and tenants without excluding this row yet. It must be filtered out of
-- any admin-facing list/search before those screens are exposed to real
-- users, or it will show up as an apparently-manageable account.
INSERT INTO tenants (tenant_id, tenant_name, domain, subscription_tier, status, is_deleted)
VALUES ('00000000-0000-0000-0000-000000000000', 'Platform (System)', 'system.internal.oakdms', 'internal', 'active', false);

INSERT INTO users (
    user_id, tenant_id, keycloak_id, email, username,
    first_name, last_name, display_name,
    mfa_enabled, status, is_deleted, verified
)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000000',
    '00000000-0000-0000-0000-0000000000fe',
    'system@system.internal.oakdms',
    'system',
    'System', NULL, 'System',
    false, 'active', false, true
);
