-- 3.2 Notification - shared notification service core (future item F2).
--
--   notification_categories         global catalogue: code, module, tier, email default
--   tenant_notification_settings    per-tenant email default override (standard/optional)
--   user_notification_preferences   per-user email on/off (standard/optional)
--   notifications                    the in-app inbox: one row per recipient
--   notification_deliveries          the delivery queue (email / in_app), claimed by the dispatcher
--   notification_delivery_attempts   append-only log of every send attempt (N-NFR-005)
--
-- See docs/notification-plan.md. Points worth knowing:
--   * Tenant isolation follows the house pattern: tenant_id on every table,
--     FK to tenants, and every query scoped by tenant (cross-tenant -> 404).
--     User references use a plain FK to users(user_id) (its PK is global),
--     exactly like workflow_instances.initiated_by - no composite FK needed.
--   * notifications/deliveries carry UNIQUE (tenant_id, <pk>) so child tables
--     can use the composite-FK pattern, like the workflow runtime tables.
--   * object_type/object_id are polymorphic (no FK), like audit_events.
--   * notification_delivery_attempts is append-only. Unlike audit_events /
--     workflow_actions (REVOKE only - which does not bind the table owner),
--     this adds a BEFORE UPDATE/DELETE trigger that RAISEs, so immutability
--     holds even for the owning/superuser role the migration tests run as
--     (plan Deviation #1). REVOKE is kept too, matching the house pattern.
--   * Migration numbers V103–V105 are the next free ones after V102; if develop
--     also consumes V103+, renumber at merge (plan Risk R-1).

-- =====================================================================
-- notification_categories  (global catalogue, seeded here)
-- =====================================================================
CREATE TABLE notification_categories (
    category_code         VARCHAR(80) PRIMARY KEY,
    module                VARCHAR(40) NOT NULL,
    tier                  VARCHAR(20) NOT NULL,
    default_email_enabled BOOLEAN NOT NULL,
    display_name          VARCHAR(150) NOT NULL,
    description           VARCHAR(500),
    is_active             BOOLEAN NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_categories_tier CHECK (tier IN ('critical', 'standard', 'optional')),
    -- A critical category must default to email on (it can never be turned off).
    CONSTRAINT ck_notification_categories_critical_email CHECK (tier <> 'critical' OR default_email_enabled)
);

-- =====================================================================
-- tenant_notification_settings  (per-tenant default for standard/optional)
-- =====================================================================
CREATE TABLE tenant_notification_settings (
    setting_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants (tenant_id),
    category_code         VARCHAR(80) NOT NULL REFERENCES notification_categories (category_code),
    email_enabled_default BOOLEAN NOT NULL,
    created_by            UUID REFERENCES users (user_id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            UUID REFERENCES users (user_id),
    updated_at            TIMESTAMPTZ,
    is_deleted            BOOLEAN NOT NULL DEFAULT false,
    deleted_at            TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_tenant_notification_settings_tenant_category
    ON tenant_notification_settings (tenant_id, category_code) WHERE is_deleted = false;
CREATE INDEX ix_tenant_notification_settings_tenant_id ON tenant_notification_settings (tenant_id);

-- =====================================================================
-- user_notification_preferences  (per-user email on/off for standard/optional)
-- =====================================================================
CREATE TABLE user_notification_preferences (
    preference_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants (tenant_id),
    user_id               UUID NOT NULL REFERENCES users (user_id),
    category_code         VARCHAR(80) NOT NULL REFERENCES notification_categories (category_code),
    channel               VARCHAR(20) NOT NULL DEFAULT 'email',
    enabled               BOOLEAN NOT NULL,
    created_by            UUID REFERENCES users (user_id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            UUID REFERENCES users (user_id),
    updated_at            TIMESTAMPTZ,
    is_deleted            BOOLEAN NOT NULL DEFAULT false,
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT ck_user_notification_preferences_channel CHECK (channel IN ('email'))
);

CREATE UNIQUE INDEX uq_user_notification_preferences_user_category_channel
    ON user_notification_preferences (tenant_id, user_id, category_code, channel) WHERE is_deleted = false;
CREATE INDEX ix_user_notification_preferences_tenant_id ON user_notification_preferences (tenant_id);

-- =====================================================================
-- notifications  (the in-app inbox: one row per recipient)
-- =====================================================================
CREATE TABLE notifications (
    notification_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants (tenant_id),
    recipient_user_id     UUID NOT NULL REFERENCES users (user_id),
    category_code         VARCHAR(80) NOT NULL REFERENCES notification_categories (category_code),
    tier                  VARCHAR(20) NOT NULL,
    title                 VARCHAR(500) NOT NULL,
    body                  VARCHAR(4000),
    link_url              VARCHAR(2000),
    object_type           VARCHAR(60),
    object_id             UUID,
    actor_user_id         UUID REFERENCES users (user_id),
    source_module         VARCHAR(40) NOT NULL,
    dedupe_key            VARCHAR(200) NOT NULL,
    read_at               TIMESTAMPTZ,
    archived_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notifications_tenant_notification UNIQUE (tenant_id, notification_id),
    CONSTRAINT uq_notifications_recipient_dedupe UNIQUE (tenant_id, recipient_user_id, dedupe_key),
    CONSTRAINT ck_notifications_tier CHECK (tier IN ('critical', 'standard', 'optional'))
);

CREATE INDEX ix_notifications_tenant_id ON notifications (tenant_id);
-- Inbox listing, newest first.
CREATE INDEX ix_notifications_recipient ON notifications (tenant_id, recipient_user_id, created_at DESC);
-- Unread count.
CREATE INDEX ix_notifications_unread ON notifications (tenant_id, recipient_user_id)
    WHERE read_at IS NULL AND archived_at IS NULL;

-- =====================================================================
-- notification_deliveries  (the delivery queue)
-- =====================================================================
CREATE TABLE notification_deliveries (
    delivery_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants (tenant_id),
    notification_id       UUID NOT NULL,
    channel               VARCHAR(20) NOT NULL,
    destination           VARCHAR(320),
    status                VARCHAR(20) NOT NULL DEFAULT 'pending',
    skip_reason           VARCHAR(60),
    attempt_count         INT NOT NULL DEFAULT 0,
    max_attempts          INT NOT NULL DEFAULT 5,
    next_attempt_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until          TIMESTAMPTZ,
    last_error            VARCHAR(2000),
    sent_at               TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,
    CONSTRAINT uq_notification_deliveries_tenant_delivery UNIQUE (tenant_id, delivery_id),
    CONSTRAINT fk_notification_deliveries_notification FOREIGN KEY (tenant_id, notification_id)
        REFERENCES notifications (tenant_id, notification_id),
    CONSTRAINT ck_notification_deliveries_channel CHECK (channel IN ('email', 'in_app')),
    CONSTRAINT ck_notification_deliveries_status CHECK (status IN ('pending', 'sending', 'sent', 'failed', 'skipped')),
    CONSTRAINT ck_notification_deliveries_attempts CHECK (attempt_count >= 0 AND max_attempts > 0),
    CONSTRAINT ck_notification_deliveries_skip_reason CHECK (status <> 'skipped' OR skip_reason IS NOT NULL),
    CONSTRAINT ck_notification_deliveries_sent CHECK ((status = 'sent') = (sent_at IS NOT NULL))
);

CREATE INDEX ix_notification_deliveries_tenant_id ON notification_deliveries (tenant_id);
CREATE INDEX ix_notification_deliveries_notification ON notification_deliveries (tenant_id, notification_id);
-- The dispatcher claim scan.
CREATE INDEX ix_notification_deliveries_claim ON notification_deliveries (next_attempt_at)
    WHERE status = 'pending';

-- =====================================================================
-- notification_delivery_attempts  (append-only, N-FR-005 / N-NFR-005)
-- =====================================================================
CREATE TABLE notification_delivery_attempts (
    attempt_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants (tenant_id),
    delivery_id           UUID NOT NULL,
    notification_id       UUID NOT NULL,
    recipient_user_id     UUID NOT NULL REFERENCES users (user_id),
    attempt_number        INT NOT NULL,
    channel               VARCHAR(20) NOT NULL,
    destination           VARCHAR(320),
    outcome               VARCHAR(20) NOT NULL,
    error_code            VARCHAR(80),
    error_message         VARCHAR(2000),
    duration_ms           INT,
    attempted_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_delivery_attempts_delivery FOREIGN KEY (tenant_id, delivery_id)
        REFERENCES notification_deliveries (tenant_id, delivery_id),
    CONSTRAINT ck_notification_delivery_attempts_channel CHECK (channel IN ('email', 'in_app')),
    CONSTRAINT ck_notification_delivery_attempts_outcome CHECK (outcome IN ('sent', 'failed', 'skipped'))
);

CREATE INDEX ix_notification_delivery_attempts_tenant_id ON notification_delivery_attempts (tenant_id);
CREATE INDEX ix_notification_delivery_attempts_delivery ON notification_delivery_attempts (delivery_id, attempt_number);

-- Append-only enforcement: trigger binds even the owner (plan Deviation #1),
-- REVOKE matches the house pattern (audit_events V23, workflow_actions V93).
CREATE FUNCTION notification_attempts_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'notification_delivery_attempts is append-only (% blocked)', TG_OP;
END;
$$;

CREATE TRIGGER trg_notification_attempts_immutable
    BEFORE UPDATE OR DELETE ON notification_delivery_attempts
    FOR EACH ROW EXECUTE FUNCTION notification_attempts_immutable();

REVOKE UPDATE, DELETE ON notification_delivery_attempts FROM PUBLIC;

-- =====================================================================
-- Category seed (global). Granted email defaults match the plan's tier
-- mapping: critical = always on, standard = on, optional = off.
-- =====================================================================
INSERT INTO notification_categories (category_code, module, tier, default_email_enabled, display_name, description)
VALUES
    -- Critical (email always on, cannot be turned off)
    ('security.threat.uploader',       'scanning',   'critical', true,  'Your upload was blocked',           'A threat was detected in a file you uploaded.'),
    ('security.threat.admin',          'scanning',   'critical', true,  'Threat quarantined (admin)',        'A threat was detected and quarantined in your organisation.'),
    ('security.key-rotation.reminder', 'encryption', 'critical', true,  'Encryption key rotation due',       'Your organisation''s encryption key is due for rotation.'),
    ('workflow.task.escalated',        'workflow',   'critical', true,  'Task escalated to you',             'An approval task has been escalated to you.'),
    ('workflow.escalation.failed',     'workflow',   'critical', true,  'Escalation failed',                 'An approval task could not be escalated to a valid target.'),
    ('workflow.stage.unassignable',    'workflow',   'critical', true,  'Workflow stuck: no approver',       'A workflow stage has no eligible approver.'),
    ('workflow.task.delegated',        'workflow',   'critical', true,  'Task delegated or reassigned',      'An approval task was delegated or reassigned to you.'),
    ('workflow.info.requested',        'workflow',   'critical', true,  'Information requested from you',     'An approver has requested information from you.'),
    -- Standard (email on by default, user can turn off)
    ('workflow.task.assigned',         'workflow',   'standard', true,  'Approval needed',                   'A document is waiting for your approval.'),
    ('workflow.task.reminder',         'workflow',   'standard', true,  'Approval reminder',                 'A reminder that an approval task needs your attention.'),
    ('workflow.outcome',               'workflow',   'standard', true,  'Workflow outcome',                  'The outcome of a workflow you submitted.'),
    ('workflow.info.resolved',         'workflow',   'standard', true,  'Information request updated',       'An information request was answered, declined or closed.'),
    ('comment.mention',                'documents',  'standard', true,  'You were mentioned',                'Someone mentioned you in a comment.'),
    ('comment.reply',                  'documents',  'standard', true,  'New reply',                         'Someone replied to your comment.'),
    ('document.shared',                'documents',  'standard', true,  'Document or folder shared',         'A document or folder was shared with you.'),
    ('workflow.new-version',           'workflow',   'standard', true,  'New version mid-approval',          'A new version was uploaded while an approval was in progress.'),
    -- Optional (email off by default, user can turn on)
    ('document.status-changed',        'documents',  'optional', false, 'Document status changed',           'The status changed on a document you own or follow.'),
    ('module.info',                    'general',    'optional', false, 'Informational alert',               'An informational alert from another module.')
ON CONFLICT (category_code) DO NOTHING;
