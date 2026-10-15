# Notification Module — Implementation Plan (Oak DMS, section 3.2)

> Single source of truth + progress log for the N2–N13 build.
> Branch stays `feature/chamath/workflow-notification`; all work left uncommitted
> (the user handles all git). Build begins only on the user's reply "start execution".

## Context

Requirement section **3.2 Notification** (N-FR-001..006, N-NFR-001..005) is the last
unbuilt workflow deliverable — it is phase-2 **future item F2** ("move mail to the outbox
and `notification-worker`"). Today there is **no shared notification service**: ~23
distinct emails are sent by **5 hand-rolled plain-text mailers** (workflow, comment,
scanning, key-rotation, password-reset), each copying the next one's best-effort,
no-retry, after-commit pattern. There is **no in-app notification store** — the dashboard
bell is a `localStorage`-only unread dot over the dashboard activity feed. This plan builds
one shared, transactional, retrying, preference-aware, access-aware, auditable notification
service in its own module (`com.oak.dms.notification`) inside `dms-core`, migrates every
existing mailer onto it (password-reset excepted), turns the bell into a real inbox, and
adds the inactivity reminder + escalation required by N-FR-003/004.

Intended outcome: every notification is durably stored and visible on next login
(WA-NFR-004), email is retried with backoff and ≥99% reliability tracked (N-NFR-001),
every attempt is immutably logged (N-NFR-005), reminders/escalations are configurable per
workflow type/stage without restart (N-FR-006/N-NFR-004), and users/tenant-admins control
non-critical email while critical security/escalation mail cannot be suppressed.

---

## Findings (from codebase exploration)

**Mail today** — All mail is in `dms-core` (workers are empty `.gitkeep` stubs;
`notification-worker` has no pom and is not in `backend/pom.xml`). 5 classes touch
`JavaMailSender`, all send **plain-text `SimpleMailMessage`** (no HTML, no Thymeleaf, no
base Mailer):
- `workflow/helper/WorkflowNotificationMailer` (9 methods) + `service/impl/WorkflowNotificationListener` (Phase-1) + `WorkflowActivityNotificationListener` (Phase-2), via `workflow/helper/NotificationDispatcher` (async best-effort, no retry) + `WorkflowRecipients`.
- `comment/helper/CommentNotificationMailer` + `comment/service/impl/CommentNotificationListener` (reuses workflow `NotificationDispatcher`/`WorkflowRecipients`).
- `scanning/helper/ThreatNotificationMailer` + `service/impl/ThreatNotificationListener` (own async copy; recipients = uploader + `DOCM_QUARANTINE_VIEW` holders + Keycloak `TENANT_ADMIN`).
- `encryption/KeyRotationReminderMailer` (direct, called by `KeyRotationScheduler`, which is already multi-instance-safe via `pg_try_advisory_lock`).
- `security/auth/PasswordResetService` (direct, synchronous) — **stays outside the service** (decision 4).

SMTP = Spring Boot auto-config from `application.yml` `spring.mail.*` (Mailpit locally,
host `mailpit` in docker profile). From-addresses are fragmented across 3 config keys.
Off-thread sending uses the injected `applicationTaskExecutor`; no `@Async`. Scheduling via
`@EnableScheduling` on `DmsCoreApplication`, shared 2-thread pool (`spring.task.scheduling.pool.size=2`).

**Outbox** (`outbox/OutboxRelay`) is a RabbitMQ transactional outbox **not wired to mail**
and **not multi-instance safe** (its batch query has no `FOR UPDATE SKIP LOCKED`). We do
**not** reuse it; the notification dispatcher implements its own SKIP-LOCKED + lease claim.

**Scheduler seam** — `workflow/service/impl/WorkflowSlaScheduler` (`@Scheduled`, 1-min,
`@ConditionalOnProperty dms.workflow.sla.enabled`) → `WorkflowSlaServiceImpl.runOnce()`:
unlocked `findDue` scan, then per-task short tx that locks the instance
`FOR UPDATE SKIP LOCKED` + stage-instance + task in a fixed order, re-checks under lock,
stamps `reminder_sent_at`/`escalated_at` (fire-once idempotency), and **publishes
`WorkflowTaskReminderEvent` / `WorkflowTaskEscalatedEvent`**. These events are the
integration seam — the notification module subscribes and creates notifications.

**Audit** — `audit/service/AuditService` writes `audit_events` in the caller's tx, guarded
by a transaction-scoped advisory lock (`pg_advisory_xact_lock(7331991001)`, the only one in
the app — we will **not** reuse that key). Append-only on `audit_events`/`workflow_actions`
is **`REVOKE UPDATE, DELETE ... FROM PUBLIC`**, NOT a trigger (brief wording corrected).
Per decision below, our attempts table uses **a BEFORE UPDATE/DELETE trigger + REVOKE**.

**Permissions** — 3-level catalog (`permission_modules` → `permission_actions` →
`permissions`), dotted codes `<module>.<resource>.<verb>`, seeded by migration, granted to
no role (TENANT_ADMIN/PLATFORM_ADMIN implicit via `AuthorizationService`).

**Tenant isolation** — `security/auth/TenantContext` ThreadLocal, enforced in app code by
passing `tenantId` into every query; cross-tenant miss → `ResourceNotFoundException` (404,
never 403). Composite `(tenant_id, pk)` unique + composite FKs everywhere.

**Document access** — `folder/DocumentAccessService.forUser(tenantId, userId)` /
`forUsers(...)` → `DocumentAccessView.canSee(document)` (includes `workflowReadable`,
the workflow-scoped read). Precedent for access-aware mail: `comment_mentions.had_document_access`
gates the doc link in mention mail. Reuse this exactly.

**Schema** — `V81` is latest; next is **V82**. `workflow_stages` **already has**
`reminder_before_hours`, `escalation_target`, `escalation_user_id` → new stage columns must
be distinctly named. `workflow_templates` has **no** reminder/escalation columns.
`workflow_tasks` has `due_at`, `reminder_sent_at`, `escalated_at`, `escalated_to` but **no**
`last_activity_at`/`inactivity_reminder_sent_at`. `users` has `email` (NOT NULL in schema)
but **no phone** (SMS impossible without a schema change — consistent with SMS being out).
Rollbacks live in `backend/dms-core/src/main/resources/db/rollback/V<n>__*.rollback.sql`
(manual, outside classpath). Migration tests: `V<range>...MigrationTest` using Flyway + raw
JDBC, asserting constraints bite, rollbacks reverse, re-apply works.

**Frontend** — Next.js 16.2.6 / React 19 / bun; **no JS test framework** (quality gate =
`bun run typecheck` + `bun run lint`). App-router pages under `frontend/app/*`; API clients
`frontend/lib/<domain>/api.ts` + react-query `use-*.ts`; nav in `components/dashboard/nav-config.ts`
with `permission` gating + `liveBadge`. Bell = `components/layout/app-topbar.tsx`
(localStorage `dms.activity.seen`). Closest templates: Mentions (`app/mentions`,
`lib/comments`, paged + `canOpenDocument` lock) and My Tasks (`lib/workflow/use-my-task-count.ts`
live badge). NOTE: `frontend/AGENTS.md` warns Next 16 has breaking changes vs training data —
read `node_modules/next/dist/docs/` before writing frontend code.

### Conflicts / decisions recorded against the brief
1. **Append-only** — brief says "trigger, like workflow_actions"; reality is REVOKE-only,
   which does not bind the postgres owner the tests connect as. **Resolution (user-approved):
   BEFORE UPDATE/DELETE trigger that RAISEs + the house REVOKE** → the "rejects UPDATE/DELETE"
   test passes as owner, and immutability (N-NFR-005) is strongest. (Deviation #1.)
2. **HTML email** — all existing mail is plain-text; N6 requires "same content".
   **Resolution (user-approved): one shared HTML template (with plain-text alternative part)
   for new AND migrated emails; the "same content" tests assert subject + essential facts +
   correct link, not byte-identical text.** (Deviation #2.)
3. **SMS / N-FR-002 / N-NFR-001** — brief removes SMS from scope; N-FR-002 literally
   mandates email+SMS+in-app. **Resolution (locked by brief): email + in-app only, behind a
   `NotificationChannelSender` interface so SMS/push can be added later. Documented as an
   accepted requirement deviation in traceability.** (Deviation #3.)
4. **N-FR-005 "document's audit trail"** — satisfied by the append-only attempts table
   (every attempt) + selective main-audit entries (decision 6). Two-layer logging.
5. **Migration numbers** — V82–V84 used now; `develop` may also consume V82+. Flag at merge
   and renumber if collision (brief decision 9). (Risk R-1.)

---

## Data model & migration plan

Three migrations + matching rollbacks + one migration test. All tables carry
`tenant_id NOT NULL`, composite `UNIQUE (tenant_id, <pk>)`, composite FKs, and the standard
audit block where noted (`created_by/at`, `updated_by/at`, `is_deleted`, `deleted_at`).

### V82 — `V82__create_notification_core.sql`
Tables (exact columns per brief section 3; key constraints shown):

- **`notification_categories`** (global, `tenant_id` NULL): `category_code` PK,
  `module` NOT NULL, `tier` CHECK IN (`critical`,`standard`,`optional`),
  `default_email_enabled` BOOL NOT NULL, `display_name`, `description`, `is_active` BOOL
  DEFAULT true, `created_at`. **Seeded in this migration** (see category seed below).
- **`tenant_notification_settings`**: `setting_id` PK, `tenant_id` FK→tenants,
  `category_code` FK→notification_categories, `email_enabled_default` BOOL NOT NULL,
  audit block + soft delete. `UNIQUE (tenant_id, category_code)` (partial, `is_deleted=false`).
- **`user_notification_preferences`**: `preference_id` PK, `tenant_id`, `user_id`
  (composite FK `(tenant_id,user_id)`→users), `category_code`, `channel` CHECK IN (`email`),
  `enabled` BOOL NOT NULL, audit block + soft delete.
  `UNIQUE (tenant_id, user_id, category_code, channel)` (partial).
- **`notifications`** (inbox): `notification_id` PK, `tenant_id`, `recipient_user_id`
  (composite FK→users), `category_code`, `tier`, `title`, `body`, `link_url`, `object_type`,
  `object_id`, `actor_user_id`, `source_module`, `dedupe_key`, `read_at`, `archived_at`,
  `created_at`. `UNIQUE (tenant_id, recipient_user_id, dedupe_key)` (partial, dedupe_key NOT NULL);
  index `(tenant_id, recipient_user_id) WHERE read_at IS NULL AND archived_at IS NULL` for unread count.
- **`notification_deliveries`** (queue): `delivery_id` PK, `tenant_id`, `notification_id`
  (composite FK→notifications), `channel`, `destination`, `status` CHECK IN
  (`pending`,`sending`,`sent`,`failed`,`skipped`), `skip_reason`, `attempt_count` INT
  DEFAULT 0, `max_attempts` INT NOT NULL, `next_attempt_at`, `locked_until`, `last_error`,
  `sent_at`, `created_at`, `updated_at`. Claim index
  `(next_attempt_at) WHERE status='pending'`.
- **`notification_delivery_attempts`** (append-only): `attempt_id` PK, `tenant_id`,
  `delivery_id`, `notification_id`, `recipient_user_id`, `attempt_number`, `channel`,
  `destination`, `outcome` CHECK IN (`sent`,`failed`,`skipped`), `error_code`,
  `error_message`, `duration_ms`, `attempted_at`. **Append-only enforcement:**
  ```sql
  REVOKE UPDATE, DELETE ON notification_delivery_attempts FROM PUBLIC;
  CREATE FUNCTION notification_attempts_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$ BEGIN
      RAISE EXCEPTION 'notification_delivery_attempts is append-only (%).', TG_OP;
    END $$;
  CREATE TRIGGER trg_notification_attempts_immutable
    BEFORE UPDATE OR DELETE ON notification_delivery_attempts
    FOR EACH ROW EXECUTE FUNCTION notification_attempts_immutable();
  ```
- **Category seed** (in V82, `ON CONFLICT DO NOTHING`): all rows in the mapping table below.

### V83 — `V83__add_workflow_notification_config_columns.sql`
- `workflow_templates` ADD: `default_reminder_before_hours INT` (CHECK >0),
  `default_inactivity_reminder_hours INT` (CHECK >0), `default_escalate_after_reminder_hours INT`
  (CHECK >0), `default_escalation_target VARCHAR(20) DEFAULT 'none'` CHECK IN
  (`none`,`department_head`,`named_user`), `default_escalation_user_id UUID`
  (CHECK set iff target=`named_user`; composite FK→users).
- `workflow_stages` ADD: `inactivity_reminder_hours INT` (CHECK >0, NULL=inherit),
  `escalate_after_reminder_hours INT` (CHECK >0, NULL=inherit).
- `workflow_tasks` ADD: `last_activity_at TIMESTAMPTZ`, `inactivity_reminder_sent_at TIMESTAMPTZ`.
  Backfill `last_activity_at = COALESCE(claimed_at, acted_at, created_at)` for open rows.
  Partial index `(last_activity_at) WHERE status IN ('pending','claimed') AND is_deleted=false`.

### V84 — `V84__seed_notification_permissions.sql`
New module `NOTIF` (+ actions) and permission codes (seeded, granted to no role):
- `notif.notification-settings.manage` — tenant admin: category email defaults + reminder defaults.
- `notif.delivery-log.view` — admin: delivery log with filters.
- `notif.delivery-log.retry` — admin: "retry now".
(Own inbox + own preferences require only authentication — no permission code, matching how
`/mentions/my` and `/tasks` gate on identity, not a permission.)

### Rollbacks
`rollback/V82__create_notification_core.rollback.sql` (drop trigger+function, tables in FK
order), `rollback/V83__...rollback.sql` (drop columns/indexes/constraints, restore prior
index predicates), `rollback/V84__...rollback.sql` (delete seeded permissions/actions/module).
Each header documents reverse run order and uses `IF EXISTS`.

### Migration test
`backend/.../notification/V82ToV84NotificationMigrationTest.java` (mirrors
`V78ToV81WorkflowPhase2MigrationTest`): applies up; asserts CHECK constraints bite
(bad tier/status rejected), the attempts trigger rejects UPDATE/DELETE **as the test's
postgres role**, unique/dedupe constraints hold, category + permission seeds landed; runs
rollbacks and re-applies cleanly.

---

## Email → category / tier mapping (N8)

| Current mailer method | New `category_code` | Tier | Email default | Recipient |
|---|---|---|---|---|
| Threat → uploader | `security.threat.uploader` | critical | always on | uploader |
| Threat → admin | `security.threat.admin` | critical | always on | quarantine-view holders + tenant admins |
| Key rotation reminder | `security.key-rotation.reminder` | critical | always on | key-manage holders + tenant admins |
| WF escalated (to target) / copy to holders | `workflow.task.escalated` | critical | always on | escalation target (+ holders copy) |
| WF escalation failed | `workflow.escalation.failed` | critical | always on | reassign holders + tenant admins |
| WF stage unassignable | `workflow.stage.unassignable` | critical | always on | reassign holders + tenant admins + initiator |
| WF task delegated/reassigned (to new holder / notice to previous) | `workflow.task.delegated` | critical | always on | new holder (+ previous holder notice) |
| WF info requested | `workflow.info.requested` | critical | always on | the person asked |
| WF task assigned | `workflow.task.assigned` | standard | on | assignee / pool members (minus initiator) |
| WF reminder (due-date **and** inactivity) | `workflow.task.reminder` | standard | on | current holder(s) |
| WF outcome | `workflow.outcome` | standard | on | initiator |
| WF info answered/declined/closed/withdrawn | `workflow.info.resolved` | standard | on | requester / approver |
| Comment mention | `comment.mention` | standard | on | mentioned user |
| Comment reply | `comment.reply` | standard | on | thread-root author |
| **NEW** doc/folder shared with you | `document.shared` | standard | on | grantee |
| **NEW** new version uploaded mid-approval | `workflow.new-version` | standard | on | current holder(s) |
| **NEW** document status changed (own/follow) | `document.status-changed` | optional | off | owner/followers |
| **NEW** other modules' informational | `module.info` | optional | off | module-specified |
| Password reset (self/admin/new-user) | — (stays direct email) | — | — | — |

---

## Delivery state machine

```
            create (in caller tx)
                  │
                  ▼
   ┌─────────── pending ◄───────────── (retry: backoff, attempt_count++)
   │              │ claim (SKIP LOCKED + lease)
   │              ▼
   │          sending ──► sent  (status=sent, sent_at set)         [terminal]
   │              │
   │        send fails
   │              ├─ attempt_count < max_attempts ─► pending (next_attempt_at = backoff)
   │              └─ attempt_count >= max_attempts ─► failed      [terminal, audited, admin-retryable]
   │
   └─ pre-send skip (no email addr / channel disabled by prefs) ─► skipped (skip_reason) [terminal]
```
Every transition appends one `notification_delivery_attempts` row (outcome sent/failed/skipped).
`retry now` (admin) moves `failed` → `pending`, `next_attempt_at=now`, raises `max_attempts`.
In-app delivery is created `sent` immediately (it is the durable inbox row itself); it never
skips/fails, so the inbox entry always stands even when email fails (brief decision 3/4).

### Retry & backoff (example, max_attempts=5, base=60s, factor×2)
attempt 1 fails → next in 60s; 2 → 120s; 3 → 240s; 4 → 480s; 5 fails → **final failure**:
`status=failed`, audit `NOTIFICATION_DELIVERY_FAILED` (recipient, channel, last_error), in-app
stands, admin can "retry now". Lease `locked_until = now + dms.notification.dispatcher.lease`
(default 2m) protects a claimed row if the instance crashes mid-send.

---

## Dispatcher design (across instances)

`notification/dispatch/NotificationDispatcher` + `NotificationDispatchScheduler`
(`@Scheduled(fixedDelayString=${dms.notification.dispatcher.poll-interval:PT30S})`,
`@ConditionalOnProperty(prefix="dms.notification.dispatcher", name="enabled",
havingValue="true", matchIfMissing=true)` — **per-instance switch** so delivery can later
move to a dedicated instance / `notification-worker` without changing any caller).

**Claim (atomic, SKIP LOCKED + lease)** — single claim path shared by the poller and the
AFTER_COMMIT nudge:
```sql
UPDATE notification_deliveries d
   SET status='sending', locked_until = now() + :lease, updated_at = now()
 WHERE d.delivery_id IN (
     SELECT delivery_id FROM notification_deliveries
      WHERE status='pending' AND next_attempt_at <= now()
        AND (locked_until IS NULL OR locked_until < now())
      ORDER BY next_attempt_at
      FOR UPDATE SKIP LOCKED
      LIMIT :batch)
RETURNING d.*;
```
Each claimed row is processed on a dedicated `ThreadPoolTaskExecutor`
(`notification/config/NotificationExecutorConfig`, not the shared scheduling pool). On
success → `sent`; on failure → backoff/`pending` or `failed`. A crashed instance's leased
rows become claimable again after `locked_until`. **Low latency (N-NFR-002):** an
`@TransactionalEventListener(AFTER_COMMIT)` nudge submits the just-written delivery IDs to
the executor immediately; the poller is the reliability backstop + multi-instance/retry path.

Two instances running the poller never double-send: SKIP LOCKED gives each row to exactly one
claimer, and the lease prevents re-claim while sending. (Concurrency test runs 5×.)

---

## Preference resolution

`resolve(tenantId, userId, categoryCode, channel=email)`:
1. category `critical` → **ENABLED** (ignore user & tenant; UI shows locked). In-app always on.
2. else `user_notification_preferences(tenant,user,category,email).enabled` if present.
3. else `tenant_notification_settings(tenant,category).email_enabled_default` if present.
4. else `notification_categories.default_email_enabled`.

**No email address** → email delivery row created `skipped` (`skip_reason='no_email'`),
in-app stands, admin flagged (audit `NOTIFICATION_RECIPIENT_NO_EMAIL` + surfaced in admin
delivery log). Examples:
- `security.threat.uploader` (critical), user disabled everything → **email sent** (critical).
- `comment.mention` (standard), no prefs → category default on → **email + in-app**.
- `comment.mention`, user pref enabled=false → **in-app only**.
- `document.status-changed` (optional), no prefs → default off → **in-app only**; user opts in → email.
- Tenant admin sets `document.status-changed` default on → users without own pref get email;
  a user's own pref still overrides. Tenant admin **cannot** change critical categories.

---

## Reminder / escalation timeline (both rules)

Driven by the existing SLA scan (`WorkflowSlaServiceImpl`, extended), fire-once via stamps.
Config resolution: stage value if non-null, else template default (read each scan → changes
apply without restart). `awaiting_info` tasks skipped (SLA pause; `DECIDABLE` excludes them).

- **Due-date rule (existing):** reminder at `due_at − reminder_before_hours` (stamp
  `reminder_sent_at`); escalate at `due_at` (stamp `escalated_at`).
- **Inactivity rule (new):** `last_activity_at` updated on claim / delegate / reassign /
  info-answered. Inactivity reminder at `last_activity_at + inactivity_reminder_hours`
  (stamp `inactivity_reminder_sent_at`, publish `WorkflowTaskReminderEvent`). Escalation at
  `inactivity_reminder_sent_at + escalate_after_reminder_hours`.
- Each **reminder** (due-date, inactivity) fires at most once (separate stamps). **Escalation
  fires once** — the first trigger (due-date or inactivity) stamps the shared `escalated_at`;
  the other no longer fires.

**Example** (assigned t0, due t0+72h, reminder_before=24h, inactivity_reminder=48h,
escalate_after_reminder=24h): user claims at t0+10h → inactivity clock resets to t0+10h.
Due-date reminder fires t0+48h; inactivity reminder fires t0+58h (both once). Escalation:
due-date escalation at t0+72h vs inactivity escalation at t0+58h+24h=t0+82h → **due-date wins,
escalates once at t0+72h**. If instead the user never claims, inactivity reminder at t0+48h,
inactivity escalation at t0+72h.

---

## Access-aware content rule

For any notification referencing a document, compute
`DocumentAccessService.forUser(tenantId, recipientUserId).canSee(document)` (batch:
`forUsers`) — this already includes workflow-scoped read. If **true** → include document
title + `link_url` to the document. If **false** → neutral title/body (e.g. "A document
needs your attention") and `link_url = null`. Mirrors the existing
`comment_mentions.had_document_access` precedent. Access is evaluated per recipient at
notification-creation time.

---

## Build steps N2–N13

Each step: goal · files · APIs · rules/edge cases · deps · tests · size · risk.

### N2 — Migrations, entities, repositories, seeds, permissions, migration test
- **Goal:** schema + JPA layer + seeds in place.
- **Files:** `db/migration/V82,V83,V84` + 3 rollbacks; entities `notification/entity/*` (6);
  enums `notification/enums/{NotificationTier,DeliveryStatus,DeliveryOutcome,NotificationChannel}`;
  repos `notification/repository/*` (6); `workflow` entity edits (template/stage/task new
  fields); permission constants in `security/.../PermissionCodes`; `V82ToV84NotificationMigrationTest`.
- **Rules:** composite FK + tenant unique; attempts repo exposes **no** update/delete; dedupe_key NOT NULL.
- **Deps:** none. **Tests:** migration test (up/down/re-apply, constraints, trigger rejects UPDATE/DELETE). **Size:** L. **Risk:** migration-number collision with develop (R-1).

### N3 — NotificationService (transactional write)
- **Goal:** `notify(recipients, categoryCode, title, body, link, object, actor)` writes the
  inbox row(s) **and** delivery rows in the **caller's transaction**.
- **Files:** `notification/service/NotificationService` (+ `NotificationRequest` builder),
  `service/impl/NotificationServiceImpl`, `helper/NotificationRecipients`.
- **Rules:** one `notifications` row per recipient (dedupe via `(tenant,recipient,dedupe_key)`
  → `ON CONFLICT DO NOTHING` semantics); in-app delivery row `sent` immediately; email delivery
  row `pending`/`skipped` per prefs; tenant from `TenantContext` or passed explicitly (events
  run without context — pass tenantId). **Edge:** duplicate dedupe_key → no second notification.
- **Deps:** N2. **Tests:** write-in-tx (rollback drops both rows), dedupe, in-app always created. **Size:** M. **Risk:** M.

### N4 — Dispatcher (SKIP LOCKED, lease, backoff, final failure, per-instance switch)
- **Files:** `dispatch/NotificationDispatcher`, `dispatch/NotificationDispatchScheduler`,
  `dispatch/DeliveryClaimRepository` (native claim query), `config/NotificationExecutorConfig`,
  `config/NotificationProperties` (`dms.notification.dispatcher.{enabled,poll-interval,lease,batch-size,base-backoff,max-attempts}`),
  AFTER_COMMIT nudge listener.
- **Rules:** claim query above; backoff; final failure audits + leaves in-app; retry-now path.
- **Deps:** N3, N5. **Tests:** delivery+retry, final-failure, **2 parallel dispatchers over same rows, no double-send, run 5×**. **Size:** L. **Risk:** H (concurrency — locking memory rule: scalar id lookups, no load-before-lock).

### N5 — Channels behind one interface (email HTML + in-app)
- **Files:** `channel/NotificationChannelSender` (interface `supports(channel)` + `send(delivery,notification)`),
  `channel/EmailChannelSender` (uses `JavaMailSender`, `MimeMessageHelper` HTML + plain-text alt),
  `channel/InAppChannelSender` (no-op send — row already is the inbox),
  `channel/email/NotificationMailRenderer` + HTML templates (one base layout + per-category body),
  `config/NotificationProperties.mailFrom` (single consolidated from-address, defaults preserved).
- **Rules:** HTML + plain-text alternative part; branding via `BrandingProperties`; links via
  `app.frontend-base-url`. **Deps:** N3. **Tests:** email rendered & sent (GreenMail/Mailpit),
  interface dispatch picks channel. **Size:** L. **Risk:** M.

### N6 — Tiers & preference resolution, tenant defaults, no-email users
- **Files:** `service/PreferenceResolver`, `service/impl/TenantNotificationSettingServiceImpl`,
  `service/impl/UserNotificationPreferenceServiceImpl`.
- **Rules:** resolution order above; critical locked; no-email → skip email + flag admin.
- **Deps:** N2,N3. **Tests:** all resolution examples, critical not suppressible by user or tenant,
  no-email path. **Size:** M. **Risk:** M.

### N7 — Two-layer logging
- **Files:** `helper/NotificationAttemptLog` (append row per attempt),
  audit catalog entries in `audit/catalog/AuditActionCatalog` + `AuditObjectTypes`
  (`NOTIFICATION_ESCALATED`, `NOTIFICATION_ESCALATION_SKIPPED`, `NOTIFICATION_DELIVERY_FAILED`,
  `NOTIFICATION_SETTING_CHANGED`, `NOTIFICATION_RETRIED`, `NOTIFICATION_PREFERENCE_CHANGED`,
  `NOTIFICATION_RECIPIENT_NO_EMAIL`), include attempts in compliance export.
- **Rules:** every attempt → attempts table; main audit only for the 6 catalog cases.
- **Deps:** N2,N4. **Tests:** attempts table rejects UPDATE/DELETE (trigger), audit entries for
  escalation/final-failure/setting-change/retry/pref-change. **Size:** M. **Risk:** M.

### N8 — Migrate every existing mailer onto the service
- **Files (refactor listeners to call `NotificationService`, delete old mailers):**
  `WorkflowNotificationListener`, `WorkflowActivityNotificationListener`,
  `CommentNotificationListener`, `ThreatNotificationListener`, `KeyRotationScheduler`
  → call `NotificationService`; **delete** `WorkflowNotificationMailer`,
  `CommentNotificationMailer`, `ThreatNotificationMailer`, `KeyRotationReminderMailer`
  (recipient logic moved to `NotificationRecipients`); retire workflow `NotificationDispatcher`
  usages. `PasswordResetService` **untouched**. Keep all existing Spring events.
- **Rules:** preserve recipients + essential content per the mapping table; each mapped to its
  category/tier. **Deps:** N3–N7. **Tests:** every migrated email still sent with same content
  (subject + facts + link) and same recipients; existing workflow/comment/scanning tests green.
  **Size:** L. **Risk:** H (regression — strongest test coverage here).

### N9 — Access-aware content for document-referencing notifications
- **Files:** `helper/NotificationContent` (resolve title/link per recipient via
  `DocumentAccessService.forUser/forUsers`); apply in all document-referencing notify calls.
- **Deps:** N8. **Tests:** recipient with access gets title+link; without → neutral + no link;
  workflow-scoped reader gets link. **Size:** M. **Risk:** M.

### N10 — New triggers + developer guide
- **Files:** listeners/hooks for `document.status-changed` (on status-change event/promoter),
  `workflow.new-version` (on `DocumentVersionPromoter` mid-approval event), `document.shared`
  (on folder/document access-grant), approaching-deadline (already the reminder); seed those
  categories (in V82). **Guide:** `docs/notification-developer-guide.md` — how to add a category
  + call the service, with DOT-FR-006 / OMD-FR-016 / SHAD-FR-009 / CRG-FR-006 as worked examples.
- **Deps:** N3–N9. **Tests:** each new trigger creates the right notification. **Size:** M. **Risk:** M.

### N11 — Inactivity reminder + post-reminder escalation in the scheduler + admin UI fields
- **Files:** extend `WorkflowSlaServiceImpl` (+ `WorkflowSlaRepository.findDue` SQL) for the
  inactivity rule; update `last_activity_at` on claim/delegate/reassign/info-answered in the
  relevant workflow services; config resolution (stage→template); admin template/stage editor
  fields (`frontend/components/.../workflows/*` + `lib/workflow/api.ts` DTOs).
- **Rules:** fire-once stamps; `awaiting_info` skipped; changes without restart.
- **Deps:** N2,N3. **Tests:** due-date & inactivity reminders each fire once, escalation once,
  template default vs stage override, awaiting_info skipped; **concurrency 5×** (touches locking).
  **Size:** L. **Risk:** H.

### N12 — APIs & UI (inbox, bell, preferences, admin settings, delivery log)
- **APIs** (`notification/controller/*`):
  | Method | Path | Body → Response | Permission |
  |---|---|---|---|
  | GET | `/api/v1/notifications?page&size&unread&category` | → page of `NotificationView` | authenticated (self) |
  | GET | `/api/v1/notifications/unread-count` | → `{count}` | self |
  | POST | `/api/v1/notifications/{id}/read` | → 204 | self (owner row, else 404) |
  | POST | `/api/v1/notifications/read-all` | → 204 | self |
  | POST | `/api/v1/notifications/{id}/archive` | → 204 | self |
  | GET | `/api/v1/notifications/preferences` | → categories + effective + override + tier(locked) | self |
  | PUT | `/api/v1/notifications/preferences/{categoryCode}` | `{emailEnabled}` → 200 (reject critical 409) | self |
  | GET | `/api/v1/admin/notifications/settings` | → category defaults + reminder defaults | `notif.notification-settings.manage` |
  | PUT | `/api/v1/admin/notifications/settings/{categoryCode}` | `{emailEnabledDefault}` (standard/optional only) | manage |
  | GET | `/api/v1/admin/notifications/deliveries?status&channel&category&recipient&from&to` | → page | `notif.delivery-log.view` |
  | POST | `/api/v1/admin/notifications/deliveries/{id}/retry` | → 202 | `notif.delivery-log.retry` |
- **UI:** `frontend/app/inbox/page.tsx` (+ content); `components/notifications/{notification-list,notification-item}.tsx`;
  `lib/notifications/{api.ts,use-notifications.ts,use-unread-count.ts}`; rewire
  `components/layout/app-topbar.tsx` bell → server notifications + unread badge (keep the
  dashboard Recent-activity panel unchanged); profile preferences (`app/profile/notifications` or
  a card in `profile/page.tsx`, critical shown locked); `app/admin/notifications/page.tsx`
  (category defaults + reminder defaults tabs) + delivery-log view with filters + retry;
  `nav-config.ts` (Inbox self-service w/ unread `liveBadge`; admin Notification Settings +
  Delivery Log under Governance, permission-gated).
- **Rules:** tenant isolation (404 cross-tenant); own-row enforcement on read/archive.
- **Deps:** N3–N11. **Tests (backend):** inbox list/unread/mark-read/mark-all, preferences
  GET/PUT (critical rejected), admin settings, delivery log + retry, permission matrix,
  tenant 404. **Frontend:** `tsc --noEmit` + eslint (no JS test framework). **Size:** XL. **Risk:** M.

### N13 — Tests (consolidated — see "Tests required" below)
All written alongside each step; this step is the final green gate: full `mvn clean verify`
(surefire + failsafe) on `dms_phase2_it` @ 5435, concurrency suites 5×, frontend `tsc`+eslint.

---

## Tests required (N13)

Delivery & retry; final-failure path; **2 parallel dispatchers over same rows, no double-send,
5×**; tiers & preferences (critical not suppressible by user or tenant); user without email;
attempts table rejects UPDATE & DELETE (trigger, as owner); every migrated email still sent
with same content + recipients; access-aware content (with/without access, workflow-scoped);
each new trigger; due-date & inactivity reminders each fire once + escalation once; template
defaults vs stage overrides; `awaiting_info` skipped; tenant isolation (404); permission
matrix; audit entries for escalations (incl. skipped), final failures, setting changes, retry,
pref changes; migration up/down/re-apply. **Existing workflow/comment/scanning tests stay green.**
IT via failsafe (`*IT.java`), DB `dms_phase2_it` @ 5435.

---

## Requirement traceability

| Requirement | Covered by |
|---|---|
| N-FR-001 (events incl. status change, approvals, deadlines) | N8 (migrated workflow mails) + N10 (status/version/share) + N11 (deadlines) |
| N-FR-002 (3 channels incl. SMS) | **Partial (Deviation #3)** — email + in-app behind channel interface; SMS deferred |
| N-FR-003 (inactivity reminder, configurable threshold) | N11 |
| N-FR-004 (escalate after further period) | N11 |
| N-FR-005 (log channel/recipient/timestamp/outcome in audit trail) | N7 (attempts table + selective audit) |
| N-FR-006 (thresholds per workflow type/category) | N2 (template/stage cols) + N11 + G1/G2 (template and stage APIs and editor). **Covered per workflow type (the template, with per-stage overrides), not per document category** - a template belongs to one document type, so "per type" is the unit of configuration; there is no setting keyed by a category of document |
| N-NFR-001 (≥99% reliability, retry) | N4 (backoff retry) + N7 (failure logging) |
| N-NFR-002 (within minutes) | N4 (AFTER_COMMIT nudge + 30s poll) |
| N-NFR-003 (scalability, no loss) | N3 (tx write) + N4 (SKIP LOCKED + lease) |
| N-NFR-004 (configurable, no downtime) | N6 + N11 (DB-read config) + G1 (the scan resolves every threshold from the database on each run, so a new template version's settings apply with no restart) |
| N-NFR-005 (immutable audit) | N2+N7 (append-only trigger + REVOKE) |
| Alt flows (channel fail+retry; task done cancels reminder; no manager→admin; no contact→in-app+flag) | N4 (retry); N11 (fire-once/skip); N11+N8 (`escalation.failed`→admins); N6 (no-email→in-app+flag) |
| WA-FR-006 (route→approver notify) | N8 (`workflow.task.assigned`) |
| WA-FR-013 (no double action / unreached stage) | existing workflow guard (unchanged; notifications don't weaken it) |
| WA-NFR-003 (progress indicator usability) | existing workflow status panel (unchanged) |
| WA-NFR-004 (not lost if offline) | N3 (durable inbox) + N12 (bell inbox on next login) |
| DOT-FR-006 (ownership transfer) | N10 developer guide example (hook ready, not wired this phase) |
| OMD-FR-016 / SHAD-FR-009 / CRG-FR-006 | N10 developer guide examples (`module.info` category + guide) |
| VMSBDI-FR-003 (threat notify uploader+admins) | N8 (`security.threat.*`) |

---

## Risks & open questions

- **R-1 Migration-number collision** with `develop` (V82+). Flag at merge; renumber if needed.
- **R-2 Concurrency** (dispatcher + extended SLA scan). Mitigation: SKIP LOCKED + lease, scalar
  id lookups (no load-before-lock, per memory rule), run concurrency tests 5× after any change.
- **R-3 Content regression** migrating 23 emails to HTML. Mitigation: per-mail content tests
  assert subject + facts + link; existing module ITs must stay green.
- **R-4 Email reliability test infra** — need a test SMTP (GreenMail or Mailpit). Confirm during
  N5 which the repo already uses for mail ITs; default to GreenMail in tests.
- **Open Q (non-blocking):** profile preferences as a subpage vs a card in `profile/page.tsx`
  (plan assumes a card/subpage under `profile/`); and whether `module.info` should be one
  catch-all optional category or per-module — plan uses one catch-all + the developer guide.

---

## Deviations (recorded during execution)

| # | Decision | Rationale |
|---|---|---|
| 1 | Append-only = BEFORE UPDATE/DELETE trigger + REVOKE (not REVOKE-only) | REVOKE doesn't bind the postgres owner the tests run as; trigger makes "rejects UPDATE/DELETE" test pass + strongest immutability (user-approved) |
| 2 | All emails (incl. 23 migrated) rendered via one HTML template; "same content" tests assert key content not byte-identical | N5 locks HTML; preserves information/subject/link (user-approved) |
| 3 | Channels = email + in-app only; SMS deferred behind `NotificationChannelSender` | Team removed SMS (brief decision 2); N-FR-002 partially met, documented |
| 4 | Threat notifications stay AFTER_COMMIT + off-thread (not BEFORE_COMMIT like the rest) | A notification-write failure must never roll back a quarantine; preserves the pre-existing safe async path |
| 5 | Dropped the dedicated dispatcher executor bean; the nudge reuses `applicationTaskExecutor` | A custom `Executor` bean made Spring Boot back off the auto-configured `applicationTaskExecutor` that existing beans depend on |
| 6 | A mention for someone who can't open the document keeps the comment text and links to `/mentions` (not "no link") | Existing decision 10: a mentioned person is always entitled to read the comment. The document's title and any document link are still withheld |
| 7 | `document.status-changed` = the document owner hears of a workflow outcome (unless they started or caused it) | The system has no standalone document status; workflow outcomes are the status changes it has |
| 8 | "Can open" = `DocumentAccessService` (incl. workflow-scoped read), as the brief defines it; **mentions/replies additionally require `docm.documents.view`** (`requireViewPermission`) | Requiring the permission for everything broke an existing test (approvers holding only approve/reject must still see the document's name in "Approval needed") and the brief names no permission term; decision 10 (mentions) has always had one |
| 9 | Added a 30 s circuit-breaker to `UserPermissions.tenantAdminIds` (after a failed Keycloak lookup it returns the same empty set at once instead of waiting out another timeout) | Notifications are now written inside the user's transaction and the access check calls Keycloak; a slow/down Keycloak would stall every approve/submit. Result is identical to the failure path, only faster (suite Keycloak warnings 282 -> 2) |
| 10 | Test harness: `NotificationMailSupport` retires stale pending email rows before each test; `WorkflowApiSupport` now extends it (GreenMail instead of a mocked `JavaMailSender`) | The suite shares one DB and the dispatcher is off in tests, so rows pile up and the dispatcher claims the OLDEST first - a test would be buried under earlier runs' backlog |
| 11 | `DocumentAccessService`/`FolderAccessService` gained a trailing `ApplicationEventPublisher` constructor argument; their two unit tests pass one more mock | Needed to publish `AccessGrantedEvent` without the folder module depending on the notification module (bean cycle). Assertions unchanged |
| 12 | The scan now applies **all** the template defaults, not only the inactivity ones: the due-date reminder default (only when shorter than the stage's SLA) and the escalation target/user (a stage left at target `none` inherits the template's) | N11 wired only the inactivity thresholds, so a template default for the due-date reminder or the target would have been stored and silently ignored. The data model gives a stage no way to say "inherit" for the target other than `none`, so `none` means inherit - a stage cannot opt out of a template's default target (it can set its own) |
| 13 | A stage may now have an escalation target and no SLA | Escalation can follow an inactivity reminder, so it no longer needs a due time. The frontend rule "Escalation happens at the due time - set SLA hours" became "something must trigger it" (an SLA, or an inactivity reminder with an escalate-after time) |

---

## Open questions — ANSWERED by user at "start execution" (2026-10-14)

1. **Profile preferences placement** → **Separate page at `/profile/notifications`**, linked
   from the profile page AND the bell dropdown.
2. **`module.info`** → **One catch-all optional category for now.** The developer guide MUST
   tell other modules to add their own category when they plug in.
3. **Reminder defaults UI home** → **Confirmed:** template-level reminder/escalation defaults
   live in the existing workflow template editor; only category email defaults on the new
   Notification Settings page.
4. **Mail test harness** → **Yes, add GreenMail as a test-scope dependency only.**

**Additional user directives (recorded):**
- Deleting the old mailer classes is allowed **only if every email keeps its recipients,
  subject, key facts and link — verified by tests.** (Governs N8.)
- **Keep all existing Spring events.**
- **Never run git commands that change anything** (read-only git only; all work uncommitted).

---

## Progress log

### N2 — Migrations, entities, repositories, seeds, permissions, migration test ✅
- **Migrations:** `V82__create_notification_core.sql` (6 tables + 18-row category seed +
  append-only trigger/function + REVOKE), `V83__add_workflow_notification_config_columns.sql`
  (template/stage/task columns + backfill + inactivity index),
  `V84__seed_notification_permissions.sql` (NOTIF module/actions + 3 permission codes).
- **Rollbacks:** `rollback/V82,V83,V84__*.rollback.sql`.
- **Entities:** `notification/entity/{NotificationCategory,TenantNotificationSetting,
  UserNotificationPreference,Notification,NotificationDelivery,NotificationDeliveryAttempt}`;
  extended `workflow/entity/{WorkflowTemplate,WorkflowStage,WorkflowTask}` with the new fields.
- **Enums:** `notification/enums/{NotificationTier,NotificationChannel,DeliveryStatus,DeliveryOutcome}`.
- **Repositories:** `notification/repository/*` (6; attempts repo uses narrow `Repository` marker — no mutators).
- **Permissions:** added `NOTIF_*` constants to `security/rbac/catalog/PermissionCodes`.
- **Tests:** `test/.../notification/V82ToV84NotificationMigrationTest` (up: tables/seeds/CHECKs
  bite, append-only trigger rejects UPDATE+DELETE as owner, dedupe unique, workflow CHECKs;
  down: rollbacks reverse + re-apply). **Result: 2/2 green** on dms-workflow-pg:5435 (fresh dms_phase2_it).
- **Deviations:** #1 (trigger+REVOKE) applied. Fixed V84 rollback: `role_permissions` keys on
  `permission_id` (not `permission_code`) — matched the V81 rollback pattern.
- Backend `clean test-compile` green.

### N3 — NotificationService (transactional write) ✅
- **Files:** `notification/config/NotificationProperties`, `notification/service/{NotificationService,
  NotificationRequest,PreferenceResolver}`, `service/impl/NotificationServiceImpl`,
  `helper/{NotificationAttemptLog,NotificationNoEmailAuditor}`.
- **Behaviour:** joins caller's tx (`@Transactional`); one inbox row per recipient; in-app
  delivery created `sent` + logged; email delivery `pending` (pref on + has email) or `skipped`
  (`email_disabled`/`no_email`); dedupe idempotent; per-recipient content hook for N9.
- **Tests:** `NotificationServiceIT` (8): in-app+email-pending, dedupe, optional-off, critical
  cannot be disabled, standard user-off, tenant-default-on, no-email skip, tx rollback.
  **Result: 8/8 green.**
- Note: `NotificationNoEmailAuditor` logs for now; N7 adds the audit write. `PreferenceResolver`
  built here (also N6).

### N4 — Dispatcher (SKIP LOCKED, lease, backoff, final failure, per-instance switch) ✅
- **Files:** `dispatch/{DeliveryClaimRepository,NotificationDispatcher,NotificationDispatchScheduler,
  NotificationDispatchNudge,NotificationCreatedEvent}`; `helper/NotificationDeliveryAuditor` (logs; N7 adds audit).
- **Behaviour:** atomic claim `UPDATE ... FROM (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING`
  with lease; send outside DB tx; result written per-row; exponential backoff; final failure → failed + audited;
  `@Scheduled` poller + AFTER_COMMIT nudge both gated by `dms.notification.dispatcher.enabled` (per-instance switch).
- **Tests:** `NotificationDispatcherIT` (2): sends HTML email + logs attempt; retry→final-failure, in-app stands.
  `NotificationConcurrencyIT` (@RepeatedTest 5): two dispatchers, no double-send. **Result: Dispatcher 2/2, Concurrency 5/5 green.**
- **Deviation (minor):** dropped the dedicated executor bean (it made Spring Boot back off
  `applicationTaskExecutor`, breaking existing beans); the nudge reuses `applicationTaskExecutor`.
- Disabled the dispatcher in all test contexts via `src/test/resources/application.properties`.

### N5 — Channels behind one interface (email HTML + in-app) ✅
- **Files:** `channel/{NotificationChannelSender,InAppChannelSender}`,
  `channel/email/{NotificationMailRenderer,EmailChannelSender}`. GreenMail test dep added.
- **Behaviour:** `EmailChannelSender` sends MimeMessage (HTML + text alt) via the existing
  `JavaMailSender`; failures returned not thrown. Renderer keeps subject=title (key facts),
  body, absolute link button; escapes HTML. In-app sender is a success no-op.
- **Tests:** `NotificationMailRendererTest` (3). **Result: 3/3 green.**

### N6 — Tiers & preference resolution ✅ (core)
- `PreferenceResolver` (built in N3) + resolution verified by `NotificationServiceIT` (critical
  locked, standard user-off, optional default-off, tenant default, no-email). Tenant/user
  settings CRUD services + no-email audit come with N7/N12.

### N7 — Two-layer logging ✅
- **Attempts table:** append-only (trigger + REVOKE) proven by the V82–V84 migration test
  (rejects UPDATE+DELETE as owner); every attempt appended via `NotificationAttemptLog`.
- **Main audit (selective):** added catalog actions `NOTIFICATION_DELIVERY_FAILED /
  _RECIPIENT_NO_EMAIL / _SETTING_CHANGED / _PREFERENCE_CHANGED / _DELIVERY_RETRIED` (module NOTIF)
  + object types `NOTIFICATION_DELIVERY/_SETTING`; `NotificationDeliveryAuditor` writes them.
  Service calls `recipientNoEmail`, dispatcher calls `deliveryFailed` on final failure;
  setting/preference/retry wired for N12.
- **Tests:** audit assertions added to `NotificationServiceIT` (no-email) and
  `NotificationDispatcherIT` (final failure). Compiles; to be run in the N8+ gate.

### N8 — Migrate mailers onto the service (IN PROGRESS)
- Decision: notification listeners move to `@TransactionalEventListener(BEFORE_COMMIT)` so the
  inbox+delivery rows commit in the caller's tx (honors locked decision 3), keeping all events.
- Email transport changes plain-text `SimpleMailMessage` → HTML `MimeMessage` (Deviation #2), so
  the shared workflow mail test harness (`WorkflowApiSupport`, used by WorkflowPhase2NotificationIT,
  WorkflowScenariosIT, WorkflowAccessIT + the WorkflowPhase2MailerTest unit test) must switch from
  the mocked `JavaMailSender` to GreenMail, and `awaitMail` drives the dispatcher. Content strings
  (subjects, key facts, links) preserved per the email→category mapping.

### N8 — Migrate mailers onto the service ✅ (backend + harness)
- **Listeners → BEFORE_COMMIT, calling the service:** `WorkflowNotificationListener`,
  `WorkflowActivityNotificationListener`, `CommentNotificationListener` now write notifications
  in the triggering transaction (decision 3), keeping all existing Spring events. Mention
  `notified_at` set in-tx. `ThreatNotificationListener` kept AFTER_COMMIT + off-thread (so a
  notification write can never roll back a quarantine) — documented deviation.
- **Mailers rerouted through `NotificationService`:** `WorkflowNotificationMailer`,
  `CommentNotificationMailer`, `ThreatNotificationMailer`, `KeyRotationReminderMailer` (no
  longer touch `JavaMailSender`; only `EmailChannelSender` and the untouched `PasswordResetService`
  do). Each email → its category/tier; subject=title, key facts in body, link preserved;
  mention link gated by access. Old `workflow/helper/NotificationDispatcher` deleted.
- **Test harness:** `WorkflowApiSupport` now extends `NotificationMailSupport` (GreenMail) instead
  of mocking `JavaMailSender`; `awaitMail` drives `NotificationDispatcher.runOnce()` and returns a
  `CapturedMail` with the same getters. Updated `WorkflowPhase2NotificationIT`, `WorkflowScenariosIT`,
  `WorkflowAccessIT`; rewrote `WorkflowPhase2MailerTest` to capture the `NotificationRequest`
  (content preservation at unit level).
- **Result:** WorkflowPhase2MailerTest 7/7, WorkflowAccessIT 10/10, WorkflowPhase2NotificationIT 5/5 green.
- **Deviation #4:** threat notifications stay AFTER_COMMIT/off-thread (safety: never risk the quarantine).

### N11 — Inactivity reminder + post-reminder escalation ✅
- **Scheduler:** extended `WorkflowSlaRepository.FIND_DUE_SQL` with inactivity reminder +
  escalation branches (thresholds resolved in SQL: stage value else template default — carried
  on `DueTask` to avoid a stale L2-cached template entity shadowing a fresh default).
  `WorkflowSlaServiceImpl.process()` adds the two branches + `remindInactivity()`; escalation
  fires once (shared `escalated_at`), each reminder once (separate stamps); `awaiting_info` skipped.
- **Activity resets:** `last_activity_at` stamped on claim (repo UPDATE), delegate/reassign
  (`WorkflowDelegationServiceImpl`), and info-answer resume (`WorkflowEngine.resume`).
- **Bugs fixed:** `escalate()`/`remind()` NPE'd on a null `due_at` (inactivity-only task) →
  guarded; `notifyReminder` early-returned on null `due_at` so inactivity reminders made no
  notification, and `sendReminder` NPE'd formatting a null due date → both handle null now.
- **Tests:** `WorkflowInactivityReminderIT` (4): reminder fires once, escalation fires once,
  stage value overrides template default, awaiting_info skipped. **Result: 4/4 green.**
  WorkflowPhase2NotificationIT still 5/5 (no due-date SLA regression).
- Admin UI fields for the template defaults: deferred to N12 frontend.

### N12 — APIs (backend) ✅
- **DTOs:** `notification/dto/{NotificationView,UnreadCountResponse,PreferenceView,UpdatePreferenceRequest,
  TenantSettingView,UpdateTenantSettingRequest,DeliveryLogView}`.
- **Services:** `NotificationInboxService(+Impl)` (self inbox + preferences),
  `NotificationAdminService(+Impl)` (tenant settings + delivery log + retry); `NotificationConflictException` (409).
- **Controllers:** `NotificationInboxController` (`/api/v1/notifications**`, self),
  `NotificationAdminController` (`/api/v1/admin/notifications**`, permission-gated).
- **Tests:** `NotificationApiIT` (5): inbox/unread/read/read-all, preferences toggle + critical 409,
  tenant isolation 404, admin settings + 403 gating, delivery log + 403. **Result: 5/5 green.**
- **Frontend client:** `frontend/lib/notifications/api.ts` (types + calls). UI pages pending.

### N12 — Frontend ✅
- **Client + hooks:** `frontend/lib/notifications/{api.ts,use-notifications.ts}` (inbox, unread,
  mark read/all, archive, preferences, admin settings, delivery log, retry; react-query).
- **Pages:** `app/inbox/page.tsx` (inbox list, unread filter, mark-all-read, archive),
  `app/profile/notifications/page.tsx` (email preferences, critical shown locked),
  `app/admin/notifications/page.tsx` (category defaults + delivery log with status filter + retry).
- **Bell → real inbox:** `components/layout/app-topbar.tsx` now shows server notifications + a
  numeric unread badge (localStorage activity-dot removed); dropdown links to the inbox and to
  preferences. The dashboard Recent-activity panel is untouched.
- **Nav + badge:** `nav-config.ts` adds Inbox (self-service, live unread badge) + admin
  Notification Settings (Governance); `app-sidebar.tsx`/`types.ts` wire `myUnreadNotifications`;
  `permission-codes.ts` adds the NOTIF codes. Profile page links to preferences.
- **Checks:** `bun run typecheck` — only 1 pre-existing error (stale `.next` type for a
  commented-out admin page, unrelated); `eslint` on all changed files — clean.

### N9 — Access-aware content ✅
- **Mechanism (central, in the service):** `NotificationRequest` gained `documentId`, `neutralTitle`,
  `neutralBody`, `neutralLinkUrl`. When `documentId` is set, `NotificationServiceImpl` asks
  `NotificationAccessResolver` (→ `DocumentAccessService.forUsers` + `DocumentAccessView.canSee`, so workflow-scoped
  read counts) once for all recipients; those who can't open the document get the neutral title/body/link and the
  inbox entry carries no reference to the document (object type/id dropped).
- **The rule (Deviation #8):** "can open" is `DocumentAccessService.forUsers(...).canSee(document)` as the brief defines it.
  `NotificationRequest.requireViewPermission` (off by default) also requires `docm.documents.view`; mentions and replies
  set it, keeping decision 10's "permission AND access". Turning it on for everything broke an existing test.
- **Flush before the check:** the workflow-scoped check reads through plain JDBC, so the resolver flushes first —
  otherwise an approver assigned in the same transaction would be judged to have no access.
- **One check per event, not per recipient:** `UserPermissions.tenantAdminIds` is a live, uncached Keycloak call, so the
  workflow mailer methods now take the recipients of one event (`Collection<User>`) and make ONE `notify()` call.
- **Applied to:** every workflow notification (all name the document), mention, reply, new-version, status-changed.
  Threat/key-rotation don't reference a document; `document.shared` needs no check (being on the list is what lets the
  recipient open it).
- **Bug found & fixed (my N8 work):** the migrated mailers used fixed dedupe keys (`wf-assigned:<documentId>`,
  `wf-moved:<kind>:<userId>`…), so the unique index would have silently dropped a resubmission's or a second
  delegation's notification — the old mailers sent every time. Event-driven workflow/comment notifications now use no
  dedupe key (each event fires once per transaction). Threat keys (unique per quarantine) are kept.
- **Deviation #6:** a mention for someone without access keeps the comment text and links to `/mentions` (existing
  decision 10 — they are always entitled to read the comment) instead of "no link"; the document's title and any
  document link are still withheld.

### N10 — New triggers ✅
- **document.shared:** `folder/AccessGrantedEvent` published by `DocumentAccessService.updateAccess` and
  `FolderAccessService.updateAccess` (only newly added principals; a level change is not a new share); handled by
  `notification/listener/AccessGrantedNotificationListener` (BEFORE_COMMIT; roles expand to members via the new
  `UserRoleRepository.findActiveUserIdsByRoleIds`; the sharer is excluded). An event, not a call, so the folder module
  doesn't depend on the notification module (avoids a bean cycle). Two existing unit tests build these services by
  hand — each got one trailing constructor argument; assertions unchanged.
- **workflow.new-version:** `WorkflowNewVersionNotifier`, called from `DocumentVersionChangeListener.flag()` (the
  approval continues on its snapshot): the holders of the current stage's open tasks, minus the uploader. A restart
  re-creates tasks, whose assignment notifications already cover it.
- **document.status-changed:** there is no standalone document status in the system, so this is the owner hearing of
  an approval outcome (unless they started or caused it). **Deviation #7.** Optional tier.
- **Approaching deadlines:** the existing due-date reminder (N11 added the inactivity one).
- **Developer guide:** `docs/notification-developer-guide.md` — how to add a category and call the service, the
  access-aware fields, batching recipients per event, the dedupe-key rule, and "other modules add their own category;
  `module.info` is only the catch-all".
- **Tests:** `NotificationTriggersIT` (8): access-aware per recipient (restricted doc), the view-permission flag,
  workflow-scoped reader gets the title+link *without* the view permission, mention without access keeps the comment
  but not the title/link, status-changed to the owner (in-app only, not to the initiator), new-version to the current
  holders (not the uploader), document share (new people only; a level change isn't a share; a role expands to its
  members; the sharer isn't told), folder share. `WorkflowPhase2MailerTest` now 12 (neutral variants, one call per
  event, no dedupe key). **8/8 and 12/12 green.**

### N13 — Final gate ✅
- **`mvn clean verify` (surefire + failsafe)** on `dms-workflow-pg:5435` / fresh `dms_phase2_it`:
  **surefire 420 tests, 0 failures; failsafe 254 tests, 0 failures, 7 skipped** (the OpenBao Transit ITs that skip
  themselves without OpenBao). BUILD SUCCESS. Existing workflow, comment, scanning, folder and encryption tests all green.
- **Concurrency, 5 separate runs** of `WorkflowConcurrencyIT` + `NotificationConcurrencyIT` +
  `WorkflowInactivityReminderIT`: 69 tests each, 0 failures in all 5 (the dispatcher "two instances, no double send"
  test additionally repeats 5x inside every run = 25 repeats).
- **Frontend:** `bun run typecheck` - 1 error, pre-existing and unrelated (a stale generated `.next/dev/types`
  reference to a commented-out admin page); `eslint` on every new/changed file - clean.
- **Rebuilt the running app** (`docker compose up -d --build dms-core frontend`): Flyway applied V82-V84 to
  `dms_core_db` ("now at version v84"), `/actuator/health` UP, `/api/v1/notifications/**` and
  `/api/v1/admin/notifications/**` routed and secured (401 unauthenticated), no dispatcher errors in the log.
- **No git command that changes anything was run;** everything is uncommitted on `feature/chamath/workflow-notification`.

### G1–G4 — Closing the gaps (second pass) ✅

**G1 — Backend: reminder/escalation settings on the template and stage APIs**
- **Requests/responses:** `Create/UpdateWorkflowTemplateRequest` accept `defaultReminderBeforeHours, defaultInactivityReminderHours,
  defaultEscalateAfterReminderHours, defaultEscalationTarget, defaultEscalationUserId`; `UpsertWorkflowStageRequest` accepts
  `inactivityReminderHours, escalateAfterReminderHours` (null = inherit). `WorkflowTemplateResponse` / `WorkflowStageResponse` return them.
  A template update replaces the defaults as a whole (leaving one out clears it); a target other than `named_user` drops the user.
- **Validation (`WorkflowTemplateServiceImpl`):** positive whole hours (bean validation, 400); escalate-after only with an inactivity
  reminder - at template level, and at stage level against what the stage *inherits* (422 `WORKFLOW_INVALID_STAGE` /
  `WORKFLOW_INVALID_TEMPLATE_DEFAULTS`); `named_user` needs an active user in the tenant (`WORKFLOW_INVALID_TEMPLATE_DEFAULTS` /
  `WORKFLOW_INVALID_ASSIGNEE`); the template's inactivity default cannot be removed from under a stage that inherits it and sets its own
  escalate-after; activation re-checks it. A new version copies the five defaults and both stage overrides. The audit snapshot carries the
  defaults, so a change shows as a before/after pair.
- **The defaults take effect (Deviation 12):** `WorkflowSlaRepository.FIND_DUE_SQL` resolves every effective setting in SQL (stage value, else template
  default) and `DueTask` carries them; `WorkflowSlaServiceImpl.process` and `EscalationTargetResolver.resolve(target, namedUser, …)` use them.
  The stage entity is no longer loaded by the scan.

**G2 — Frontend:** `lib/workflow/api.ts` types; `components/workflow/{schema.ts, reminder-fields.tsx, stage-card.tsx, stage-builder.tsx,
workflow-template-form.tsx}` and the two template pages. The Workflow Templates editor has a "Reminder & escalation defaults" section
(both create and edit); the stage card has override controls where "Inherit from template" is the default, "Set for this stage" reveals the hours,
and it shows the inherited value - plus the escalate-to control. Short helper text explains due-date vs inactivity reminders. The read-only
template view and the stage summary show the effective values. A stage's own schema is built from the template's defaults so it is checked
against what it will actually inherit (Deviation 13).

**G3 — Tests:** `WorkflowReminderSettingsIT` (13): template defaults saved, read back, replaced and cleared; stage overrides saved and `null` = inherit;
non-positive hours -> 400; escalate-after without inactivity (template and stage, own or inherited) -> 422; named_user needs an active
same-tenant user; the template inactivity default cannot be removed from under an inheriting stage; a new version copies defaults and
overrides; and four end-to-end cases where a stage with only template defaults gets its inactivity reminder, escalates to the template's
target after it, keeps a target of its own over the template's, and inherits the due-date reminder only when shorter than its SLA.
Existing template, SLA, inactivity, notification and scenario ITs stay green (62/62 in that run).

**G4 — Docs:** new `docs/events/notification-audit-events.md` (both layers, the five `NOTIF` actions and their fields);
`docs/events/workflow-audit-events.md` updated (template snapshot, stage overrides, inactivity `WORKFLOW_TASK_REMINDER_SENT`, `reminded`).
Traceability: N-FR-006 is covered per workflow type, not per document category.

**Environment note:** the disposable test DB container was stopped and the local dev encryption key file in the macOS temp directory had been
cleared (the app refuses to start when encrypted objects exist without its key), so `dms_phase2_it` was recreated fresh on port 5435 as the brief
allows. The dev DB on 5433 was not touched by any test.

