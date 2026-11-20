-- 5.3 Custom Report Generation (CRG-FR-001 .. 012).
--
-- A REPORT is a point-in-time, tabular extract of the documents the requesting user may see. Everything an
-- organisation can tune is DATA in its own rows, never code, and other organisations are never affected:
--
-- tenant_report_settings   one row per organisation (switches, formats, branding, limits, delivery rules).
--                          No row = the defaults (ReportSettingsService). A NULL integer column means "use the
--                          platform value" (dms.reports.*); a tenant may only go LOWER than the platform ceiling.
-- report_field_settings    per-field admin customisation of the field catalogue. No row = the code default.
--                          field_key is 'doc.<name>' for built-ins and 'meta.<fieldKey>' for custom metadata.
-- report_templates         a named, saved report definition (JSONB + definition_version so old rows are migrated
--                          on read). scope: private (owner), shared (grants), official (admin published; owner NULL
--                          = owned by the organisation, so it survives its author leaving). locked = users may run
--                          it but not edit columns/grouping/format; user_editable_filters lists the filters they may change.
-- report_template_grants   who a shared / official template is visible to (user, role or department).
-- report_schedules         a template run repeatedly. next_run_at is computed with java.time in time_zone.
-- report_schedule_recipients  internal user / role / department, or an external e-mail (only if the tenant allows it).
-- report_runs              one execution producing one file. definition_snapshot is exactly what was executed.
--                          for_user_id = whose access scoped the data (recipient-scoped scheduled runs).
--                          delivery_scope says WHICH delivery of a scheduled slot a run is, so the slot key is unique:
--                            owner      evaluated under the schedule owner's access (also every manual run)
--                            recipient  one internal recipient, evaluated under THEIR access (for_user_id is set)
--                            external   the run sent to external addresses, evaluated under the OWNER's access
--                          Without it the owner's run and the external run of one slot would collide (for_user_id
--                          would be NULL in both). Deviation from the brief's SQL draft - see docs/custom-reports-design.md.
-- report_run_deliveries    per-recipient delivery log. recipient_ref_hash only - never a raw address in a table that
--                          could be exported or audited.

