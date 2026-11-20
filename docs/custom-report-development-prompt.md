# Task: build module 5.3 "Custom Report Generation" (CRG) in the existing DMS, fully configurable per tenant

You are working inside the `dms-platform` repo (Spring Boot backend in `backend/dms-core`, Next.js frontend in `frontend`). Read `CLAUDE.md` first: **never run `git commit`, `git push` or anything that changes git history. Leave all changes uncommitted.**

Build the feature to match this repo's conventions. Study these before writing code and copy their patterns instead of inventing new ones:

- **Async job + file output:** `importexport/export/ExportJobWorker`, `BulkExportService`, `ExportJobService`, `ExportArchiveBuilder`, table `export_jobs` (V41, V48). Reuse the worker pattern (`applicationTaskExecutor`, resume on `ApplicationReadyEvent`), the object-storage path convention, the TTL config style (`dms.export.job-ttl`) and the expiry sweep. Look at the frontend side too: `app/exports/page.tsx`, `lib/export-jobs/api.ts`.
- **Recurring jobs, one server at a time, per tenant:** `compliance/RetentionJob` (Postgres advisory lock, per-tenant loop, `OrganisationStatusGuard`, failure logged and retried next run). Also `NotificationDispatchScheduler` for the on/off switch property pattern.
- **Per-tenant settings:** `compliance/TenantRetentionSettings`, `RetentionSettingsService`, `RetentionController` (settings endpoints and `@authz.hasAnyPermission(...)` expressions), migration `V130`/`V128`, and the frontend `app/admin/retention`, `lib/retention/*`. "No row means the defaults" is the pattern to copy.
- **Visibility (row-level security):** `folder/DocumentAccessService.DocumentAccessView#canSee` / `seesEverything()` / `grantedIds()` / `workflowReadableIds()` and `DocumentService#visibleItems`. Do not re-implement access rules.
- **Notifications:** `notification/service/NotificationService`, `NotificationRequest`, `notification/channel/*`, `NotificationChannel` (`email`, `in_app` only), `NotificationChannelSender`, and migration `V125` (how notification categories are seeded). `common/mail/BrandedMailSender`.
- **Metadata (custom fields):** `metadata/entity/MetadataField`, `DocumentMetadata` (values are a JSON map keyed by `fieldKey`), `DocumentType`, `DocumentTypeSchema`. A tenant's custom fields must be reportable.
- **Workflow:** `workflow/entity/WorkflowInstance` (`status`: `in_progress | approved | rejected | cancelled`), `WorkflowStage`, `WorkflowStageInstance`, `WorkflowTemplate`.
- **Audit:** `AuditActionCatalog`, `docs/audit_events_schema_spec.md`, `AuditEventBuilder`, `AuditValueRedactor`.
- **Permissions:** `security/rbac/catalog/PermissionCodes.java` and how `V10`, `V94`, `V98` seed and grant permissions. The `CRG` module and these codes are **already seeded** (V6, V10) but have no constants in `PermissionCodes.java` yet: `crg.generate-custom-reports.build`, `...run`, `crg.schedule-automated-reports.create-schedule`, `...update-cancel-schedule`, `crg.manage-report-templates.create`, `...update`, `...delete`.
- **Frontend shell:** `components/dashboard/nav-config.ts`, `app/admin/*` pages, `lib/auth/permission-codes.ts`, `lib/bookmarks/*` and `components/bookmarks/*` (most recent feature, shows current style and tests).
- **Dashboards (do not duplicate):** `dashboard/DashboardService` already aggregates counts. Reports are row-level tabular output, not dashboard widgets. Do not modify the dashboard.

## 0. Scope guard: edit other components only when Custom Reports needs it

The goal is the Custom Report Generation feature. You **may edit existing components when the feature requires it** (for example a mail sender that cannot attach files, an index on `documents`, a role-provisioning hook, a cache invalidation in the metadata module, nav and permission registrations). You **must not** use that freedom to fix, refactor, rename, reformat, clean up or "improve" existing code the feature does not depend on, even if you spot bugs or smells.

Rules for every edit outside the reporting code:

1. **Needed by CRG, or don't touch it.** Before editing, be able to say which requirement or decision in this brief forces the change.
2. **Smallest additive change.** Prefer adding a method, overload, bean, column or index over altering existing behaviour. Existing signatures, defaults, outputs and tests must keep working unchanged.
3. **No behaviour change for other features.** Pin, Bookmark, Retention, Dashboard, Export/Import, Notification, Workflow, Metadata, Audit and Security must behave exactly as before. Run their existing tests after your change and report the result.
4. **Tests for the change.** Any edit to an existing component gets a test proving the new capability and a test (or the existing suite) proving the old behaviour is intact.
5. **List them.** The final report has a section "Changes outside Reports": file, what changed, which CRG requirement needed it. Confirm with `git diff --stat` that nothing else changed.
6. **Unrelated problems found along the way** go in a separate section "Noticed but not changed", with file and a one-line description. Do not fix them.

New code lives in the `com.oak.dms.reporting` package, new migrations, new tests, new frontend folders (`lib/reports`, `components/reports`, `app/reports`, `app/admin/reports`) and new docs.

## 1. What this feature is (and is not)

A **report** is a point-in-time, tabular extract of **documents** the requesting user is allowed to see, shaped by filters, columns, sorting and optional grouping, and delivered as PDF, Excel or CSV.

A **report definition** is a JSON document describing one report (source, filters, columns, sort, group-by, options). A **report template** is a named, saved report definition. A **report run** is one execution of a definition producing one file. A **report schedule** runs a template repeatedly and delivers the result.

The **tenant admin** decides what their organisation's report feature can do: which fields are reportable, what the labels are, which formats and delivery options exist, how large and how long-lived reports are, how output looks, and which official templates every user gets. Other tenants are never affected.

Out of scope (do not build): cross-tenant reports, charts or BI, a free-form SQL or expression editor, audit-log reports (module ABDR owns those), reports on non-document sources (users, tasks, storage), SMS/push/SFTP/S3 delivery (keep the delivery interface open for them), report-level comments or approvals.

## 2. Decisions already made (implement these; do not re-litigate)

The source spec (5.3) is thin. These decisions fill its gaps. If something here looks wrong once you have read the code, **stop and ask** rather than deviating.

