-- Module DW (Document Watermarking), Phase 1: a user picks a template or
-- types their own text, and the system produces a watermarked COPY of a
-- document in its native format. The original is never touched.
--
-- Two tables, both additive:
--   watermark_templates - what a watermark says and looks like. Rows with
--     tenant_id NULL are the read-only system templates every tenant sees;
--     a tenant admin manages their own alongside them.
--   watermark_jobs      - one requested copy, produced in the background by
--     the ExportJob pattern (job row -> 202 -> poll -> download).
--
-- The permission codes this feature checks (dw.configure-watermark-policy.*,
-- docm.documents.view, dvc.view-version-history.view) were all seeded in
-- V10 - nothing new is needed here.
--
-- CHECK constraints below are deliberately WIDE: they stop nonsense reaching
-- the column (a negative font size, an opacity of 400) but they are not the
-- product guardrails. The real limits are configurable - dms.watermark.* -
-- and enforced in WatermarkTemplateService/WatermarkService, so tightening
-- them later is a config change rather than a migration.

create table watermark_templates (
    template_id       uuid primary key,
    -- NULL = a system template, seeded below and read-only for everyone.
    -- Same "NULL tenant means platform default" convention as
    -- storage_locations and scan_engines.
    tenant_id         uuid references tenants(tenant_id),
    label             varchar(100) not null,
    default_text      varchar(500) not null,

    -- Style defaults. All nullable on purpose: NULL means "fall through to
    -- the system default" (dms.watermark.*), so a template can set only the
    -- one thing it cares about. Precedence is request > template > system.
    font_size         integer,
    opacity           integer,
    rotation          integer,
    color             varchar(7),
    placement         varchar(20),
    page_scope        varchar(20),

    is_system_default boolean not null default false,
    active            boolean not null default true,
    -- Bumped by JPA @Version on every update and snapshotted into
    -- watermark_jobs.template_version, so a finished job always records the
    -- exact wording it was produced from even after the template changes.
    version           integer not null default 0,

    is_deleted        boolean not null default false,
    deleted_at        timestamptz,
    created_by        uuid,
    updated_by        uuid,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),

    constraint ck_watermark_templates_system_is_platform
        check (is_system_default = (tenant_id is null)),
    constraint ck_watermark_templates_font_size  check (font_size is null or font_size between 1 and 4000),
    constraint ck_watermark_templates_opacity    check (opacity   is null or opacity   between 1 and 100),
    constraint ck_watermark_templates_rotation   check (rotation  is null or rotation  between -360 and 360),
    constraint ck_watermark_templates_color      check (color     is null or color ~ '^#[0-9A-Fa-f]{6}$'),
    constraint ck_watermark_templates_placement
        check (placement is null or placement in ('tiled', 'centered', 'corner', 'header-footer')),
    constraint ck_watermark_templates_page_scope
        check (page_scope is null or page_scope in ('all-pages', 'first-page-only'))
);

-- One label per tenant among the rows that still exist. Postgres treats
-- NULLs as distinct in a unique index, so a plain (tenant_id, label) index
-- would happily allow two system templates both called CONFIDENTIAL -
-- coalesce gives the system scope an id of its own instead.
create unique index ux_watermark_templates_label
    on watermark_templates (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(label))
    where is_deleted = false;

create index ix_watermark_templates_tenant
    on watermark_templates (tenant_id) where is_deleted = false;

comment on table watermark_templates is
    'Module DW: named watermark text and style. tenant_id NULL = read-only system template.';