CREATE TABLE tenant_report_settings (
    tenant_id                         UUID PRIMARY KEY REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    enabled                           BOOLEAN      NOT NULL DEFAULT TRUE,
    scheduling_enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    allowed_formats                   TEXT[]       NOT NULL DEFAULT ARRAY['pdf','xlsx','csv'],
    default_format                    VARCHAR(10)  NOT NULL DEFAULT 'xlsx',
    csv_delimiter                     VARCHAR(4)   NOT NULL DEFAULT ',',
    csv_include_bom                   BOOLEAN      NOT NULL DEFAULT TRUE,
    date_format                       VARCHAR(30),
    number_format                     VARCHAR(30),
    default_time_zone                 VARCHAR(60),
    default_locale                    VARCHAR(20),
    header_text                       VARCHAR(200),
    footer_text                       VARCHAR(200),
    confidentiality_notice            VARCHAR(500),
    show_logo                         BOOLEAN      NOT NULL DEFAULT TRUE,
    show_filter_summary               BOOLEAN      NOT NULL DEFAULT TRUE,
    show_generated_by                 BOOLEAN      NOT NULL DEFAULT TRUE,
    pdf_page_size                     VARCHAR(10)  NOT NULL DEFAULT 'A4',
    pdf_orientation                   VARCHAR(10)  NOT NULL DEFAULT 'landscape',
    max_rows                          INTEGER,
    max_pdf_rows                      INTEGER,
    over_limit_behaviour              VARCHAR(20)  NOT NULL DEFAULT 'fail',
    max_concurrent_runs               INTEGER,
    file_retention_days               INTEGER,
    max_templates_per_user            INTEGER,
    max_schedules_per_user            INTEGER,
    max_schedules_per_tenant          INTEGER,
    min_schedule_interval             VARCHAR(10)  NOT NULL DEFAULT 'daily',
    max_recipients_per_schedule       INTEGER,
    allowed_delivery_channels         TEXT[]       NOT NULL DEFAULT ARRAY['in_app','email'],
    delivery_mode_default             VARCHAR(10)  NOT NULL DEFAULT 'link',
    allow_attachments                 BOOLEAN      NOT NULL DEFAULT FALSE,
    max_attachment_mb                 INTEGER,
    allow_external_recipients         BOOLEAN      NOT NULL DEFAULT FALSE,
    external_domain_allowlist         TEXT[]       NOT NULL DEFAULT '{}',
    failure_notify_admins             BOOLEAN      NOT NULL DEFAULT TRUE,
    failure_admin_user_ids            UUID[]       NOT NULL DEFAULT '{}',
    pause_after_consecutive_failures  INTEGER,
    catch_up_window_hours             INTEGER,
    skip_empty_scheduled_reports      BOOLEAN      NOT NULL DEFAULT FALSE,
    include_inactive_by_default       BOOLEAN      NOT NULL DEFAULT FALSE,
    default_columns                   TEXT[]       NOT NULL DEFAULT '{}',
    default_sort                      JSONB,
    updated_by                        UUID,
    updated_at                        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- NULL integer columns mean "use the platform default" (dms.reports.*); CHECKs enforce valid enums.
    CONSTRAINT ck_trs_default_format CHECK (default_format IN ('pdf', 'xlsx', 'csv')),
    CONSTRAINT ck_trs_allowed_formats CHECK (cardinality(allowed_formats) >= 1 AND allowed_formats <@ ARRAY['pdf','xlsx','csv']),
    CONSTRAINT ck_trs_default_in_allowed CHECK (default_format = ANY (allowed_formats)),
    CONSTRAINT ck_trs_csv_delimiter CHECK (csv_delimiter IN (',', ';', E'\t')),
    CONSTRAINT ck_trs_page_size CHECK (pdf_page_size IN ('A4', 'Letter')),
    CONSTRAINT ck_trs_orientation CHECK (pdf_orientation IN ('landscape', 'portrait')),
    CONSTRAINT ck_trs_over_limit CHECK (over_limit_behaviour IN ('fail', 'truncate_and_mark')),
    CONSTRAINT ck_trs_min_interval CHECK (min_schedule_interval IN ('daily', 'hourly')),
    CONSTRAINT ck_trs_delivery_mode CHECK (delivery_mode_default IN ('link', 'attachment')),
    CONSTRAINT ck_trs_channels CHECK (allowed_delivery_channels <@ ARRAY['in_app','email']),
    CONSTRAINT ck_trs_positive CHECK (
        COALESCE(max_rows, 1) > 0 AND COALESCE(max_pdf_rows, 1) > 0 AND COALESCE(max_concurrent_runs, 1) > 0
        AND COALESCE(file_retention_days, 1) > 0 AND COALESCE(max_templates_per_user, 1) > 0
        AND COALESCE(max_schedules_per_user, 1) > 0 AND COALESCE(max_schedules_per_tenant, 1) > 0
        AND COALESCE(max_recipients_per_schedule, 1) > 0 AND COALESCE(max_attachment_mb, 1) > 0
        AND COALESCE(pause_after_consecutive_failures, 1) > 0 AND COALESCE(catch_up_window_hours, 1) > 0)
);

CREATE TABLE report_field_settings (
    tenant_id        UUID         NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    field_key        VARCHAR(120) NOT NULL,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    label_override   VARCHAR(120),
    selectable       BOOLEAN      NOT NULL DEFAULT TRUE,
    filterable       BOOLEAN      NOT NULL DEFAULT TRUE,
    sortable         BOOLEAN      NOT NULL DEFAULT TRUE,
    groupable        BOOLEAN      NOT NULL DEFAULT TRUE,
    default_selected BOOLEAN      NOT NULL DEFAULT FALSE,
    display_order    INTEGER,
    show_code        BOOLEAN      NOT NULL DEFAULT FALSE,   -- choice fields: show the code instead of the label
    updated_by       UUID,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, field_key)
);

