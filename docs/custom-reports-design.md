# Custom Report Generation (5.3) - design

UI name: **Reports**. Code: `com.oak.dms.reporting` (backend), `app/reports`, `app/admin/reports`, `components/reports`, `lib/reports` (frontend). Migrations V138-V142 (rollbacks in `db/rollback`).

## 1. Decisions

| Topic | Decision |
|---|---|
| Data source | Documents only, one tenant. Behind a `ReportDataSource` interface so other sources can be added later. |
| Where SQL is built | Only `DocumentReportQueryBuilder`. Field keys and operators are checked against the catalogue; values are always bound parameters. There is no free-form SQL or expression. |
| Access | The access predicate is part of the query (folder and document access, restricted folders, legal-hold and lifecycle rules, workflow-readable documents only while the approval runs). Hidden rows never appear in rows, counts, group totals or "no data" states, so nothing about them leaks. |
| Consistency | Every run reads inside one `ReadOnlySnapshot` (read-only, `REPEATABLE READ`, streamed), so a file never mixes two states of the data. |
| Memory | Rows are streamed from JDBC into the exporter (CSV `commons-csv`, Excel POI `SXSSF`, PDF PDFBox page by page). `ReportExportScaleTest` checks 100k CSV/XLSX rows and 5k PDF rows under `-Xmx192m`. |
| Runs | Asynchronous: a `report_runs` row, then `ReportRunWorker`. A sweeper fails runs whose worker died. Download is an authenticated, re-authorised endpoint; files are stored encrypted, expire, and are removed by retention. |
| Schedules | Run as the **schedule owner**, with access checked **at execution time**. One server at a time runs the scheduler (Postgres advisory lock). One run per schedule slot (`ux_report_runs_schedule_slot`). |
| Delivery | Recipient-scoped: each recipient gets only what their own access allows (in-app link, or email link / attachment). External addresses only when the organisation allows them and the setting is on. The delivery interface (`ReportDeliveryChannel`) is open for further channels. |
| Customisation | The tenant administrator sets limits (never above the platform ceilings), the field catalogue (enable, rename, hide), output and branding, and official / locked templates. |
| Upgrades | Templates store `definition_version`; the loader tolerates removed or unknown fields and reports them as warnings. |

## 2. API (`/api/v1/reports`)

| Area | Endpoints |
|---|---|
| Catalogue | `GET /catalog`, `GET /catalog/fields/{key}/values`, `POST /preview` |
| Templates | `GET/POST /templates`, `GET/PUT/DELETE /templates/{id}`, `POST .../duplicate`, `.../share`, `.../publish`, `.../unpublish` |
| Runs | `POST /runs`, `GET /runs`, `GET /runs/{id}`, `GET .../download`, `POST .../cancel`, `POST .../retry` |
| Schedules | `GET/POST /schedules`, `GET/PUT/DELETE /schedules/{id}`, `POST .../pause`, `.../resume`, `.../run-now`, `GET .../runs` |
| Admin (`/admin`) | `GET/PUT /settings`, `POST /settings/sample?format=pdf\|csv`, `GET/PUT /fields`, `GET /schedules`, `POST /schedules/{id}/pause\|resume\|cancel\|reassign`, `GET /runs` |

`POST /admin/settings/sample` makes a one-page sample from five invented rows under the *unsaved* settings (validated like a save, stored nowhere, no tenant data read).

### Permissions

`crg.generate-custom-reports.build` / `.run`; `crg.schedule-automated-reports.create-schedule` / `.update-cancel-schedule`; `crg.manage-report-templates.create` / `.update` / `.delete` / `.publish`; `crg.configure-reports.manage`; `crg.view-all-runs.view` (metadata only, never files).

## 3. Data model

`tenant_report_settings` (one row per tenant) · `report_field_settings` (per-field overrides) · `report_templates` + `report_template_grants` · `report_schedules` + `report_schedule_recipients` · `report_runs` · `report_run_deliveries`. Every table has `tenant_id`; indexes are in V138 and V141-V142.

## 4. State diagrams

Run (`report_runs.status`):

```
queued -> running -> completed -> expired
   |         |   \-> failed
   |         \-----> cancelled
   \-> cancelled
(scheduled run that could not start) -> skipped
```