create table watermark_jobs (
    job_id              uuid primary key,
    tenant_id           uuid not null references tenants(tenant_id),
    requested_by        uuid not null,
    document_id         uuid not null,
    version_id          uuid not null,

    -- Nullable together: a job built from custom text has no template. The
    -- FK is ON DELETE nothing by design - templates are soft-deleted, so a
    -- finished job's provenance survives the template being removed.
    template_id         uuid references watermark_templates(template_id),
    template_version    integer,

    source              varchar(20) not null default 'USER_REQUEST',
    status              varchar(20) not null default 'pending',

    -- Everything the worker needs, resolved in the REQUEST thread: the final
    -- text (placeholders already substituted) and the effective style. The
    -- worker never re-resolves, so what the audit log recorded is exactly
    -- what was drawn, even if the user, the template or the clock moved on.
    request_snapshot    jsonb not null,

    source_mime         varchar(255),
    source_size_bytes   bigint,

    storage_location_id uuid,
    -- {tenantId}/tmp/watermarks/{jobId}/{filename} in the tenant's STAGING
    -- location, exactly like export_jobs.file_object_key. Never the document
    -- location: that one holds promoted, scanned versions and nothing else.
    output_object_key   text,
    output_size_bytes   bigint,
    expires_at          timestamptz,

    failure_code        varchar(60),
    -- Shown to the requester, so it never carries a stack trace or an
    -- internal path - see WatermarkJobWorker.fail.
    failure_reason      varchar(1000),

    correlation_id      uuid,
    idempotency_key     varchar(255),
    -- SHA-256 over the normalised REQUEST (not the resolved snapshot): the
    -- resolved text moves with {date}/{watermarkId}, so hashing it would
    -- make an honest retry look like a different request and 409.
    request_hash        varchar(64),

    created_at          timestamptz not null default now(),
    started_at          timestamptz,
    completed_at        timestamptz,
    updated_at          timestamptz not null default now(),
    purged_at           timestamptz,

    constraint ck_watermark_jobs_status
        check (status in ('pending', 'processing', 'completed', 'failed', 'expired')),
    constraint ck_watermark_jobs_source check (source in ('USER_REQUEST')),
    constraint ck_watermark_jobs_template_version
        check ((template_id is null) = (template_version is null))
);

-- Same Idempotency-Key from the same person in the same tenant is the same
-- job - but only while that job is still worth something to them.
--
-- The index deliberately covers only the LIVE statuses. A key is a promise
-- that an accidental double-click doesn't produce two copies; it is not a
-- promise that the key is spent forever. Once a job has failed, or its
-- output has expired and been deleted, there is nothing left to hand back,
-- so the same key must be free to start a fresh job - otherwise a client
-- that retries with a stable key (which is exactly what a well-behaved
-- client does) could never recover from one failure.
--
-- Partial twice over, so the overwhelming majority of rows - no key, or
-- finished long ago - cost nothing to keep indexed.
create unique index ux_watermark_jobs_idempotency
    on watermark_jobs (tenant_id, requested_by, idempotency_key)
    where idempotency_key is not null
      and status in ('pending', 'processing', 'completed');

-- resumeInterrupted() on startup.
create index ix_watermark_jobs_unfinished
    on watermark_jobs (status) where status in ('pending', 'processing');

-- The expiry sweep (WatermarkOutputSweeper).
create index ix_watermark_jobs_expiry
    on watermark_jobs (expires_at) where purged_at is null and output_object_key is not null;

-- Finding a purged document's leftover outputs.
create index ix_watermark_jobs_document
    on watermark_jobs (document_id) where purged_at is null;

create index ix_watermark_jobs_active_per_user
    on watermark_jobs (tenant_id, requested_by) where status in ('pending', 'processing');

comment on table watermark_jobs is
    'Module DW: one requested watermarked copy. Output lives in staging until expires_at.';

-- The four system templates (tenant_id NULL, read-only). Fixed ids so they
-- are the same row in every environment - a tenant's saved choice of
-- template keeps working across a restore or a rebuild. ON CONFLICT so a
-- re-run is a no-op rather than a failure.
insert into watermark_templates (template_id, tenant_id, label, default_text, is_system_default, active)
values
    ('d0cc0000-0000-4000-8000-000000000001', null, 'CONFIDENTIAL',      'CONFIDENTIAL',      true, true),
    ('d0cc0000-0000-4000-8000-000000000002', null, 'DRAFT',             'DRAFT',             true, true),
    ('d0cc0000-0000-4000-8000-000000000003', null, 'INTERNAL USE ONLY', 'INTERNAL USE ONLY', true, true),
    ('d0cc0000-0000-4000-8000-000000000004', null, 'DO NOT COPY',       'DO NOT COPY',       true, true)
on conflict (template_id) do nothing;
