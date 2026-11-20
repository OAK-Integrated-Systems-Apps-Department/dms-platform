# Report audit events (5.3 Custom Report Generation)

Registered in `AuditActionCatalog` under module `CRG`. Reports are a data-exit path, so generation, download, external delivery and every settings change are MEDIUM; the rest are LOW.

**Never in a payload:** filter values, row content, file names with personal data, recipient email addresses. Payloads carry ids, counts, field keys and operators. Previews and idempotent no-ops (pausing a paused schedule) are not audited. `ReportAuditCompletenessTest` fails the build if an action is registered but never written.

| Action | Severity | Object | When | Payload (`new_value`) |
|---|---|---|---|---|
| `REPORT_RUN_REQUESTED` | LOW | `REPORT_RUN` | A run was accepted | `format, trigger, filterCount, columnCount, grouped` |
| `REPORT_GENERATED` | MEDIUM | `REPORT_RUN` | A file was produced | `rowCount, truncated, fileSizeBytes, format, trigger` |
| `REPORT_FAILED` | LOW | `REPORT_RUN` | A run failed (also when the sweeper finds a dead run: `REPORT_INTERRUPTED`) | `errorCode, format, trigger`; `failure_reason` truncated to 500 characters |
| `REPORT_DOWNLOADED` | MEDIUM | `REPORT_RUN` | A file was downloaded | `format, fileSizeBytes` |
| `REPORT_TEMPLATE_CREATED` / `_UPDATED` / `_DELETED` | LOW | `REPORT_TEMPLATE` | Template changes (deleting also cancels its schedules with reason `TEMPLATE_DELETED`) | previous and new description of the template (field keys and operators, not values) |
| `REPORT_TEMPLATE_SHARED` | LOW | `REPORT_TEMPLATE` | Grants changed | previous / new description plus `grants` count |
| `REPORT_TEMPLATE_PUBLISHED` | LOW | `REPORT_TEMPLATE` | Made official (or unpublished) | previous / new description plus `audience` |
| `REPORT_SCHEDULE_CREATED` / `_UPDATED` | LOW | `REPORT_SCHEDULE` | Schedule saved | `templateId, frequency, format, channels, deliveryMode, userRecipients, roleRecipients, departmentRecipients, emailRecipients` (counts only) |
| `REPORT_SCHEDULE_PAUSED` / `_RESUMED` / `_CANCELLED` | LOW | `REPORT_SCHEDULE` | State change by the owner, an admin (`reason`/`by` = `ADMIN`) or the system (after repeated failures, or the template was deleted) | `status, reason` |
| `REPORT_SCHEDULE_RUN_SKIPPED` | LOW | `REPORT_SCHEDULE` | A due run could not start | `reason` (`OWNER_INACTIVE`, `OWNER_PERMISSION_LOST`, `TEMPLATE_UNAVAILABLE`, `FIELDS_UNAVAILABLE`, `NO_RECIPIENTS`, `MISSED_WINDOW`, ...) |
| `REPORT_DELIVERED` | MEDIUM | `REPORT_RUN` | A recipient was sent the report | `format, channel, mode, recipientKind` (`internal` / `external`) |
| `REPORT_SETTINGS_UPDATED` | MEDIUM | `TENANT` | Organisation settings saved | `previous_value` and `new_value` snapshots |
| `REPORT_FIELD_SETTINGS_UPDATED` | MEDIUM | `TENANT` | Field catalogue changed | only the changed fields, old and new (`code default` when there was no override) |

Object types added: `REPORT_RUN`, `REPORT_TEMPLATE`, `REPORT_SCHEDULE` (`AuditObjectTypes`).