CREATE TABLE report_templates (
    template_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID         NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    owner_user_id         UUID         REFERENCES users (user_id),   -- NULL = owned by the tenant (official)
    scope                 VARCHAR(10)  NOT NULL,
    name                  VARCHAR(150) NOT NULL,
    description           VARCHAR(500),
    definition            JSONB        NOT NULL,
    definition_version    INTEGER      NOT NULL DEFAULT 1,
    locked                BOOLEAN      NOT NULL DEFAULT FALSE,       -- official only
    user_editable_filters TEXT[]       NOT NULL DEFAULT '{}',        -- official + locked only
    created_by            UUID,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by            UUID,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,                               -- soft delete
    CONSTRAINT ck_report_templates_scope CHECK (scope IN ('private', 'shared', 'official')),
    CONSTRAINT ck_report_templates_owner CHECK ((scope = 'official') = (owner_user_id IS NULL))
);
-- Unique active names: per owner for private/shared, per tenant for official.
CREATE UNIQUE INDEX ux_report_templates_owner_name
    ON report_templates (tenant_id, owner_user_id, lower(name)) WHERE deleted_at IS NULL AND scope <> 'official';
CREATE UNIQUE INDEX ux_report_templates_official_name
    ON report_templates (tenant_id, lower(name)) WHERE deleted_at IS NULL AND scope = 'official';
CREATE INDEX ix_report_templates_tenant ON report_templates (tenant_id, scope) WHERE deleted_at IS NULL;

CREATE TABLE report_template_grants (
    template_id    UUID        NOT NULL REFERENCES report_templates (template_id) ON DELETE CASCADE,
    tenant_id      UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    principal_type VARCHAR(12) NOT NULL,
    principal_id   UUID        NOT NULL,
    access_level   VARCHAR(5)  NOT NULL DEFAULT 'run',
    PRIMARY KEY (template_id, principal_type, principal_id),
    CONSTRAINT ck_rtg_principal CHECK (principal_type IN ('user', 'role', 'department')),
    CONSTRAINT ck_rtg_level CHECK (access_level IN ('run', 'edit'))
);
CREATE INDEX ix_rtg_principal ON report_template_grants (tenant_id, principal_type, principal_id);

CREATE TABLE report_schedules (
    schedule_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID         NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    template_id          UUID         NOT NULL REFERENCES report_templates (template_id),
    owner_user_id        UUID         NOT NULL REFERENCES users (user_id),
    name                 VARCHAR(150) NOT NULL,
    frequency            VARCHAR(10)  NOT NULL,
    days_of_week         SMALLINT[]   NOT NULL DEFAULT '{}',          -- 1..7 ISO (Monday = 1)
    day_of_month         SMALLINT,                                    -- 1..31, or -1 = last day
    time_of_day          TIME         NOT NULL,
    time_zone            VARCHAR(60)  NOT NULL,                       -- IANA
    start_date           DATE,
    end_date             DATE,
    format               VARCHAR(10)  NOT NULL,
    delivery_channels    TEXT[]       NOT NULL,
    delivery_mode        VARCHAR(10)  NOT NULL DEFAULT 'link',
    status               VARCHAR(15)  NOT NULL DEFAULT 'active',
    pause_reason         VARCHAR(40),
    next_run_at          TIMESTAMPTZ,
    last_run_at          TIMESTAMPTZ,
    consecutive_failures INTEGER      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_report_schedules_frequency CHECK (frequency IN ('daily', 'weekly', 'monthly')),
    CONSTRAINT ck_report_schedules_status CHECK (status IN ('active', 'paused', 'paused_failed', 'cancelled', 'ended')),
    CONSTRAINT ck_report_schedules_format CHECK (format IN ('pdf', 'xlsx', 'csv')),
    CONSTRAINT ck_report_schedules_mode CHECK (delivery_mode IN ('link', 'attachment')),
    CONSTRAINT ck_report_schedules_dom CHECK (day_of_month IS NULL OR day_of_month = -1 OR day_of_month BETWEEN 1 AND 31)
);
CREATE INDEX ix_report_schedules_due ON report_schedules (next_run_at) WHERE status = 'active';
CREATE INDEX ix_report_schedules_tenant ON report_schedules (tenant_id, status);
CREATE INDEX ix_report_schedules_owner ON report_schedules (tenant_id, owner_user_id);
CREATE INDEX ix_report_schedules_template ON report_schedules (template_id) WHERE status IN ('active', 'paused', 'paused_failed');

