-- Immutable, tamper-evident audit trail. See docs/audit_events_schema_spec.md
-- for the full field-by-field rationale; this migration implements that
-- spec exactly.
--
-- actor_id / impersonated_by are deliberately NOT foreign keys to
-- users.user_id, unlike tenant_id. V14__allow_hard_delete_of_users.sql
-- gave this system a genuine hard-delete path for users
-- (UserServiceImpl#purgeUser); a live FK here would force a choice
-- between blocking that purge forever (RESTRICT) or letting a purge
-- silently null out actor_id (SET NULL), which would collapse "this
-- historical action was really taken by a since-purged human" into the
-- exact same null value the spec reserves for "system-triggered" -
-- reintroducing the ambiguity actor_id's own design note explicitly
-- calls out. actor_display_name already snapshots the human-readable
-- name at event time for the same reason permission_code is a plain
-- snapshot rather than a live FK: this row must stay meaningful and
-- immutable no matter what later happens to the entity it names.
-- tenant_id has no such conflict - tenants are only ever soft-deleted -
-- so it keeps a real FK.
CREATE TABLE audit_events (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID REFERENCES tenants (tenant_id),
    actor_id             UUID,
    actor_type           VARCHAR(20) NOT NULL,
    actor_display_name   VARCHAR(255),
    impersonated_by      UUID,
    action               VARCHAR(100) NOT NULL,
    module               VARCHAR(100) NOT NULL,
    object_type          VARCHAR(100) NOT NULL,
    object_id            UUID,
    resource_name        VARCHAR(255),
    object_version       INT,
    outcome              VARCHAR(20) NOT NULL,
    failure_reason       VARCHAR(500),
    occurred_at          TIMESTAMPTZ NOT NULL,
    previous_value       JSONB,
    new_value            JSONB,
    reason               TEXT,
    session_id           UUID,
    auth_method          VARCHAR(20),
    permission_code      VARCHAR(100),
    data_classification  VARCHAR(50),
    legal_hold_flag      BOOLEAN,
    ip_address           VARCHAR(45),
    user_agent           TEXT,
    client_type          VARCHAR(20),
    correlation_id       UUID,
    request_id           UUID,
    severity             VARCHAR(10),
    record_hash          VARCHAR(64) NOT NULL,
    previous_hash        VARCHAR(64),
    CONSTRAINT ck_audit_events_actor_type CHECK (actor_type IN ('USER', 'SYSTEM', 'API_KEY')),
    CONSTRAINT ck_audit_events_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT ck_audit_events_auth_method CHECK (auth_method IS NULL OR auth_method IN ('PASSWORD', 'MFA', 'SSO')),
    CONSTRAINT ck_audit_events_client_type CHECK (client_type IS NULL OR client_type IN ('WEB', 'MOBILE', 'API', 'ADMIN_CLI')),
    CONSTRAINT ck_audit_events_severity CHECK (severity IS NULL OR severity IN ('LOW', 'MEDIUM', 'HIGH'))
);

-- Nearly every search/filter query is scoped by tenant and a date range
-- (platform-level rows have tenant_id NULL and simply fall outside any
-- tenant-scoped range scan on this index).
CREATE INDEX ix_audit_events_tenant_id_occurred_at ON audit_events (tenant_id, occurred_at);

-- Immutability must not depend solely on application code: no application
-- code path issues UPDATE/DELETE (see AuditEventRepository, which never
-- extends a repository interface exposing either), and this backs that
-- with a DB-level privilege revoke. A correction to a past event is
-- always written as a new compensating event, never an edit to this row.
--
-- Caveat: this REVOKE only constrains a role that actually needs a GRANT
-- to get these privileges in the first place. It has no effect on the
-- table owner or a superuser (e.g. local/dev's default `postgres` role,
-- see application.yml's DB_USERNAME default) - Postgres ownership always
-- implies full privileges regardless of ACL entries, and a superuser
-- bypasses privilege checks entirely. Meaningful enforcement in a real
-- deployment requires the application to connect as a distinct,
-- non-owning, non-superuser role; that role provisioning is an infra
-- concern outside this migration.
REVOKE UPDATE, DELETE ON audit_events FROM PUBLIC;