### 2.1 Data source, filters, columns

| Topic | Decision |
|---|---|
| Data source | v1 has one source: **Documents**. Implement it behind a `ReportDataSource` interface (field catalog, query builder, row streaming) so another source can be added later without touching controllers, jobs or exporters. |
| Always excluded | Templates (`document.template`), recycle-bin documents (`deleted`) and purged tombstones (`purged_at`). Same exclusions as `visibleItems`. There is no option to include them. |
| Archived / inactive | Included only when the definition says `includeInactive` (or filters on `lifecycleState`). Default per tenant setting `includeInactiveByDefault` (default `false`). |
| Built-in fields | Title, document type, department (owner department), owner, status, approval status, workflow stage, created at, created by, modified at, modified by, lifecycle state, folder path, current version number, page count, file size, file type/extension, legal hold flag, retention due date. Confirm each against `Document`, `DocumentVersion` and the retention columns, and drop any that cannot be derived cheaply and correctly (say so in the report back). |
| Custom metadata fields | Every active `MetadataField` of the tenant is offered as `meta.<fieldKey>` (filter, column, sort where the data type allows). A metadata field that is not `searchable` is column-only, not filterable. Honour the field's `dataType` for operators and formatting. |
| Approval status | Derived from the document's **latest** `WorkflowInstance.status`: `in_progress` -> "In approval", `approved` -> "Approved", `rejected` -> "Rejected", `cancelled` -> "Cancelled"; no instance -> "Not submitted". Document that mapping in the design doc. This is a derived field, not `Document.status`; `Document.status` is a separate field called "Document status". |
| Workflow stage | The name of the stage of the latest instance's current stage instance, when that instance is `in_progress`; empty otherwise. Filter by stage name or id (label shown to the user is the name; tolerate the same name on several templates). |
| Filter operators | Per data type: text (`contains`, `equals`, `starts with`, `is empty`), choice/reference (`is any of`, `is none of`, `is empty`), number (`=`, `<>`, `<`, `<=`, `>`, `>=`, `between`), date (`on`, `before`, `after`, `between`, `is empty`, plus relative ranges `today`, `last N days`, `this/last week|month|quarter|year`), boolean (`is true/false`). Escape text with `LikePatterns`. |
| Combining filters | Filters are combined with **AND**. Multiple values inside one filter are **OR**. No nested groups in v1. |
| Relative dates | Resolved at **run time** in the report's time zone, and the resolved absolute range is printed in the report header. A saved template keeps the relative expression. |
| Columns, sort, group | The user picks columns (order kept), up to 3 sort keys, and at most one group-by field. Grouped reports show a group header with a row count and a grand total. No other aggregations in v1. |
| Limits per definition | At most 25 filters, 40 columns, and 5 metadata-field filters (metadata filters hit JSON; keep the cap configurable under `dms.reports.max-metadata-filters`). |
| Documents with no visible type/department | Show empty cells, never drop the row. |

### 2.2 Security and access (CRG-FR-005, CRG-NFR-005)

| Topic | Decision |
|---|---|
| Row scope | Every query runs through the caller's `DocumentAccessView` (`forCaller()` for interactive runs, `forUser(tenantId, userId)` for scheduled runs). Push the access predicate into SQL with the existing pattern (`grantedIds()`, hidden folders, `workflowReadableIds()`); do **not** load all rows and filter in memory. |
| No existence leaks | A report that is narrowed by access shows a notice "Results are limited to documents you can access", **never a count of hidden rows**. Totals, group counts and the preview total only count visible rows. An unknown or invisible template/run id returns the same 404. |
| Permissions | Add constants to `PermissionCodes.java` and the frontend `permission-codes.ts` for the seven seeded codes. `build` = create/edit definitions in the builder; `run` = run a template or definition; `create-schedule`, `update-cancel-schedule`, `manage-report-templates.*` as named. Running an ad-hoc definition needs `build` and `run`. Running a template someone shared with you needs only `run`. |
| New permissions | One migration adds, in the same style as V98: `crg.configure-reports.manage` (tenant admin: settings, field catalog, delivery rules, official templates), `crg.manage-report-templates.publish` (make a template tenant-official), and `crg.view-all-runs.view` (see run **metadata** of everyone in the tenant; never their files). Add the module actions in `permission_actions` and the descriptions the way V18 does. |
| Default grants | The same migration **backfills** the default role grants: roles that already hold `docm` document view get `crg...run`; the tenant-administrator role gets all CRG permissions; all other roles get nothing else. New tenants and roles get the same defaults (check `TenantProvisioningService` and the role seeding code so the grant is not a one-off backfill, and extend them if new tenants or roles would otherwise miss it). |
| Column-level security | A column is only offered, selectable and exported if the caller may see that field. v1 rule: tenant-disabled fields (section 2.4) are hidden from everyone, including admins running reports. There is no per-role column security in v1. |
| Download | Downloading a file re-checks that the caller is the requester (or a permitted recipient) **and** still holds `crg...run` and the tenant feature is enabled. Files are encrypted at rest through the existing storage layer (see `EncryptingObjectStorageService`). Never put a report file or a signed URL in a notification; link to the authenticated download page. |
| CSV/Excel injection | Any text cell beginning with `=`, `+`, `-`, `@`, tab or carriage return is prefixed with a single quote when written to CSV and Excel. Test this. |
| Tenant isolation | Every table has `tenant_id`; every query is scoped by `TenantContext` (or the explicit tenant id in scheduled code); no tenant or user id is accepted from a request body. |
| Suspended organisation | Scheduled runs skip a tenant for which `OrganisationStatusGuard.inactiveReason` is present. |

### 2.3 Running reports, preview, formats

