# Ownership audit events (DOT Document Ownership and Transfer)

Every ownership change writes its facts twice: a row per document in `ownership_transfers` (previous owner and department, new owner and department, actor, time, reason, batch, access granted, name snapshots - **append-only**, UPDATE/DELETE are rejected by a trigger) and an `audit_events` row in the same transaction. The history table answers "who owned this and when"; the audit events answer "who moved what, in bulk". Retained per the audit retention policy.

Registered in `AuditActionCatalog` (module `DOT`, a real `permission_modules` code) and emitted through `AuditService.event(...)`. The audit write is always the last statement of the transaction (it takes the global chain lock).

## Common fields

| Column | Value |
|---|---|
| `module` | `DOT` |
| `object_type` | `DOCUMENT` (single transfer), `OWNERSHIP_BATCH` (bulk/offboarding), `USER` (offboarding), `DEPARTMENT` (holding owner) |
| `object_id` | the document id; the batch id; the user id; the department id |
| `resource_name` | the document title; `"<kind> ownership transfer"`; the user's name; the department's name |
| `actor_*` | the acting user |
| `tenant_id` | always set |
| `reason` | the transfer note (optional) or, for `USER_OFFBOARDED`, the required reason |

## Events

| Action | Severity | When | Fields (`previous_value` / `new_value`) |
|---|---|---|---|
| `DOCUMENT_OWNERSHIP_TRANSFERRED` | MEDIUM | one document transferred | previous: `ownerUserId, ownerName, ownerDepartmentId, ownershipState`; new: the same plus `accessGranted, convertedToRestricted, batchId` |
| `OWNERSHIP_BATCH_TRANSFERRED` | HIGH | a bulk, reorganisation, holding or offboarding run that moved at least one document - **one event per run**, not per document | `batchId, kind, reason, sourceUserId, sourceDepartmentId, newOwnerUserId, newOwnerName, newOwnerDepartmentId, ownershipState, total, transferred, skipped{reason:count}, accessGranted, convertedToRestricted` |
| `USER_OFFBOARDED` | HIGH | a user was offboarded (reason required) | `mode, batchId, transferred, skipped, newOwnerUserId, flaggedTasks, startedRuns`; written together with the existing `USER_DEACTIVATED` |
| `DEPARTMENT_HOLDING_OWNER_CHANGED` | MEDIUM | a department's holding owner was set, changed or cleared | previous / new `holdingOwnerUserId` |

Per-document detail of a bulk run is in `ownership_transfers` (one row per document, tied to the run by `batch_id`; the run summary is in `ownership_transfer_batches`). Skipped documents write nothing.

## Not audited here, on purpose

- Empty runs (nothing moved) and previews.
- Reads: the history panel, the owned-documents list, the CSV export.
- The access entries written for the new owner (`document_access`) are part of the transfer event (`accessGranted`, `convertedToRestricted`), not separate `DOCUMENT_ACCESS_CHANGED` events.
- Notifications sent (see the notification audit events).
