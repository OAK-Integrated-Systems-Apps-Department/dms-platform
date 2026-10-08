# Audit Events — Implementation Report

Status snapshot of what the audit trail actually covers today, kept
separate from `audit_events_schema_spec.md` (the field-level design spec)
so a future reader doesn't have to infer scope from behavior. Update this
file whenever a new category of event — human-triggered or
system-triggered — is added.

## SYSTEM actor: what it covers today

`audit_events.actor_type = 'SYSTEM'` exists for exactly two cases. It is
**not** a general system/background-process audit trail — read the
"What this does NOT cover yet" section below before assuming broader
coverage exists.

### 1. Failed-login attribution (fallback, automatic)

`AuditService#resolveActor` falls back to the SYSTEM placeholder actor
(`SystemActor.SYSTEM_USER_ID`, a reserved UUID with no `users` row behind
it) whenever an audit event is logged with no bound `TenantContext`
identity. In practice this fires for `LOGIN_FAILURE`
only — a failed login attempt can't be attributed to a real user without
risking username enumeration (see `AuthService#login`'s own doc comment).
This is automatic: no call site opts into it explicitly, it's just what
happens when `AuditService#persist` can't resolve a real actor.

- Action: `LOGIN_FAILURE`
- Module: `ASM`, Severity: `MEDIUM`
- `tenant_id`: always `NULL` (a failed login has no resolved tenant either)

### 2. Outbox relay permanent failure (explicit, `.systemActor()`)

`OutboxRelay` (`com.oak.dms.outbox.OutboxRelay`) polls `outbox_events` on
a fixed interval (`dms.outbox.relay-interval-ms`, default 2000ms) and
forwards each `PENDING` row to RabbitMQ. This is the one genuine
scheduled-job code path that calls `AuditEventBuilder#systemActor()`
explicitly, as opposed to the automatic fallback above.

**Only the terminal, unrecoverable failure is audited — not every run,
and not every retry:**

| Outcome | Audited? | Why |
|---|---|---|
| Successful publish | **No** | Runs on a 2-second tick over up to 100 rows at a time; logging every success would flood the trail with routine, non-actionable volume. |
| Transient failure (attempts 1 to `MAX_ATTEMPTS`-1, currently 4) | **No** | Expected to self-heal on the next tick (a RabbitMQ blip, a network hiccup); not yet a state anyone needs to act on. |
| Permanent failure (attempts reach `MAX_ATTEMPTS`, currently 5) | **Yes** | The row is marked `failed` and will never be retried automatically — this is the one state that's genuinely actionable. |

The one event this produces:

```
event("OUTBOX_EVENT_PUBLISH_FAILED")
    .systemActor()
    .tenantId(event.getTenantId())
    .objectType("OUTBOX_EVENT")
    .objectId(event.getOutboxEventId())
    .resourceName(event.getEventType())        // e.g. "user.created"
    .outcome(FAILURE)
    .failureReason(cause.getMessage())
    .newValue({ aggregateType, aggregateId, attempts })
    .log()
```

- Module: `SHAD` (System Health and Administration Dashboard — the module
  whose purpose is platform infrastructure status, not a business domain)
- Severity: `HIGH`
- `reason` field: not applicable (`reasonRequired = false`) — there's no
  human in this code path to supply one

**Prerequisite fix bundled with this**: `@Scheduled` on `OutboxRelay` had
no effect at all — `@EnableScheduling` was missing from
`DmsCoreApplication`, so the method never ran. Confirmed against the dev
database: every row in `outbox_events` was stuck in `pending`, none had
ever reached `published` or `failed`. Fixed by adding
`@EnableScheduling` to `DmsCoreApplication`. Verified after the fix: all
previously-stuck rows transitioned to `published` on the very next tick.

## Every `@Scheduled` job / background process in the codebase

`OutboxRelay.relayPendingEvents()` is the **only** one. Confirmed by
searching the entire backend for `@Scheduled`, `@Async`,
`CommandLineRunner`, `ApplicationRunner`, `@RabbitListener`,
`@KafkaListener`, `@EventListener`, `@JmsListener`, `@EnableScheduling`,
`ScheduledExecutorService`, and ad hoc `new Thread(...)` /
`@PostConstruct` loops — nothing else matched. There is no other silent
gap of this specific shape (a scheduled/background process whose
failures go unaudited) today, because there is no other scheduled or
background process, period.

If a new one is added later (a cleanup job, a digest email sender, a
retry sweeper, etc.), it does not automatically get audit coverage —
whoever adds it needs to explicitly decide what's audit-worthy about it
and call `AuditService` the same way `OutboxRelay` does here.

## What "system failure" auditing does NOT cover yet

This is a checklist of gaps, not a to-do list to build now. Each of
these already has *some* handling — none of them are unhandled or
crash the app — they're just not written to the audit trail, so an
audit-log reviewer would see nothing if one of these happened.

| Category | What happens today | Where |
|---|---|---|
| Unhandled exceptions in request handling | Caught by a global handler, logged via SLF4J (`log.error`), returns a generic 500 to the caller | `GlobalExceptionHandler#handleUnexpected` (`@ExceptionHandler(Exception.class)`) |
| Keycloak Admin API call failures | Caught locally, logged via SLF4J, degrades to a safe default (e.g. "MFA not required") rather than failing the request | `AuthService#isMfaRequired` |
| Email send failures (password reset) | Caught by an outer try/catch, logged via SLF4J as a warning, deliberately swallowed so the response can't be used to enumerate valid accounts | `PasswordResetService#requestReset` |
| Database connectivity issues | Handled at the connection-pool level (HikariCP) and surfaced via `/actuator/health`, not application code | N/A — infra-level, no application audit hook exists |
| Any other external service integration added later | N/A yet | N/A — no other external service is called synchronously from request-handling code today |

None of these write an `audit_events` row. If any of them need to become
audit-worthy in the future (e.g. "alert on repeated Keycloak Admin API
failures" or "audit every unhandled 500"), that's a deliberate design
decision each one needs on its own — same reasoning as the outbox relay
above (what's the actionable, non-noisy signal here, not just "log
everything").