| Topic | Decision |
|---|---|
| Preview | Synchronous. Returns `{ total, sample, limitedToAccess }` where `sample` is at most 50 rows in the chosen sort order. Debounce on the client (400 ms). Preview has its own statement timeout (default 10 s, `dms.reports.preview-timeout`) and returns a clear "preview timed out, narrow your filters" message instead of an error. |
| Generation | Always **asynchronous**: `POST /runs` returns `202 { runId }`, a worker produces the file, the UI polls (or the existing notification center tells the user). States: `queued`, `running`, `completed`, `failed`, `cancelled`, `expired`, `skipped`. Cancel is allowed while `queued` or `running`. A restart resumes or fails stuck `running` runs the way `ExportJobWorker` does. |
| Consistency (CRG-NFR-003) | The query runs in one read-only transaction at `REPEATABLE READ`, so a report cannot mix two states. Every file shows "Generated at <time + zone>", the requester, the resolved filters (human-readable) and the row count. |
| Streaming (CRG-NFR-002) | Never load the full result set in memory. Use JDBC fetch-size streaming and write rows as they arrive: Excel via POI `SXSSFWorkbook`, CSV via `commons-csv`, PDF via PDFBox page by page. Add a statement timeout for generation (`dms.reports.run-timeout`, default 5 min). |
| Row limits | Platform ceilings in `application.yml` under `dms.reports` (`max-rows` default 200000, `max-pdf-rows` default 5000). A tenant may set lower values, never higher. Over the limit: the run **fails fast with `REPORT_ROW_LIMIT_EXCEEDED`** and tells the user how many rows matched and to narrow the filters; it does not silently truncate. Exception: Excel/CSV can be set by the tenant to "truncate and mark" instead, in which case the file header says "Truncated at N rows". |
| Concurrency | Per-tenant concurrent runs (tenant setting, default 3, ceiling `dms.reports.max-concurrent-per-tenant`) and per-user queued runs (default 5). Excess requests get 429 `REPORT_RATE_LIMITED` for interactive runs; scheduled runs wait in the queue. |
| Formats | CSV (UTF-8, delimiter and BOM from tenant settings), Excel `.xlsx` (header row frozen, filters on, column widths, native date and number cells, an info sheet with title, filters, generated-at, row count), PDF (landscape/portrait and page size from settings, repeating header row, page numbers, group headers, footer text). Only formats the tenant has enabled are offered; the API rejects others with 422. |
| Empty result | The run **completes** with `rowCount = 0` and no file is stored; the UI shows "No data found. Try adjusting your filters." and the schedule logic follows the empty-result rule in 2.5. |
| Failure and retry | A failed manual run shows the error with a **Retry** button (creates a new run from the same snapshot). Error messages shown to users are sanitised (no SQL, no stack, no object keys); the full cause goes to logs with the run id. |
| Re-run | Re-running a template always evaluates **current** data and **current** access. The run stores a `definition_snapshot` of exactly what was executed. |
| Run history | Users see their own runs (default 30 days, filterable). Files expire after the tenant's `fileRetentionDays` (default 7, ceiling 90); a sweep marks runs `expired` and deletes the files. Run metadata is kept for the audit-relevant period configured in `dms.reports.run-history-retention` (default 180 days). |

### 2.4 Tenant-admin customisation (the "fully customisable" part)

All settings below live in the tenant's own rows. A tenant with no row gets the defaults. Every setting is validated against the platform ceilings in `dms.reports`. Every change is audited with old and new values (use the redactor for anything sensitive).

**A. Settings (`tenant_report_settings`, one row per tenant)**

| Group | Settings |
|---|---|
| Feature | `enabled` (master switch; off hides the nav item and makes every endpoint return 403 `REPORTS_DISABLED`); `scheduling_enabled`. |
| Formats and output | `allowed_formats` (any subset of pdf, xlsx, csv; at least one), `default_format`, `csv_delimiter` (`,` `;` tab), `csv_include_bom`, `date_format`, `number_format`, `default_time_zone` (IANA; used when a user has none), `default_locale`. |
| Branding | `show_logo` (reuse `BrandingProperties`/tenant logo, no upload in this task), `header_text`, `footer_text`, `confidentiality_notice` (printed on every page of PDF and on the info sheet / first line comment in Excel and CSV when set), `pdf_page_size` (A4/Letter), `pdf_orientation` (default for new reports; user may override), `show_filter_summary`, `show_generated_by`. |
| Limits | `max_rows`, `max_pdf_rows`, `over_limit_behaviour` (`fail` or `truncate_and_mark`), `max_concurrent_runs`, `file_retention_days`, `max_templates_per_user`, `max_schedules_per_user`, `max_schedules_per_tenant`, `min_schedule_interval` (`daily` default; `hourly` allowed only if a platform flag permits), `max_recipients_per_schedule` (default 25). |
| Delivery | `allowed_delivery_channels` (`in_app`, `email`), `delivery_mode_default` (`link` or `attachment`), `allow_attachments`, `max_attachment_mb` (default 10, ceiling 25), `allow_external_recipients` (default **false**), `external_domain_allowlist` (list; empty with external allowed = reject), `failure_notify_admins` and `failure_admin_recipients` (users or role), `pause_after_consecutive_failures` (default 5), `catch_up_window_hours` (default 24), `skip_empty_scheduled_reports` (default false). |
| Defaults for users | `include_inactive_by_default`, `default_columns` (list of field keys), `default_sort`. |

**B. Field catalogue (`report_field_settings`, one row per field the admin has customised)**