`failed`, `cancelled` and `skipped` runs can be retried by the requester where the state allows it (`REPORT_RUN_NOT_RETRYABLE` otherwise).

Schedule (`report_schedules.status`):

```
active <-> paused            (user or admin)
active  -> paused_failed     (after N consecutive failures; resume on purpose)
paused_failed -> active      (resume, which resets the counter)
any     -> cancelled         (user, admin, or the template was deleted)
active  -> ended             (end date passed)
```

Delivery (`report_run_deliveries.status`): `pending -> sent | failed | skipped`; failed email deliveries are retried after 1, 5 and 15 minutes.

### Checks before a scheduled run (in this order)

Tenant active, reports and scheduling enabled, owner active, owner still holds the run permission, template still available to the owner, every selected field still enabled, format and channel still allowed. A failure makes a `skipped` run with one of: `TENANT_INACTIVE`, `REPORTS_DISABLED`, `SCHEDULING_DISABLED`, `OWNER_INACTIVE`, `OWNER_PERMISSION_LOST`, `TEMPLATE_UNAVAILABLE`, `FIELDS_UNAVAILABLE`, `FORMAT_NOT_ALLOWED`, `CHANNEL_NOT_ALLOWED`, `NO_RECIPIENTS`, `MISSED_WINDOW`. It notifies the owner (and the configured failure administrators), and after `pauseAfterConsecutiveFailures` the schedule becomes `paused_failed`.

## 5. Approval status mapping

"Approval status" is derived from the document's **latest** `WorkflowInstance.status`. It is not `Document.status`, which is a separate field called "Document status".

| Latest workflow instance | Shown as |
|---|---|
| `in_progress` | In approval |
| `approved` | Approved |
| `rejected` | Rejected |
| `cancelled` | Cancelled |
| none | Not submitted |

"Workflow stage" is the current stage name and is set only while the instance is `in_progress`.

## 6. Settings reference

Platform ceilings and defaults are `dms.reports.*` (env `DMS_REPORTS_*`, see `deployment.md` and `.env.example`). A tenant's value is `min(tenant value, ceiling)`; an empty value follows the platform value.

| Setting | Platform default | Ceiling |
|---|---|---|
| Max rows per run | 200,000 | `DMS_REPORTS_MAX_ROWS` |
| Max rows for PDF | 5,000 | `DMS_REPORTS_MAX_PDF_ROWS` |
| Filters / columns per report | 25 / 40 | same names |
| Metadata filters per report | 5 | `..._MAX_METADATA_FILTERS` |
| Preview timeout / run timeout | 10 s / 5 min | - |
| Concurrent runs per tenant | 3 | 10 |
| Queued runs per user | - | 5 |
| File retention | 7 days | 90 |
| Run history retention | 180 days | - |
| Email attachment size | 10 MB | 25 |
| Templates per user | - | 200 |
| Schedules per user / tenant | - | 50 / 1000 |
| Recipients per schedule | 25 | 100 |
| Pause after consecutive failures | 5 | - |
| Catch-up window | 24 h | 168 |
| Hourly schedules | off | `DMS_REPORTS_ALLOW_HOURLY_SCHEDULES` |

Tenant-only settings (no ceiling): allowed formats and default format, CSV delimiter and BOM, date and number formats, default time zone and locale, header, footer, confidentiality notice, logo and filter-summary switches, PDF page size and orientation, over-limit behaviour (truncate or fail), delivery channels and mode, attachments, external recipients and domain allow-list, failure notification, skip empty scheduled reports, default columns and sort.

## 7. Access rules (summary)

- A report only contains documents the person may see; the same holds for a recipient of a scheduled report.
- A run's file can be downloaded by its requester only. A scheduled run's file is available to its recipients; the schedule owner cannot open a recipient's run. Administrators with `view-all-runs` see run metadata, never files.
- A shared template is read-only unless the grant says edit; an official template may be locked (only its editable filters can be changed at run time).
- Previews and idempotent no-ops are not audited.

## 8. Observability

Micrometer: `reports.run.duration`, `reports.run.rows`, `reports.run.failed{reason}`, `reports.schedule.skipped{reason}`, `reports.queue.depth`, tagged by format, never by tenant. Audit events: `events/report-audit-events.md`.

## 9. Known limits

