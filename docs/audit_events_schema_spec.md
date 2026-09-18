\# audit_events - Schema Specification

Derived from the finalized ER diagram. This is the authoritative field
list and types for the `audit_events` table migration.

## Table: audit_events

| Column              | Type         | Nullable | FK / Notes                                   |
|---------------------|--------------|----------|-----------------------------------------------|
| id                  | uuid (PK)    | No       |                                               |
| tenant_id           | uuid         | Yes      | FK -> tenants.tenant_id. Null for platform-level events (e.g. tenant creation/deactivation). |
| actor_id            | uuid         | Yes      | FK -> users.user_id. Null for system-triggered events (use a fixed SYSTEM reference id, per prior decision). |
| actor_type          | varchar      | No       | USER / SYSTEM / API_KEY                      |
| actor_display_name  | varchar      | Yes      | Snapshot of actor's display name at event time |
| impersonated_by     | uuid         | Yes      | FK -> users.user_id. Populated only if the platform's impersonation/"act on behalf of" capability exists and is in use for this event. |
| action              | varchar      | No       | From the event catalogue (e.g. ROLE_CREATED)  |
| module              | varchar      | No       | Matches permission_modules.module_code        |
| object_type         | varchar      | No       | e.g. ROLE, USER, PERMISSION, TENANT           |
| object_id           | uuid         | Yes      | Null for events with no single object (e.g. login) |
| resource_name       | varchar      | Yes      | Snapshot of the object's human-readable name at event time |
| object_version      | int          | Yes      | Where versioning applies                      |
| outcome             | varchar      | No       | SUCCESS / FAILURE / DENIED                    |
| failure_reason      | varchar      | Yes      | Populated when outcome is FAILURE/DENIED       |
| occurred_at         | timestamptz  | No       | When the event happened                       |
| previous_value      | jsonb        | Yes      | Prior state; sensitive fields redacted before write |
| new_value           | jsonb        | Yes      | New state; sensitive fields redacted before write |
| reason              | text         | Yes      | User-supplied justification; length-limited and sanitized |
| session_id          | varchar(255) | Yes      | Keycloak's own opaque session id (JWT `sid`/`session_state` claim) - not a UUID |
| auth_method         | varchar      | Yes      | PASSWORD / MFA / SSO - only on auth events, based on the factor actually verified |
| permission_code     | varchar      | Yes      | Plain string snapshot, NOT a live FK to permissions.permission_code (must survive the permission being later edited or deleted) |
| data_classification | varchar      | Yes      | Snapshot of the object's sensitivity classification at event time |
| legal_hold_flag     | boolean      | Yes      | Whether the object was under an active legal hold at event time |
| ip_address          | varchar      | Yes      |                                                |
| user_agent          | text         | Yes      |                                                |
| client_type         | varchar      | Yes      | WEB / MOBILE / API / ADMIN_CLI                |
| correlation_id      | uuid         | Yes      | Generated server-side only - never accepted from client input |
| request_id          | uuid         | Yes      | Generated automatically per HTTP request       |
| severity            | varchar      | Yes      | LOW / MEDIUM / HIGH, set via a static action-to-severity mapping |
| record_hash         | varchar      | No       | SHA-256 hash of this record's own content, using a fixed canonical field order |
| previous_hash       | varchar      | Yes      | Hash of the immediately preceding record; null only for the very first record ever written |

## Indexes

- `(tenant_id, occurred_at)` - composite index, since nearly every search/filter query is scoped by tenant and a date range.

## Integrity / immutability requirements

- No UPDATE or DELETE path via the application API.
- Revoke UPDATE, DELETE privileges on this table from the application's
  database role at the DB level (insert/select only) - the immutability
  guarantee must not depend solely on application code.
- A correction to a past event is written as a new compensating event,
  never as an edit to the original row.

## Related existing table to investigate before building reliability

- `OUTBOX_EVENTS` (`outbox_event_id`, `tenant_id`, `aggregate_type`,
  `aggregate_id`, `event_type`, `payload`, `status`, `attempts`,
  `last_error`) already exists in this system. Confirm how it's
  currently used before deciding whether `audit_events` writes should
  be routed through it rather than building a separate reliability
  mechanism from scratch.

## Notes / rationale for fields that were previously flagged as gaps

These fields needed a specific capture rule beyond "just populate it" -
noted here so the implementer doesn't re-introduce the same gap.

- **actor_id** - a null value doesn't distinguish "system-triggered
  action" from "data missing due to a bug." Use a fixed, well-known
  SYSTEM reference id for system-triggered events instead of null.

- **impersonated_by** - depends on whether an impersonation / "act on
  behalf of" capability exists or is introduced in this system; not
  yet confirmed. If it exists: populate automatically from the
  session/token's impersonation context inside the central audit
  service, not passed manually at each call site.

- **reason** - free-text field. Must have a max length and be
  sanitized before storage. Whether it's mandatory or optional should
  be defined per action type in the event catalogue (e.g. mandatory
  for legal hold / permanent deletion), not decided ad hoc per screen.

- **auth_method** - must reflect the factor that was actually verified
  and succeeded, not merely submitted - a failed MFA attempt must not
  be recorded as an MFA-authenticated event.

- **correlation_id** - must never be accepted from client/frontend
  input, since an untrusted caller could otherwise manipulate how
  events are grouped. Generate server-side for the first call in a
  related sequence, then propagate internally through the backend's
  own request chain.

- **record_hash** - requires a fixed, documented canonical
  serialization (field order) so the same data always re-hashes to the
  same value. Without this, the chain can appear "broken" due to an
  inconsistent hashing method rather than actual tampering.

- **previous_hash** - the read-last-record-then-insert step must be
  protected against concurrent writes from multiple application
  instances (e.g. a database-level lock), or the chain can become
  incorrect under concurrent load even with no real tampering.