CREATE TABLE report_schedule_recipients (
    schedule_id    UUID         NOT NULL REFERENCES report_schedules (schedule_id) ON DELETE CASCADE,
    tenant_id      UUID         NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    recipient_type VARCHAR(10)  NOT NULL,
    recipient_ref  VARCHAR(320) NOT NULL,                             -- uuid, or an e-mail address for 'email'
    PRIMARY KEY (schedule_id, recipient_type, recipient_ref),
    CONSTRAINT ck_rsr_type CHECK (recipient_type IN ('user', 'role', 'department', 'email'))
);

CREATE TABLE report_runs (
    run_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    template_id         UUID        REFERENCES report_templates (template_id),   -- NULL = ad hoc
    schedule_id         UUID        REFERENCES report_schedules (schedule_id),
    requested_by        UUID        NOT NULL REFERENCES users (user_id),
    for_user_id         UUID        REFERENCES users (user_id),
    delivery_scope      VARCHAR(10) NOT NULL DEFAULT 'owner',
    trigger             VARCHAR(10) NOT NULL,
    format              VARCHAR(10) NOT NULL,
    status              VARCHAR(10) NOT NULL DEFAULT 'queued',
    definition_snapshot JSONB       NOT NULL,
    scheduled_for       TIMESTAMPTZ,
    row_count           BIGINT,
    truncated           BOOLEAN     NOT NULL DEFAULT FALSE,
    file_object_key     VARCHAR(1000),
    file_size_bytes     BIGINT,
    error_code          VARCHAR(60),
    error_message       VARCHAR(500),                                            -- sanitised, user-safe
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    correlation_id      UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_report_runs_status CHECK (status IN ('queued', 'running', 'completed', 'failed', 'cancelled', 'expired', 'skipped')),
    CONSTRAINT ck_report_runs_trigger CHECK (trigger IN ('manual', 'scheduled', 'retry')),
    CONSTRAINT ck_report_runs_format CHECK (format IN ('pdf', 'xlsx', 'csv')),
    CONSTRAINT ck_report_runs_scope CHECK (delivery_scope IN ('owner', 'recipient', 'external')),
    CONSTRAINT ck_report_runs_recipient CHECK ((delivery_scope = 'recipient') = (for_user_id IS NOT NULL))
);
-- A slot fires once per delivery target: two scheduler instances racing on one slot insert a single row.
CREATE UNIQUE INDEX ux_report_runs_schedule_slot
    ON report_runs (schedule_id, scheduled_for, delivery_scope, for_user_id) NULLS NOT DISTINCT
    WHERE schedule_id IS NOT NULL AND scheduled_for IS NOT NULL;
CREATE INDEX ix_report_runs_user     ON report_runs (tenant_id, requested_by, created_at DESC);
CREATE INDEX ix_report_runs_tenant   ON report_runs (tenant_id, created_at DESC);
CREATE INDEX ix_report_runs_expiry   ON report_runs (expires_at) WHERE status = 'completed';
CREATE INDEX ix_report_runs_active   ON report_runs (tenant_id, status) WHERE status IN ('queued', 'running');
CREATE INDEX ix_report_runs_schedule ON report_runs (schedule_id, created_at DESC) WHERE schedule_id IS NOT NULL;

CREATE TABLE report_run_deliveries (
    delivery_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id             UUID        NOT NULL REFERENCES report_runs (run_id) ON DELETE CASCADE,
    tenant_id          UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    channel            VARCHAR(10) NOT NULL,
    recipient_type     VARCHAR(10) NOT NULL,
    recipient_ref_hash VARCHAR(64) NOT NULL,
    status             VARCHAR(10) NOT NULL DEFAULT 'pending',
    attempts           INTEGER     NOT NULL DEFAULT 0,
    error_code         VARCHAR(60),
    sent_at            TIMESTAMPTZ,
    CONSTRAINT ck_rrd_status CHECK (status IN ('pending', 'sent', 'failed', 'skipped'))
);
CREATE INDEX ix_rrd_run ON report_run_deliveries (run_id);