- PDF text is drawn with PDFBox and has no complex-script shaping (e.g. Sinhala). A font that has the glyphs is chosen through `PdfFontChain`; shaping needs a different renderer.
- The reassign notice, hourly schedules, duplicate email, field usage counts and picker search that were limits before are built; see section 10.

## 10. Follow-up behaviour (V143)

- **Hourly schedules.** `frequency = hourly` fires every hour at the minute of the time of day (the hour is ignored), counted in real hours so a clock change never skips or doubles a run. It is accepted only while the organisation's minimum schedule interval is `hourly`, which itself needs `DMS_REPORTS_ALLOW_HOURLY_SCHEDULES=true`; otherwise creating or editing answers 422 `SCHEDULE_HOURLY_NOT_ALLOWED`. If the setting is later turned back to daily, an existing hourly schedule is skipped with `FREQUENCY_NOT_ALLOWED` (and pauses after the usual number of failures).
- **Reassign notice.** The new owner gets a `report.schedule-assigned` notification (standard tier, own email preference applies; text carries the schedule name only). Handing it to the current owner notifies nobody.
- **One email per person.** Cause of the duplicate: the in-app channel writes a `report.ready` notification, and the platform emails that by each person's preference (default on), while the email channel sent its own message. When a run goes to the same person by both channels, the in-app notification now sets `suppressEmail`; its email delivery is recorded as skipped with `sent_by_other_channel`. In-app only still follows the person's preference. A person listed directly and through a role or department gets one run and one email (recipients are de-duplicated before runs are made; covered by a test).
- **Field usage counts.** `GET /admin/fields` returns `officialTemplatesUsing` and `schedulesUsing` per field (filters, columns, sort and group-by; live schedules are active, paused or paused-after-failures). Counts only: private templates are never named.
- **Failure rule in the catalogue.** `settings.pauseAfterConsecutiveFailures` is part of `GET /catalog` so the schedule dialog can state the real rule.
- **Pickers.** `GET /admin/principals?type=user|role&q=&limit=` (configure permission only, active entries, at most 100). The general `/users` and `/roles` lists take only paging parameters (no search) and need user-management permissions the report administrator may not hold.

## 11. Query plans at 100,000 documents (CRG-NFR-002)

Measured by `DocumentReportScaleTest` (one organisation, 100,000 documents, 50 users, 10 types, PostgreSQL 18, the real query builder, an administrator and a restricted user; the full `EXPLAIN (ANALYZE, BUFFERS)` text is written to `target/crg-explain.txt` on every run). Count and first-50-row preview, in milliseconds:

| Filter | Matching | Count | Preview |
|---|---|---|---|
| Document type + created in last 30 days | 170 | 10 | 10 |
| Owner | 71 | 3 | 6 |
| Title contains | 645 | 9 | 11 |
| Legal hold | 0 | 35 | 29 |
| Custom number field | 4,072 | 69 | 70 |
| **File type = pdf** | 14,143 | 87 | 84 |
| **Approval status = in approval** | 2,285 | 142 | 59 |
| **Approval status = not submitted** | 70,857 | 140 | 65 |
| Everything, newest first / by title / by owner name | 95,000 | 18 | 38-44 |

The three slowest and why:

1. **Approval status** (about 140 ms count): sequential scan of `documents` joined, per document, to the latest `workflow_instances` row through `ix_workflow_instances_document` (87,285 index probes, 278k buffers, all cached). The status is derived (latest instance, else "not submitted"), so no index on the document can answer it.
2. **File type** (about 87 ms): hash join of `documents` to `document_versions`, with the extension worked out from `original_filename` for each row. The result is correct for every filename shape but cannot use an index.
3. **Custom number field** (about 69 ms): hash join to `document_metadata` and a JSON read of the field value per row.

Decision: **no index was added.** Each of these is a scan that grows in step with the number of documents (roughly 1.4 µs per document for approval status), the preview needs only the first 50 rows, and every one is far inside the 3 s preview target. An expression index on the file extension or a stored approval status would speed up the count but would need to be kept in step by triggers on every upload and workflow change; that cost is not justified until a tenant is an order of magnitude bigger. Revisit when `reports.run.duration` or the preview p95 says so. The run itself streams the same query, so its cost is the scan plus the file writing (`ReportExportScaleTest`: 100,000 rows to CSV or Excel inside a 192 MB heap).