For every built-in and metadata field the admin can set: `enabled` (reportable at all), `label_override`, `selectable` (as a column), `filterable`, `sortable`, `groupable`, `default_selected`, `display_order`, and for choice fields whether to show the label or the code. Fields with no row use the code defaults. A field the admin disables disappears from the builder, from templates (the template shows a warning "field X is no longer available" and still runs without it), and from schedules. The catalogue endpoint returns the **effective** catalogue (settings applied, caller's permissions applied).

**C. Official templates**

Users with `crg.manage-report-templates.publish` can publish a template as **tenant-official**: visible to everyone with `run`, or to chosen roles, departments or users. An official template can be **locked** (users can run it, optionally change only the filters the admin marked `userEditable`, and can never edit its columns, grouping or format) or **open** (users can "Save a copy"). Official templates are not deleted when their author leaves (ownership moves to the tenant).

**D. Schedule governance (admin view)**

A tenant admin can list every schedule in the tenant (owner, template, frequency, next run, status, last failure), pause, resume or cancel any of them, and reassign the owner. Pausing a schedule never deletes its history.

**E. Platform ceilings**

`application.yml` gets a `dms.reports` block next to `dms.pins` with the ceilings and defaults named in this document (env names `DMS_REPORTS_*`, add them to `.env.example`). A platform administrator can switch the feature off globally with `dms.reports.enabled`.

### 2.5 Templates and scheduling

| Topic | Decision |
|---|---|
| Template scopes | `private` (owner only), `shared` (owner picks users, roles or departments, each with `run` or `edit`), `official` (admin published, see 2.4C). Names are unique (case-insensitive) per owner for private and shared, and per tenant for official. Deleting is a **soft delete**. A template with active schedules cannot be deleted: 409 `TEMPLATE_HAS_SCHEDULES` with the count; `?cancelSchedules=true` cancels them in the same transaction. |
| Template definition | Stored as JSONB with an integer `definition_version`. The loader migrates old versions on read. Validate on save: every field exists in the catalogue, operators fit the data type, no duplicate columns. |
| Frequency | `daily`, `weekly` (days of week), `monthly` (day of month 1-31, or "last day"; a day that does not exist in a short month runs on the last day), plus time of day. Optional `start_date` and `end_date`. Minimum interval from tenant settings. |
| Time zone | An IANA zone per schedule. Compute `next_run_at` with `java.time.ZonedDateTime`. DST rule: a time that does not exist (spring forward) runs at the next valid instant; a time that happens twice (fall back) runs once, on the first occurrence. Test both. |
| Scheduler | A single `ReportScheduleJob` modelled on `RetentionJob` (advisory lock, short poll interval `dms.reports.schedule-poll` default `PT1M`, on/off property). It selects schedules with `next_run_at <= now()` and `status = 'active'` using `FOR UPDATE SKIP LOCKED`, creates a run with `trigger = scheduled` and a unique `(schedule_id, scheduled_for)` so a double fire is impossible, advances `next_run_at` in the same transaction, and hands the run to the normal worker. |
| Missed runs | After downtime, run **once** (not once per missed slot) if the missed slot is within `catch_up_window_hours`; otherwise skip and record a `skipped` run with reason `MISSED_WINDOW`. |
| Run identity | A scheduled run executes as the **schedule owner**, with the owner's access evaluated **at execution time** (CRG-FR-006). Before running, check in this order: tenant active, feature and scheduling enabled, owner still active, owner still holds `crg...run`, template still exists and is shared with or owned by the owner, every selected field still enabled. Any failure creates a `skipped` run with a specific reason code, notifies the owner (and admins when configured), and after `pause_after_consecutive_failures` failures the schedule becomes `paused_failed`. |
| Recipients | Three kinds: internal user, internal role or department (expanded to active users at execution time), and external email (only if the tenant allows it and the domain is on the allowlist). Maximum from tenant settings. Duplicates are collapsed. |
| Who sees which rows | **Internal recipients receive a report evaluated under their own access** (`forUser(recipient)`), generated once per recipient, so nobody ever receives a row they could not open themselves. A recipient who has lost `crg...run` or is inactive is skipped and listed in the run's delivery log. **External recipients** receive the report evaluated under the **owner's** access, only as an attachment, only if `allow_external_recipients` is on; creating or editing a schedule with external recipients requires `crg.configure-reports.manage`. Say this plainly in the UI where recipients are chosen. |
| Delivery | Channels: `in_app` (notification with a link to the authenticated download page) and `email`. Email default is a **link** (not an attachment). Attachments are only used if the tenant allows them **and** the file is under `max_attachment_mb`; otherwise fall back to a link and record that. `BrandedMailSender` currently attaches only the inline logo, so add file-attachment support to it in an additive, tested way (a new overload; the existing method and every current caller stay unchanged). Do not change `EmailChannelSender` unless the notification flow cannot carry the report link without it. Implement delivery behind `ReportDeliveryChannel` (one class per channel) so SFTP or Teams can be added later. |
| Delivery failure | Retry up to 3 times with exponential backoff (1, 5, 15 minutes). A permanent failure marks that recipient failed, not the whole run. The owner gets one summary notification per run with failures. Add notification categories `REPORT_READY`, `REPORT_FAILED`, `REPORT_SCHEDULE_PAUSED`, seeded like V125, with the user's normal notification preferences honoured for `REPORT_READY` only (failure and pause notices are mandatory). |
| Empty scheduled report | Delivered with "No data found" unless the tenant has `skip_empty_scheduled_reports`, in which case the run is recorded as `completed` with `delivered = false` and reason `EMPTY`. |
| Owner leaves | When the owner is deactivated, their schedules pause with reason `OWNER_INACTIVE` and the admins are notified. The admin can reassign the schedule to another user who then must have access to the template. |
| Run now | The owner (or an admin) can trigger a schedule once, immediately; it does not move `next_run_at`. |

### 2.6 Audit

Add to `AuditActionCatalog` (constants and spec map entries, module `CRG`) and document in `docs/audit_events_schema_spec.md`: `REPORT_RUN_REQUESTED`, `REPORT_GENERATED`, `REPORT_FAILED`, `REPORT_DOWNLOADED`, `REPORT_TEMPLATE_CREATED`, `REPORT_TEMPLATE_UPDATED`, `REPORT_TEMPLATE_DELETED`, `REPORT_TEMPLATE_SHARED`, `REPORT_TEMPLATE_PUBLISHED`, `REPORT_SCHEDULE_CREATED`, `REPORT_SCHEDULE_UPDATED`, `REPORT_SCHEDULE_PAUSED`, `REPORT_SCHEDULE_RESUMED`, `REPORT_SCHEDULE_CANCELLED`, `REPORT_SCHEDULE_RUN_SKIPPED`, `REPORT_DELIVERED`, `REPORT_SETTINGS_UPDATED`, `REPORT_FIELD_SETTINGS_UPDATED`. Severity LOW, except `REPORT_GENERATED`, `REPORT_DOWNLOADED`, `REPORT_DELIVERED` to external recipients and every settings change, which are MEDIUM (reports are a data-exit path). Payloads carry run id, template id, format, row count, filter **field keys and operators** and recipient counts. **Never** put filter values, row content, file names with personal data, or recipient email addresses in the payload (log a hash or the user id). Do not audit previews or idempotent no-ops.

## 3. Requirements to satisfy (amended spec)

Keep the original IDs; the added ones continue the numbering.

Functional
- **CRG-FR-001** Generate custom reports from configurable filter criteria (section 2.1).
- **CRG-FR-002** Filter by document type, department, owner, approval status, creation date, modification date and workflow stage, **and** by any other field the tenant enables, including custom metadata.
- **CRG-FR-003** Save, name, share, duplicate and re-run report configurations as templates.
- **CRG-FR-004** Export to PDF, Excel and CSV, limited to the tenant's allowed formats.
- **CRG-FR-005** Restrict data to what the requesting user is authorised to see, applied in the query, with no existence leaks.
- **CRG-FR-006** Schedule reports (daily, weekly, monthly) with recipients, delivery channel, time zone and failure handling; permission validated at execution time.
- **CRG-FR-007** Choose columns, order, sort and one group-by per report.
- **CRG-FR-008** Tenant admin configures reporting: settings, field catalogue, official and locked templates, schedule governance (section 2.4).
- **CRG-FR-009** Run history with status, download, retry and cancel.
- **CRG-FR-010** Live preview (count plus sample) while building.
- **CRG-FR-011** "No data found", error-with-retry and "results limited to your access" states, as the spec's alternate flows require.
- **CRG-FR-012** Every report states how it was made: generated at (with zone), requester, resolved filters, row count, and the tenant's confidentiality notice when set.

Non-functional
- **CRG-NFR-001** Preview p95 under 3 s for 100k documents; generation of 50k rows to Excel or CSV under 60 s; hard stop at `run-timeout`.
- **CRG-NFR-002** Constant memory per run regardless of row count; no query without `tenant_id`; indexes for every default filter and sort (check existing indexes on `documents` first, add only what is missing, and show the `EXPLAIN` in the design doc for the three slowest filters).
- **CRG-NFR-003** One consistent snapshot per run (section 2.3).
- **CRG-NFR-004** A non-technical user can build and save a report with the builder only: plain-language operator names, a sentence summary of the current filters ("Documents of type Contract, owned by Finance, created in the last 30 days"), field search, sensible defaults, inline validation.
- **CRG-NFR-005** Exports obey the same access rules as the source data, are encrypted at rest, expire, and are downloadable only through an authenticated, re-authorised endpoint.
- **CRG-NFR-006** Accessibility: the builder is fully keyboard operable, every control has a label, status changes use `aria-live`, works on narrow screens, and nothing relies on colour alone.
- **CRG-NFR-007** Observability: Micrometer counters and timers (`reports.run.duration`, `reports.run.rows`, `reports.run.failed{reason}`, `reports.schedule.skipped{reason}`, `reports.queue.depth`), tagged by format but **not** by tenant id.
- **CRG-NFR-008** Safe to upgrade: the template `definition_version` and a loader that tolerates unknown or removed fields.

## 4. Data model

Create the next migration after the current highest. **The latest seen is `V137`**, so start at `V138`, but re-check `db/migration` because this repo has had number collisions after merges. Add a matching file in `db/rollback/` for each. Mirror the style and comments of `V130` and `V128` (a header comment explaining each column that is not obvious). Split into at least three migrations: tables, permission seeding and backfill, notification categories.

```sql
-- tenant settings: one row per tenant; no row = defaults (see ReportSettingsService)
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
    updated_at                        TIMESTAMPTZ  NOT NULL DEFAULT now()
    -- NULL integer columns mean "use the platform default" (dms.reports.*); CHECKs enforce valid enums.
);

-- per-field admin customisation; a missing row = code default
CREATE TABLE report_field_settings (
    tenant_id        UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    field_key        VARCHAR(120) NOT NULL,         -- 'doc.title', 'doc.owner', 'meta.<fieldKey>', ...
    enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    label_override   VARCHAR(120),
    selectable       BOOLEAN     NOT NULL DEFAULT TRUE,
    filterable       BOOLEAN     NOT NULL DEFAULT TRUE,
    sortable         BOOLEAN     NOT NULL DEFAULT TRUE,
    groupable        BOOLEAN     NOT NULL DEFAULT TRUE,
    default_selected BOOLEAN     NOT NULL DEFAULT FALSE,
    display_order    INTEGER,
    show_code        BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_by       UUID,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, field_key)
);

CREATE TABLE report_templates (
    template_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    owner_user_id       UUID        REFERENCES users (user_id),  -- NULL = owned by the tenant (official)
    scope               VARCHAR(10) NOT NULL,                     -- private | shared | official
    name                VARCHAR(150) NOT NULL,
    description         VARCHAR(500),
    definition          JSONB       NOT NULL,
    definition_version  INTEGER     NOT NULL DEFAULT 1,
    locked              BOOLEAN     NOT NULL DEFAULT FALSE,       -- official only
    user_editable_filters TEXT[]    NOT NULL DEFAULT '{}',        -- official + locked only
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT ck_report_templates_scope CHECK (scope IN ('private','shared','official'))
);
-- unique active names: per owner for private/shared, per tenant for official
CREATE UNIQUE INDEX ux_report_templates_owner_name  ON report_templates (tenant_id, owner_user_id, lower(name)) WHERE deleted_at IS NULL AND scope <> 'official';
CREATE UNIQUE INDEX ux_report_templates_official_name ON report_templates (tenant_id, lower(name))               WHERE deleted_at IS NULL AND scope = 'official';

CREATE TABLE report_template_grants (
    template_id     UUID        NOT NULL REFERENCES report_templates (template_id) ON DELETE CASCADE,
    tenant_id       UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    principal_type  VARCHAR(12) NOT NULL,                         -- user | role | department
    principal_id    UUID        NOT NULL,
    access_level    VARCHAR(5)  NOT NULL DEFAULT 'run',           -- run | edit
    PRIMARY KEY (template_id, principal_type, principal_id)
);

CREATE TABLE report_schedules (
    schedule_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    template_id         UUID        NOT NULL REFERENCES report_templates (template_id),
    owner_user_id       UUID        NOT NULL REFERENCES users (user_id),
    name                VARCHAR(150) NOT NULL,
    frequency           VARCHAR(10) NOT NULL,                      -- daily | weekly | monthly
    days_of_week        SMALLINT[]  NOT NULL DEFAULT '{}',         -- 1..7 ISO
    day_of_month        SMALLINT,                                  -- 1..31, or -1 = last day
    time_of_day         TIME        NOT NULL,
    time_zone           VARCHAR(60) NOT NULL,
    start_date          DATE,
    end_date            DATE,
    format              VARCHAR(10) NOT NULL,
    delivery_channels   TEXT[]      NOT NULL,
    delivery_mode       VARCHAR(10) NOT NULL DEFAULT 'link',
    status              VARCHAR(15) NOT NULL DEFAULT 'active',     -- active | paused | paused_failed | cancelled | ended
    pause_reason        VARCHAR(40),
    next_run_at         TIMESTAMPTZ,
    last_run_at         TIMESTAMPTZ,
    consecutive_failures INTEGER    NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_report_schedules_due ON report_schedules (next_run_at) WHERE status = 'active';

CREATE TABLE report_schedule_recipients (
    schedule_id     UUID        NOT NULL REFERENCES report_schedules (schedule_id) ON DELETE CASCADE,
    tenant_id       UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    recipient_type  VARCHAR(10) NOT NULL,                          -- user | role | department | email
    recipient_ref   VARCHAR(320) NOT NULL,                         -- uuid or email address
    PRIMARY KEY (schedule_id, recipient_type, recipient_ref)
);

CREATE TABLE report_runs (
    run_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    template_id         UUID        REFERENCES report_templates (template_id),   -- NULL = ad hoc
    schedule_id         UUID        REFERENCES report_schedules (schedule_id),
    requested_by        UUID        NOT NULL REFERENCES users (user_id),
    for_user_id         UUID        REFERENCES users (user_id),                  -- whose access scoped the data (recipient-scoped runs)
    trigger             VARCHAR(10) NOT NULL,                                    -- manual | scheduled | retry
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
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_report_runs_schedule_slot ON report_runs (schedule_id, scheduled_for, for_user_id) NULLS NOT DISTINCT WHERE schedule_id IS NOT NULL;
CREATE INDEX ix_report_runs_user    ON report_runs (tenant_id, requested_by, created_at DESC);
CREATE INDEX ix_report_runs_expiry  ON report_runs (expires_at) WHERE status = 'completed';

CREATE TABLE report_run_deliveries (
    delivery_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id        UUID        NOT NULL REFERENCES report_runs (run_id) ON DELETE CASCADE,
    tenant_id     UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    channel       VARCHAR(10) NOT NULL,
    recipient_type VARCHAR(10) NOT NULL,
    recipient_ref_hash VARCHAR(64) NOT NULL,       -- never the raw address in a table that is exported or audited
    status        VARCHAR(10) NOT NULL,            -- pending | sent | failed | skipped
    attempts      INTEGER     NOT NULL DEFAULT 0,
    error_code    VARCHAR(60),
    sent_at       TIMESTAMPTZ
);
```

Treat the SQL above as a strong draft, not scripture. Where it conflicts with how the repo actually does things (for example the `tenants` or `users` key names, whether `documents` has a `(tenant_id, document_id)` key, `NULLS NOT DISTINCT` support in the Postgres version in `infra/local`), follow the repo and note the change. Check the existing `documents` and `document_metadata` indexes first; likely candidates are `(tenant_id, created_at)`, `(tenant_id, updated_at)`, `(tenant_id, document_type_id)`, `(tenant_id, owner_department_id)`, `(tenant_id, owner_user_id)`, `(tenant_id, lifecycle_state)`, and a GIN index on the metadata JSON only if measurements justify it. You may add indexes on existing tables (`documents`, `document_metadata`, workflow tables) **only where `EXPLAIN` shows a default report filter or sort needs one**. Add them in a separate migration with a rollback, show the before/after `EXPLAIN` in the design doc, and keep them narrow (tenant-first composite, partial where possible).

## 5. Backend

Package `com.oak.dms.reporting` (the empty package already exists). Suggested structure, adapted to repo conventions:

```
reporting/
  controller/   ReportController, ReportTemplateController, ReportScheduleController, ReportAdminController
  dto/          definition, catalog, preview, run, template, schedule and settings DTOs
  entity/       one per table above
  repository/   one per entity
  catalog/      ReportFieldCatalog, FieldDescriptor, FieldType, OperatorSet
  datasource/   ReportDataSource, DocumentReportDataSource, DocumentReportQueryBuilder (access predicate lives here)
  export/       ReportExporter (interface), CsvReportExporter, ExcelReportExporter, PdfReportExporter, CellSanitizer
  run/          ReportRunService, ReportRunWorker, ReportRunSweeper (expiry), ReportRunStateMachine
  schedule/     ReportScheduleService, ReportScheduleJob, NextRunCalculator
  delivery/     ReportDeliveryChannel, InAppReportDelivery, EmailReportDelivery, ReportDeliveryService
  settings/     ReportSettingsService, ReportSettingsDefaults (merges tenant row, platform ceilings and code defaults)
  ReportProperties.java   (@ConfigurationProperties("dms.reports"))
```

Endpoints under `/api/v1/reports`. Use `@PreAuthorize` with `@authz.hasAnyPermission(...)` as `RetentionController` does, `@Operation` descriptions in the style of `PinController`, and `FolderException`/`ApiException` codes the way the pins do.

| Method | Path | Notes |
|---|---|---|
| GET | `/catalog` | Effective field catalogue for the caller: key, label, type, operators, choices (for choice and reference fields, paged search endpoint `GET /catalog/fields/{key}/values?q=` so a 10,000-user tenant is not sent in one response), flags. Also the effective settings the UI needs (formats, limits, defaults, delivery options). Needs `build` or `run`. |
| POST | `/preview` | Body: definition. Returns `{ total, sample, limitedToAccess, resolvedFilters }`. Needs `build`. |
| POST | `/runs` | Body: `{ templateId? , definition?, format, overrides? }`. 202 `{ runId }`. Exactly one of `templateId`/`definition`. Needs `run` (+ `build` for ad hoc). |
| GET | `/runs` | Caller's own runs, paged, filter by status, template, date. |
| GET | `/runs/{id}` | Status, progress, row count, error. 404 for someone else's run. |
| GET | `/runs/{id}/download` | Streams the file; re-checks access, permission and expiry; audits `REPORT_DOWNLOADED`. `Content-Disposition` filename built from the template name with unsafe characters removed. |
| POST | `/runs/{id}/cancel`, `/runs/{id}/retry` | As described in 2.3. |
| GET / POST | `/templates` | List what the caller may see or run (own, shared with them, official); create. |
| GET / PUT / DELETE | `/templates/{id}` | Read, update (respect `locked` and grants), soft delete. |
| POST | `/templates/{id}/duplicate`, `/templates/{id}/share`, `/templates/{id}/publish`, `/templates/{id}/unpublish` | As described in 2.4C and 2.5. |
| GET / POST | `/schedules` | Own schedules; create (validates everything in 2.5). |
| GET / PUT / DELETE | `/schedules/{id}` | DELETE = cancel. |
| POST | `/schedules/{id}/pause`, `/resume`, `/run-now` | |
| GET | `/schedules/{id}/runs` | History with per-run delivery summary. |
| GET / PUT | `/admin/settings` | Tenant settings; PUT validates against ceilings and returns the effective values. Needs `crg.configure-reports.manage`. |
| GET / PUT | `/admin/fields` | Whole effective catalogue with admin overrides; PUT is a full replacement of overrides in one transaction. |
| GET | `/admin/schedules`, `/admin/runs` | Tenant-wide, metadata only. `/admin/runs` needs `crg.view-all-runs.view`. |
| POST | `/admin/schedules/{id}/pause`, `/resume`, `/cancel`, `/reassign` | |

Implementation notes that are easy to get wrong:

- **One query builder, one access predicate.** `DocumentReportQueryBuilder` is the only place that builds the SQL for documents. It takes `(tenantId, DocumentAccessView, definition, effectiveCatalogue)` and returns a parameterised query. No string concatenation of user input; field keys are resolved through the catalogue to known column expressions, never interpolated from the request.
- **Preview and generation share that builder**, so what the user previews is what they get.
- **Settings resolution** is `min(tenantValue, platformCeiling)` with a code default for `null`. Centralise it in `ReportSettingsService` and never read `ReportProperties` directly from a controller or exporter.
- **Catalogue resolution** merges: code-defined built-ins, tenant `MetadataField`s, tenant `report_field_settings`, and the caller's permissions. Cache the tenant part briefly (see `DashboardCache` and the metadata cache for the invalidation pattern) and invalidate it when metadata fields or field settings change. If the metadata module has no suitable event or hook, add a minimal additive one.
- **Run state machine:** only legal transitions, enforced in one class, with a test per illegal one.
- **Worker:** model on `ExportJobWorker`. Each run sets `TenantContext` explicitly for the duration of the job and clears it in `finally`.
- **Per-recipient generation:** group recipients by their access fingerprint where that is cheap and obviously correct (for example, two users with identical `DocumentAccessView` content); otherwise generate separately. If you add that optimisation, add a test that proves two users with different access never share a file.
- **Time zones:** store instants as `TIMESTAMPTZ`, format with the report's zone, and print the zone name in the header.
- **Excel:** use `SXSSFWorkbook` and call `dispose()`. **PDF:** page-by-page with PDFBox, fonts embedded so non-Latin names print; test with Arabic, Sinhala/Tamil and CJK sample values and fall back to a clear placeholder rather than failing the run if a glyph is missing. **CSV:** `commons-csv`, `CellSanitizer` applied to every text cell.
- **Temporary files:** use `common/io/TempFiles`; delete in `finally`.

## 6. Frontend

Mirror `lib/retention/*`, `lib/bookmarks/*`, `lib/export-jobs/*` and the `app/admin/*` pages. Use React Query, the existing `api-fetch` and error-toast helpers, and the existing form and table components.

User-facing (`app/reports/...`, nav label **"Reports"**):

- `app/reports/page.tsx` with three tabs: **My reports** (templates I own or that were shared or published to me, with Run / Edit / Duplicate / Schedule / Share), **Run history** (status, format, rows, created, expires, download, retry, cancel), **Schedules** (status, next run in the schedule's time zone, last result, pause/resume/run now).
- `app/reports/builder/page.tsx` (new) and `app/reports/builder/[templateId]/page.tsx` (edit): a guided builder in four clear steps shown as one scrollable page with a sticky summary and preview panel on the right (stacked on narrow screens): 1 **What to include** (filters; field picker with search, grouped by category, plain-language operators, relative date presets, "include archived" switch), 2 **What to show** (columns with drag and keyboard reordering, sort, group-by), 3 **Format and layout** (formats allowed by the tenant; PDF orientation and page size; title), 4 **Save and schedule**. The preview panel shows the sentence summary, the total, the first 50 rows, the "limited to your access" notice, and clear loading, empty ("No data found...") and error states with retry. Locked official templates open in a reduced "Run with options" view.
- Schedule dialog: frequency, days, time, time zone (default to the user's browser zone, then tenant default), start and end date, format, channels, link vs attachment (only if allowed), recipient picker (people, roles, departments, and external addresses only if the tenant allows them, with the visible explanation from 2.5 about whose access applies), and failure handling summary showing the tenant's rules.
- Run progress: toast with link on start; the run row updates by polling `GET /runs/{id}` with back-off; completion also arrives through the normal notification. Respect `prefers-reduced-motion`.
- Sidebar: in `components/dashboard/nav-config.ts` add **"Reports"** with a lucide icon (e.g. `FileBarChart`, import it with the existing icons), `href: "/reports"`, `permission: [PermissionCodes.CRG_RUN, PermissionCodes.CRG_BUILD]`, placed where it reads naturally next to the Documents/monitoring entries (read the file and choose; tell me where you put it). Also hide it when the tenant setting `enabled` is false (use the catalogue/settings endpoint through the same feature-flag mechanism the file already uses for `feature`). Do not add the link until `app/reports/page.tsx` exists.

Tenant admin (`app/admin/reports/page.tsx`, label **"Reports"** under Administration > Policies next to "Records Retention", `permission: [PermissionCodes.CRG_CONFIGURE_REPORTS]`) with tabs:

1. **General**: enable reporting, enable scheduling, default time zone, locale, date and number formats, include-archived default.
2. **Fields**: a table of every built-in and custom field with switches (reportable, column, filter, sort, group, default column), label override, and drag to reorder. A search box, a "Reset to default" action, and a visible warning when disabling a field that official templates or schedules use (the backend returns the usage counts).
3. **Official templates**: list, publish and unpublish, lock and unlock, choose which filters users may change, audience picker. Link into the builder in admin mode.
4. **Schedules & delivery**: allowed channels, link or attachment, attachment size, external recipients and the domain allowlist, recipient limits, minimum interval, failure rules, admin failure recipients; and the tenant-wide schedule list with pause, resume, cancel, reassign.
5. **Output & branding**: allowed formats, default format, CSV delimiter and BOM, header, footer, confidentiality notice, PDF defaults, and a **live sample** (generate a one-page PDF/CSV sample with 5 fake rows through a `POST /admin/settings/sample` endpoint that uses no tenant data).
6. **Limits**: rows, PDF rows, concurrency, retention days, template and schedule counts, each showing the platform ceiling next to the input.

Admin screens save per tab, show what changed, and display validation errors from the backend inline. Add `CRG_*` constants to `lib/auth/permission-codes.ts` to match the backend. Hide controls the user has no permission for; never rely on hiding alone (the backend enforces everything).

Frontend files: `lib/reports/api.ts`, `lib/reports/use-reports.ts`, `lib/reports/definition.ts` (types, validation, the sentence summariser), `lib/reports/messages.ts` (error-code to message map, like `lib/retention/messages.ts`), `components/reports/*`.

## 7. Tests

Backend unit
- `DocumentReportQueryBuilderTest`: every operator on every data type, relative date resolution in different zones, parameterisation (no literal user input in SQL), the 5 metadata-filter cap, disabled fields rejected.
- `ReportSettingsServiceTest`: ceilings, nulls, enum validation, defaults for a tenant with no row.
- `NextRunCalculatorTest`: daily, weekly, monthly incl. 31st in short months and "last day", both DST edges (spring forward gap, fall back overlap) for at least two zones, `start_date`/`end_date`, end of schedule.
- `ReportRunStateMachineTest`, `CellSanitizerTest` (formula injection), exporter tests that read the generated CSV, XLSX and PDF back and check headers, native cell types, info sheet, confidentiality notice and group headers.
- `ReportScheduleJobTest`: skip reasons for each failed pre-check in 2.5, auto-pause threshold, catch-up window.

Backend integration (`ReportIT`, `ReportScheduleIT`, like `PinAudienceIT`)
- **Access:** a user who cannot see a restricted folder or document never gets its rows in preview, run, group counts or totals; no count of hidden rows appears anywhere; workflow-readable documents appear only while the approval is running.
- **Cross-user:** user B cannot read, download, cancel or retry user A's run, nor see a private template (same 404 as missing). Shared and official visibility rules.
- **Tenant isolation:** tenant A's settings, catalogue, templates, schedules and files are invisible to tenant B; tenant A's admin settings never affect tenant B.
- **Settings effects:** disabling a field, a format, the whole feature, scheduling and external recipients each takes effect on the next request; ceilings reject excess; `over_limit_behaviour` both ways.
- **Scheduled revalidation:** owner loses `crg...run` between creation and execution -> skipped with reason; owner deactivated -> paused; a recipient loses access -> that recipient is skipped and the others still receive; external recipient is evaluated with owner's scope and requires the admin permission.
- **Idempotency:** two scheduler instances firing the same slot produce exactly one run (unique slot) and the `SKIP LOCKED` path is exercised.
- **Consistency and scale:** a 100k-document fixture streams to CSV and XLSX with bounded heap (assert via a small `-Xmx` or a memory check in the test), and a document changed during generation does not create an inconsistent file.
- **Lifecycle:** recycle-bin documents and templates excluded, archived controlled by the flag, file expiry sweep deletes the file and marks the run `expired`, downloads after expiry return 410 `REPORT_EXPIRED`.
- **Audit:** every action in 2.6 emits exactly one event, with no filter values, rows or addresses in any payload.
- **Migration:** permission backfill (roles with document view get `run`; tenant administrator gets all) and the rollback files work on a database that already has V137.

Frontend tests in the style of the existing `*.test.tsx` files
- Builder: filter summary sentence, field picker search, operator sets by type, validation messages, relative dates, disabled formats not shown, preview empty, error and "limited to your access" states.
- Run flow: progress polling, success with download, failure with retry, over-limit message, cancel.
- Schedule dialog: time zone default, external recipients hidden when not allowed, attachment option hidden when not allowed, validation.
- Admin: each tab saves and shows backend validation errors; disabling a used field shows the usage warning; ceilings are displayed.
- Keyboard and aria: builder column reorder by keyboard, `aria-live` status announcements.

## 8. Docs and wrap-up

- Add `docs/custom-reports-design.md` (short: decisions table, API, data model, run and schedule state diagrams, settings reference with defaults and ceilings, the approval-status mapping, access rules, and the `EXPLAIN` results). Add `docs/custom-reports-admin-guide.md` written for a tenant admin in plain language, and `docs/custom-reports-user-guide.md` for report users.
- Update `docs/requirement-registry.md` section 5.3: replace the thin text with the amended requirements in section 3 and add the decisions that change behaviour (recipient-scoped delivery, execution-time checks, tenant-admin customisation). Keep the section name **"Custom Report Generation"** everywhere and the UI name **"Reports"**.
- Document the audit events in `docs/audit_events_schema_spec.md` (and add a short `docs/events/report-audit-events.md` like the other event docs).
- Add the `dms.reports` block to `application.yml` and `application-docker.yml` where they hold similar blocks, the `DMS_REPORTS_*` variables to `.env.example` and `docs/deployment.md`, and regenerate the OpenAPI doc and client the way `scripts/generate-openapi-client.sh` expects.
- Run the backend unit and integration tests and the frontend lint and tests. Fix what you break. Report exactly what you ran and the results.
- Do not commit. Summarise changed files at the end, list anything in this brief you could not satisfy and why, list every place you deviated from the SQL draft, list the fields from section 2.1 you dropped, include the "Changes outside Reports" and "Noticed but not changed" sections from section 0, and confirm with `git status`/`git diff --stat` that every edit to an existing file is listed there.

Work in this order: migrations and rollbacks, `PermissionCodes` and permission seeding, settings service and properties, field catalogue, query builder with access predicate (with its tests first), preview endpoint, exporters, run service and worker, run endpoints, templates and sharing, scheduler and delivery, admin endpoints, audit entries, backend integration tests, frontend API and hooks, builder, run history and templates pages, schedule dialog, admin screens, nav, frontend tests, docs. **Ask before deviating from section 2.**
