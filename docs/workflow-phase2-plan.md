# Oak DMS · 3.1 Workflow Automation · Phase 2: development plan


## Context

Phase 1 (`docs/workflow-phase1-plan.md`) delivered the core approval engine: templates, runtime, approve and reject with per-stage rules, My Tasks, status, audit and core mail. It is merged and has been tested by hand. Phase 2 adds:
- delegation and reassignment
- SLA reminders and escalation
- threaded comments with @mentions
- formal information requests that pause one approver's task
- supporting documents
- the notifications, API extensions and UI for all of the above

It also first fixes four issues found in manual testing (S0).

Out of scope:
- condition-based routing
- branching and join semantics
- a general notification module (3.2)
- push or websocket updates
- per-document ACLs or sharing (recorded as future item F1)

`J` = `backend/dms-core/src/main/java/com/oak/dms`, `T` = `backend/dms-core/src/test/java/com/oak/dms`, `M` = `backend/dms-core/src/main/resources/db/migration`, `R` = `…/db/rollback`, `F` = `frontend`. Every API path starts with `/api/v1`.

### Answers recorded in Q&A (in addition to the 25 locked decisions)

| # | Topic | Decision |
|---|---|---|
| A1 | `department_head` target | A department-pool task goes to **that department's head**. A user or role task goes to **the head of the initiator's department**. |
| A2 | Unusable escalation target | The target is unusable if it is missing, inactive, the initiator, the current holder, has already decided in this stage, or lacks approve/reject permission. In that case: set `escalated_at`, leave `escalated_to = NULL`, and record an `escalated` action with `detail.skipped = <reason>`. Mail the holders of `wa.reassign-workflow-task` and the tenant admins. It fires **once** and is never retried. |
| A3 | Re-delegation | **Allowed.** The current holder (including a delegate) may delegate again. `delegated_from` keeps the **original** holder, and each hop is a `delegated` action. Delegating back to the original holder clears the delegation. |
| A4 | Pool delegation | **Claimed pool tasks only, and only by the claimer.** An unclaimed pool task must be claimed first. `claimed_by` stays as the claimer, and `delegated_to` takes precedence over it. |
| A5 | Mention format | A **stored user-id token**: `@[Display Name](user:<uuid>)`, inserted by autocomplete and rendered as a chip. The server parses and validates the ids. |
| A6 | Reminder for unclaimed pool tasks | Goes to **every eligible live pool member**: holds approve/reject permission, is not the initiator, and has not already decided in this stage. |
| A7 | Delegation columns | `assignee_type`/`assignee_id` **stay unchanged** as the entry snapshot. `delegated_from` = the original holder, `delegated_to` = the delegate. `delegated_to` decides who may act. |
| A8 | Respond permission | **No new code.** Only the `requested_from` user may respond or decline. The new codes are just `wa.request-workflow-information.create` and `docm.document-comments.create`. |
| A9 | Missing timeline types | **Widen `ck_workflow_actions_action_type`** (V48) to add `supporting_document_added`, `supporting_document_removed` and `info_reassigned`. This only widens the CHECK and is the single exception to decision 25. |
| A10 | Uploading a new supporting document | **Reuse** the existing `POST /documents` (needs `docm.documents.create` and a type). The UI polls the ingestion ticket on a 202, then links the clean version with `source = uploaded_new`. No ingestion changes. |
| A11 | Who can be asked for information | Already settled by decision 15: any active user in the tenant other than the requester, including the initiator and users without document access. |

---

## 1. Findings from exploration

### Confirmed facts (all verified in code)
- **No per-document access control.** `J/sharing/` contains only `.gitkeep`, and no migration creates a share, ACL or permission table. Viewing a document means holding `docm.documents.view` plus tenant scoping (`DocumentController.java:63-73`, `DocumentServiceImpl.java:152-155`). Nothing checks the owner.
- **`TaskStatus.OPEN = Set.of(pending, claimed)`** (`J/workflow/enums/TaskStatus.java`). The plan doc called it `OPEN_STATUSES`; the code calls it `OPEN`.
- **`workflow_tasks` has `escalated_at` but no `escalated_to`.** `delegated_from`, `delegated_to`, `delegated_at` and `reminder_sent_at` exist (V45:130-140). `sla_paused_*`, `allow_info_requests` and `info_request_id` are absent.
- **The next migration is V47.** V46 is the last one (V35 is missing).
- **`ImportTemplateRoundTripTest.csvTemplate_carriesMarkerAndGuidanceLines_andReadsBack` fails on HEAD.**
  - Cause: `ImportTemplateWriter.java:242-270` writes the `#` lines with `printer.printRecord` on `CSVFormat.DEFAULT`, and commons-csv in MINIMAL quote mode quotes any value whose first character is `<= '#'`.
  - Evidence: the surefire report in `target/` shows `﻿"# dms-import-template …"`.
- The Phase 2 columns `reminder_before_hours`, `escalation_target` and `escalation_user_id` are stored and validated (`WorkflowTemplateServiceImpl.validateStage:461-482`). The Phase 1 UI never exposes them; `stage-card.tsx:55-71` passes them through untouched. **So every existing stage is `none`/NULL, and turning on the scheduler causes no burst of mail.**

### Where the Phase 1 compatibility check (§8) was optimistic
1. **"Add `OR delegated_to = :me`" is not a single clause.** Eligibility is duplicated in about 8 places:
   - `ApproverResolverImpl.canAct`/`isPoolMember`
   - the `findMyTasks` JPQL **and** its separate `countQuery` (`WorkflowTaskRepository:89-133`, two copies of the predicate)
   - `WorkflowQueryServiceImpl.requireVisible` and `myOpenTaskId`
   - the claim UPDATE (`:61-65`)
   - `act()` lines 149 and 158
   - participant-based `resolve` for mail recipients (`WorkflowNotificationListener:150`)

   Mail is resolved from the **participant**, not the task row, so it would ignore a delegate or an escalation route. **Fix:** one `TaskRouting` helper in Java, and one shared JPQL predicate constant used by both queries.
2. **Extending `TaskStatus.OPEN` alone opens a security hole.**
   - `canAct` checks `claimedBy` only when `status == claimed` (`ApproverResolverImpl:68`), and `act()` line 149 does the same.
   - A claimed task moved to `awaiting_info` would fall through to `isPoolMember`, so **any pool member could decide it**.
   - Nothing blocks a decision on an `awaiting_info` task.
   - The My Tasks JPQL hard-codes `in (pending, claimed)` (`:93`, `:112`), so `awaiting_info` tasks would vanish from every view.
   - The claim UPDATE requires `pending` and gives a misleading `WORKFLOW_TASK_CLOSED`.
   - `release()` doesn't check the state of the stage or instance.
3. **The DB doesn't constrain `awaiting_info`.** `ck_workflow_tasks_claimed` only covers `status='claimed'`, and the V43–46 migration test even sets `awaiting_info` on an unclaimed pool task. V47 adds a CHECK.
4. **§8 said the scheduler writes `expired`. Decision 6 supersedes that:** escalation adds a route and `expired` is never written. That is also necessary, because `StageOutcomeEvaluator` doesn't count expired entries, so under `wait_for_all` a stage could never conclude.
5. **There are two `due_at` columns.** Task `due_at` and stage `due_at` are both stamped at activation (`WorkflowEngine:99,107,121`). The SLA pause is per approver, so **only the task's `due_at` moves**, and the scheduler works on the task's `due_at`. The stage's `due_at` stays as a fixed stage-level indicator.
6. **Indexes that hard-code statuses:** `ix_workflow_tasks_open_assignee … WHERE status IN ('pending','claimed')` and `ix_workflow_tasks_claimed_by … WHERE status = 'claimed'`. You can't `ALTER` an index predicate, so V47 drops and re-creates them. This is index-only and doesn't touch the table.
7. **`department_head` has no source in the code.** `departments.head_user_id` is only stored and validated (`DepartmentServiceImpl`). This is resolved by A1.
8. **The workflow `ck_workflow_actions_action_type` has no value for standalone supporting-document or info-reassign events.** Resolved by A9.
9. **There is no reusable "does user X hold permission P".** `AuthorizationService` only knows the current caller. `RolePermissionRepository.findEffectivePermissionCodes(userId, tenantId)` uses DB grants only, and tenant admins come from Keycloak (`KeycloakUserService.getPlatformRealmRoles`). Mention access, delegate eligibility and escalation targets all need this, so it becomes a new helper (§9).
10. **No user picker is available to ordinary users.** `/workflow/lookups/users` is class-gated to template designers and department managers (`WorkflowLookupController:36-41`), and `UserRepository` has no search. A new people lookup is needed (S6).
11. **Overdue counting is capped.** `/tasks/my/count` counts overdue tasks in Java over at most 1000 rows (`WorkflowQueryServiceImpl:199-208`). Since S3 edits the query anyway, it moves into SQL there.

### Infrastructure to reuse
- **Mail after commit.** Copy the pattern in `WorkflowNotificationListener` (`@TransactionalEventListener(AFTER_COMMIT)`, then `@Qualifier("applicationTaskExecutor")`, with one try/catch per recipient through `send(Runnable)`) and in `WorkflowNotificationMailer` (`SimpleMailMessage`, links from `app.frontend-base-url`).
  - The tenant-admin lookup is duplicated today in `ThreatNotificationListener:125-147` and `WorkflowNotificationListener:183-194`.
  - It moves into a workflow-local `WorkflowRecipients` helper. Scanning is left untouched.
- **Schedulers.**
  - `@EnableScheduling` is on `DmsCoreApplication:17`. There is no ShedLock and no `spring.task.scheduling` config, so there is **one scheduler thread** shared by `ScanRetryScheduler`, `OutboxRelay` and two `BulkImportReconciler` jobs.
  - `ScanRetryScheduler` uses a `TransactionTemplate`, one short transaction per unit of work, and never binds `TenantContext`. Its audit calls pass `.tenantId(…)`, and the actor falls back to SYSTEM (`AuditService.resolveActor:157-168`).
- **SKIP LOCKED.** The only instance is `ScanResultRepositoryImpl:18-41`: JdbcTemplate `update … where id in (select … for update skip locked) returning`, with a lease column. Ownership is re-checked under `lockById`.
  - `OutboxRelay` is *not* safe across instances.
  - `BulkImportReconciler` is idempotent through a batch lock plus a status re-check.
- **Locks.** `WorkflowLocks.applyLockTimeout()` (`set_config('lock_timeout','5s',true)`, MANDATORY). A timeout surfaces as `PessimisticLockingFailureException` → 409 `CONCURRENT_UPDATE`. Per the memory note, fetch parent ids as scalars and never load an entity before its `FOR UPDATE`.
- **Upload path.**
  - `POST /documents` (multipart, `docm.documents.create`) → `IngestionGateImpl.submit`.
  - Responses: 201 clean with `documentId`/`versionId`; 202 while a cloud scan is pending, then poll `GET /ingestion-tickets/{id}` (uploader only); 422 if infected.
  - No document row exists until the file is clean.
- **Frontend.**
  - The document page is `F/app/documents/[documentId]/page.tsx`: a `Panel` "Document", the `WorkflowStatusPanel` and a "Workflow History" panel.
  - The status page is `F/app/workflows/instances/[instanceId]/page.tsx`: the panel plus `WorkflowTimeline`.
  - My Tasks is `F/app/tasks/tasks-content.tsx`, with a hand-rolled tablist using the `VIEWS` constant, and `F/components/workflow/my-tasks-table.tsx`.
  - Polling is every 15 s (`use-my-tasks`, `use-my-task-count`, `use-workflow-instance`); the timeline isn't polled.
  - Every workflow DTO type is in `F/lib/workflow/api.ts`. The labels are in `workflow-badges.ts`, and both maps are typed `Record<WorkflowActionType,…>`, so `tsc` forces new entries.
  - Dialogs use `AlertDialog`; there is also `Sheet`. `@base-ui/react` ships `autocomplete`/`popover`/`combobox`, which nothing uses yet, and there is no mention library.

### ERD vs the real schema: mismatches and how they are resolved
| # | Spec | Reality | Resolution |
|---|---|---|---|
| N1 | `document_version_id` FK | The PK is `document_versions.version_id`; the tenant-safe key is `uq_document_versions_tenant_version` | `(tenant_id, document_version_id) → document_versions (tenant_id, version_id)`, the same as V45 |
| N2 | `COMMENT_MENTIONS` has only `created_at` | The repo convention is the full audit block (M8) | Keep the spec. The table is insert-only, like `workflow_actions`. `notified_at` is the one later update, done by the mail listener |
| N3 | Actor FKs (`requested_by`, `author_user_id`, `escalated_to`, …) | `users` has no `(tenant_id, user_id)` key (M10) | Plain `REFERENCES users (user_id)`; the tenant is checked in the service |
| N4 | `workflow_info_requests.responded_at` on cancel | A cancel isn't a response | `responded_*` is set only for `answered`/`declined`. A cancel uses `cancel_reason` plus `updated_at`/`updated_by` |
| N5 | Info request ↔ task ↔ stage consistency | Composite FKs check tenant only | The service checks `task.stage_instance_id = stage_instance_id` and the instance |
| N6 | Supporting-document version belongs to the document | Composite FKs check tenant only | The service checks `version.documentId = documentId`. A version of the document under approval is rejected (422) |
| N7 | `action_type` lacks three values | — | A9: widen the CHECK |
| N8 | `delegated_from` NOT NULL with `delegated_to`? | Admin reassignment of an unclaimed or parked pool task has no previous holder | `delegated_from` is nullable when `delegated_to` is set; NULL means "reassigned from the pool" |

---

## 2. Migration plan (in order)

Each migration gets `R/V<n>__<desc>.rollback.sql` and is covered by `T/workflow/V47ToV50WorkflowPhase2MigrationTest`. That test follows the V41/V43 pattern: migrate a scratch DB to 46, then 50, assert, run the rollbacks in reverse, then re-migrate. `V43ToV46WorkflowMigrationTest` pins its target to 46 and is unaffected.

### V47__workflow_phase2_task_and_stage_columns.sql
```sql
ALTER TABLE workflow_stages ADD COLUMN allow_info_requests BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE workflow_tasks
    ADD COLUMN sla_paused_at      TIMESTAMPTZ,
    ADD COLUMN sla_paused_seconds INT NOT NULL DEFAULT 0,
    ADD COLUMN escalated_to       UUID;
ALTER TABLE workflow_tasks
    ADD CONSTRAINT fk_workflow_tasks_escalated_to FOREIGN KEY (escalated_to) REFERENCES users (user_id),
    ADD CONSTRAINT ck_workflow_tasks_sla_paused_seconds CHECK (sla_paused_seconds >= 0),
    -- the clock is paused only while waiting on information
    ADD CONSTRAINT ck_workflow_tasks_sla_paused_at CHECK (sla_paused_at IS NULL OR status = 'awaiting_info'),
    -- decision 12: a pool task is claimed by whoever raised the request
    ADD CONSTRAINT ck_workflow_tasks_awaiting_info_holder
        CHECK (status <> 'awaiting_info' OR assignee_type = 'user' OR claimed_by IS NOT NULL),
    ADD CONSTRAINT ck_workflow_tasks_delegation
        CHECK ((delegated_to IS NULL) = (delegated_at IS NULL)
               AND (delegated_from IS NULL OR delegated_to IS NOT NULL)
               AND (delegated_from IS NULL OR delegated_from <> delegated_to)),
    ADD CONSTRAINT ck_workflow_tasks_escalation CHECK (escalated_to IS NULL OR escalated_at IS NOT NULL);

-- decision 20: awaiting_info is open. Index predicates can't be altered, so recreate them.
DROP INDEX ix_workflow_tasks_open_assignee;
CREATE INDEX ix_workflow_tasks_open_assignee ON workflow_tasks (tenant_id, assignee_type, assignee_id)
    WHERE status IN ('pending','claimed','awaiting_info');
DROP INDEX ix_workflow_tasks_claimed_by;
CREATE INDEX ix_workflow_tasks_claimed_by ON workflow_tasks (tenant_id, claimed_by)
    WHERE status IN ('claimed','awaiting_info');
CREATE INDEX ix_workflow_tasks_delegated_to ON workflow_tasks (tenant_id, delegated_to)
    WHERE delegated_to IS NOT NULL AND status IN ('pending','claimed','awaiting_info');
CREATE INDEX ix_workflow_tasks_escalated_to ON workflow_tasks (tenant_id, escalated_to)
    WHERE escalated_to IS NOT NULL AND status IN ('pending','claimed','awaiting_info');
-- SLA scan (S4); awaiting_info deliberately excluded (decision 8)
CREATE INDEX ix_workflow_tasks_sla_due ON workflow_tasks (due_at)
    WHERE status IN ('pending','claimed') AND due_at IS NOT NULL
      AND (reminder_sent_at IS NULL OR escalated_at IS NULL);
```
The rollback drops the new indexes, re-creates the two original ones with their V45 predicates, drops the constraints, and drops the four columns.

**Review of hard-coded `('pending','claimed')` usages (decision 20):**

| Location | Change |
|---|---|
| `ix_workflow_tasks_open_assignee` | Recreated to include `awaiting_info` (above) |
| `ix_workflow_tasks_claimed_by` | Recreated as `claimed, awaiting_info` |
| `uq_workflow_tasks_one_decision_per_user` (`approved, rejected`) | Unchanged (decided statuses only) |
| `ix_workflow_stage_instances_due` | Unchanged and no longer the SLA driver; superseded by `ix_workflow_tasks_sla_due` |
| `WorkflowTaskRepository:93,112` | `t.status in :openStatuses`, bound to `TaskStatus.OPEN` |
| `WorkflowTaskRepository:99,118` | `t.status in :heldStatuses` (claimed or awaiting_info) `and t.claimedBy = :userId and t.delegatedTo is null` |
| `WorkflowTaskRepository:100,119` | Pool branch stays `status = pending` (only pending pool tasks are claimable), plus `and t.delegatedTo is null` |
| `WorkflowTaskRepository:61-65` (claim) | Unchanged predicate (`pending`, pool); delegation is impossible on an unclaimed pool task (A4) |
| `WorkflowTaskRepository:73-76` (release) | Adds `and t.delegatedTo is null` |
| `WorkflowTaskServiceImpl:75,84,149,158` | Go through `TaskRouting` (§5) |
| `ApproverResolverImpl:68` | `canAct` → `TaskRouting.canAct` (§5) |
| `WorkflowQueryServiceImpl:268` | `claimedByMe` = `claimedBy == me && status ∈ HELD` |
| `T/workflow/WorkflowConcurrencyIT.assertInvariants` | `status in ('pending','claimed','awaiting_info')` |

`TaskStatus` gains `OPEN = {pending, claimed, awaiting_info}`, `HELD = {claimed, awaiting_info}`, `DECIDABLE = {pending, claimed}` and `DECIDED = {approved, rejected}`. `WorkflowTaskServiceImpl.DECIDED` moves into the enum.

### V48__create_workflow_info_requests_and_supporting_documents.sql
```sql
CREATE TABLE workflow_info_requests (
    info_request_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants (tenant_id),
    instance_id       UUID NOT NULL,
    stage_instance_id UUID NOT NULL,
    task_id           UUID NOT NULL,
    requested_by      UUID NOT NULL REFERENCES users (user_id),
    requested_from    UUID NOT NULL REFERENCES users (user_id),
    message           VARCHAR(2000) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'open',
    response_message  VARCHAR(2000),
    decline_reason    VARCHAR(2000),
    cancel_reason     VARCHAR(2000),
    due_at            TIMESTAMPTZ,
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at      TIMESTAMPTZ,
    responded_by      UUID REFERENCES users (user_id),
    created_by UUID REFERENCES users (user_id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id), updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false, deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_workflow_info_requests_tenant_request UNIQUE (tenant_id, info_request_id),
    CONSTRAINT fk_workflow_info_requests_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES workflow_instances (tenant_id, instance_id),
    CONSTRAINT fk_workflow_info_requests_stage_instance FOREIGN KEY (tenant_id, stage_instance_id)
        REFERENCES workflow_stage_instances (tenant_id, stage_instance_id),
    CONSTRAINT fk_workflow_info_requests_task FOREIGN KEY (tenant_id, task_id)
        REFERENCES workflow_tasks (tenant_id, task_id),
    CONSTRAINT ck_workflow_info_requests_status CHECK (status IN ('open','answered','declined','cancelled')),
    CONSTRAINT ck_workflow_info_requests_message CHECK (length(btrim(message)) > 0),
    CONSTRAINT ck_workflow_info_requests_not_self CHECK (requested_from <> requested_by),
    CONSTRAINT ck_workflow_info_requests_responded
        CHECK ((status IN ('answered','declined')) = (responded_at IS NOT NULL AND responded_by IS NOT NULL)),
    CONSTRAINT ck_workflow_info_requests_declined CHECK (status <> 'declined' OR length(btrim(decline_reason)) > 0),
    CONSTRAINT ck_workflow_info_requests_cancelled CHECK (status <> 'cancelled' OR length(btrim(cancel_reason)) > 0)
);
CREATE INDEX ix_workflow_info_requests_tenant_id ON workflow_info_requests (tenant_id);
CREATE INDEX ix_workflow_info_requests_task_open ON workflow_info_requests (task_id)
    WHERE status = 'open' AND is_deleted = false;
CREATE INDEX ix_workflow_info_requests_requested_from ON workflow_info_requests (tenant_id, requested_from, status)
    WHERE is_deleted = false;
CREATE INDEX ix_workflow_info_requests_instance ON workflow_info_requests (instance_id, requested_at);

CREATE TABLE workflow_supporting_documents (
    supporting_document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    instance_id         UUID NOT NULL,
    info_request_id     UUID,
    document_id         UUID NOT NULL,
    document_version_id UUID NOT NULL,
    source              VARCHAR(20) NOT NULL,
    added_by            UUID NOT NULL REFERENCES users (user_id),
    access_mode         VARCHAR(30) NOT NULL DEFAULT 'already_accessible',
    access_revoked_at   TIMESTAMPTZ,
    note                VARCHAR(2000),
    added_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    <audit block, verbatim as above>,
    CONSTRAINT uq_workflow_supporting_documents_tenant_sd UNIQUE (tenant_id, supporting_document_id),
    CONSTRAINT fk_workflow_supporting_documents_instance FOREIGN KEY (tenant_id, instance_id)
        REFERENCES workflow_instances (tenant_id, instance_id),
    CONSTRAINT fk_workflow_supporting_documents_info_request FOREIGN KEY (tenant_id, info_request_id)
        REFERENCES workflow_info_requests (tenant_id, info_request_id),
    CONSTRAINT fk_workflow_supporting_documents_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT fk_workflow_supporting_documents_version FOREIGN KEY (tenant_id, document_version_id)
        REFERENCES document_versions (tenant_id, version_id),
    CONSTRAINT ck_workflow_supporting_documents_source CHECK (source IN ('linked_existing','uploaded_new')),
    CONSTRAINT ck_workflow_supporting_documents_access_mode
        CHECK (access_mode IN ('already_accessible','workflow_scoped_grant')),
    CONSTRAINT ck_workflow_supporting_documents_revoked
        CHECK (access_revoked_at IS NULL OR access_mode = 'workflow_scoped_grant')
);
CREATE UNIQUE INDEX uq_workflow_supporting_documents_instance_version
    ON workflow_supporting_documents (instance_id, document_version_id) WHERE is_deleted = false;
CREATE INDEX ix_workflow_supporting_documents_tenant_id ON workflow_supporting_documents (tenant_id);
CREATE INDEX ix_workflow_supporting_documents_instance ON workflow_supporting_documents (instance_id) WHERE is_deleted = false;
CREATE INDEX ix_workflow_supporting_documents_request ON workflow_supporting_documents (info_request_id)
    WHERE info_request_id IS NOT NULL;
CREATE INDEX ix_workflow_supporting_documents_document ON workflow_supporting_documents (tenant_id, document_id);

ALTER TABLE workflow_actions ADD COLUMN info_request_id UUID;
ALTER TABLE workflow_actions ADD CONSTRAINT fk_workflow_actions_info_request
    FOREIGN KEY (tenant_id, info_request_id) REFERENCES workflow_info_requests (tenant_id, info_request_id);
CREATE INDEX ix_workflow_actions_info_request ON workflow_actions (info_request_id) WHERE info_request_id IS NOT NULL;

-- A9: widen only (every V45 value kept)
ALTER TABLE workflow_actions DROP CONSTRAINT ck_workflow_actions_action_type;
ALTER TABLE workflow_actions ADD CONSTRAINT ck_workflow_actions_action_type CHECK (action_type IN (
  'submitted','claimed','released','approved','rejected',
  'stage_activated','stage_approved','stage_rejected','stage_unassignable',
  'workflow_approved','workflow_rejected','cancelled','version_changed','restarted',
  'reassigned','delegated','escalated','reminded','commented',
  'info_requested','info_provided','info_declined','info_cancelled',
  'info_reassigned','supporting_document_added','supporting_document_removed'));
```
The rollback restores the V45 CHECK, which fails loudly if any rows use the new values. That is intended. It then drops the actions column and FK, then the supporting-documents table, then the requests table.

### V49__create_document_comments.sql
```sql
CREATE TABLE document_comments (
    comment_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id          UUID NOT NULL,
    workflow_instance_id UUID,
    parent_comment_id    UUID,
    body                 VARCHAR(4000) NOT NULL,
    author_user_id       UUID NOT NULL REFERENCES users (user_id),
    edited_at            TIMESTAMPTZ,
    <audit block>,
    CONSTRAINT uq_document_comments_tenant_comment UNIQUE (tenant_id, comment_id),
    CONSTRAINT fk_document_comments_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT fk_document_comments_instance FOREIGN KEY (tenant_id, workflow_instance_id)
        REFERENCES workflow_instances (tenant_id, instance_id),
    CONSTRAINT fk_document_comments_parent FOREIGN KEY (tenant_id, parent_comment_id)
        REFERENCES document_comments (tenant_id, comment_id),
    CONSTRAINT ck_document_comments_body CHECK (length(btrim(body)) > 0),
    CONSTRAINT ck_document_comments_not_own_parent CHECK (parent_comment_id IS NULL OR parent_comment_id <> comment_id)
);
CREATE INDEX ix_document_comments_tenant_id ON document_comments (tenant_id);
CREATE INDEX ix_document_comments_document ON document_comments (tenant_id, document_id, created_at) WHERE is_deleted = false;
CREATE INDEX ix_document_comments_instance ON document_comments (workflow_instance_id, created_at)
    WHERE workflow_instance_id IS NOT NULL AND is_deleted = false;
CREATE INDEX ix_document_comments_parent ON document_comments (parent_comment_id) WHERE parent_comment_id IS NOT NULL;

CREATE TABLE comment_mentions (
    mention_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    comment_id          UUID NOT NULL,
    mentioned_user_id   UUID NOT NULL REFERENCES users (user_id),
    had_document_access BOOLEAN NOT NULL,
    notified_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_comment_mentions_tenant_mention UNIQUE (tenant_id, mention_id),
    CONSTRAINT fk_comment_mentions_comment FOREIGN KEY (tenant_id, comment_id)
        REFERENCES document_comments (tenant_id, comment_id),
    CONSTRAINT uq_comment_mentions_comment_user UNIQUE (comment_id, mentioned_user_id)
);
CREATE INDEX ix_comment_mentions_tenant_id ON comment_mentions (tenant_id);
CREATE INDEX ix_comment_mentions_user ON comment_mentions (tenant_id, mentioned_user_id, created_at DESC);
```
The rollback drops `comment_mentions`, then `document_comments`.

### V50__seed_workflow_phase2_permissions.sql
- This follows V46:13-39 exactly. Each `permission_actions` insert uses `ON CONFLICT (module_id, action_code) DO NOTHING`, and the `DO $$ … WITH inserted AS (INSERT INTO permissions … ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING RETURNING 1) … RAISE NOTICE` block counts the inserted rows.
  - Module `WA`, action `REQUEST_WORKFLOW_INFORMATION`: `wa.request-workflow-information.create`.
  - Module `DOCM`, action `MANAGE_DOCUMENT_COMMENTS`: `docm.document-comments.create`.
- No role grants, which is the repo convention.
- Delegation reuses approve/reject, reassignment reuses the existing `wa.reassign-workflow-task`, responding is identity-based (A8), and attaching requires `docm.documents.view`.
- The rollback deletes the rows it seeded (permissions first, then the actions).

---

## 3. State machines

### Task (additions in bold; `delegated` and `expired` stay unused, and no code writes them)
| From | To | Trigger |
|---|---|---|
| (none) | `pending` | Stage activation (unchanged) |
| `pending` | `claimed` | Claim, or implicit claim on act (unchanged). **Raising an info request on an unclaimed pool task claims it** |
| `claimed` | `pending` | Release by the claimer. **Refused if `delegated_to` is set or the task is awaiting info** |
| **`pending`/`claimed`** | **same status** | **Delegate or reassign** (sets `delegated_*`). **Escalate** (sets `escalated_*`). **Remind** (sets `reminder_sent_at`) |
| **`pending`/`claimed`** | **`awaiting_info`** | **The first open info request on the task** (`sla_paused_at = now`; for a pool task, `claimed_by = requester`) |
| **`awaiting_info`** | **`claimed`** (pool, or `claimed_by` set) **/ `pending`** (user task) | **The last open request closes** (answered, declined, withdrawn, or cancelled by reassign). Pause folded into `due_at` |
| `pending`/`claimed` | `approved`/`rejected` | Act. **`awaiting_info` is never decidable** (409 `WORKFLOW_TASK_AWAITING_INFO`) |
| `pending`/`claimed`/**`awaiting_info`** | `cancelled` | The stage concluded without this entry, or the instance was rejected, cancelled or restarted. **Its open requests are cancelled and its pause is folded into `sla_paused_seconds`** |

`awaiting_info` is **open and undecided** everywhere: in `TaskStatus.OPEN`, in the stage counters (it contributes nothing to a or r), in the lock sets and in the cancel sets.

### Information request
| From | To | Trigger | Effect on the task |
|---|---|---|---|
| (none) | `open` | Raised by the task holder (decision 12) | First open request → `awaiting_info` and the pause starts |
| `open` | `answered` | `requested_from` responds (note and/or supporting documents) | Last one → resume |
| `open` | `declined` | `requested_from` declines with a mandatory reason | Last one → resume |
| `open` | `cancelled` | The requester withdraws it (reason "Withdrawn by requester" if none is given) | Last one → resume |
| `open` | `cancelled` | Auto: the task is cancelled, because its stage concluded without it or the instance ended (decision 16) | Task is already closing |
| `open` | `cancelled` | Auto: an admin reassigned the task (§5) | Resume, then reassign |
| `open` | `open` (`requested_from` changed) | The initiator or a tenant admin reassigns the request (`info_reassigned`) | None; the pause continues |

`answered`, `declined` and `cancelled` are terminal.

### How these interact with the stage and the instance
- A request never changes a stage or instance status on its own.
- The stage concludes on decisions only. An `awaiting_info` task blocks *its own* entry, but other entries keep going (decision 12). An `any` stage can therefore approve while one approver is waiting; that cancels their task and its requests.
- Under `all`/`wait_for_all`, a waiting entry holds the stage open, as an undecided entry already does in Phase 1.
- When the instance is cancelled, restarted or rejected, `engine.cancel`/`halt` → `cancelTasks` → `cancelOpenRequests(task, cause)`.

---

## 4. Approve/reject path (updated)

```text
@Transactional WorkflowTaskServiceImpl.act(taskId, decision, reason)
  reason guard (unchanged); applyLockTimeout()
  (instanceId, stageInstanceId) = scalar lookups       -- nothing loaded before its lock (memory note)
  inst = lock instance; si = lock stage instance
  open = taskRepo.lockOpenByStageInstance(si.id, TaskStatus.OPEN)      -- now includes awaiting_info, ORDER BY task_id
  requireActionable(si, inst, me)                                       -- unchanged order: stage, instance, self, already decided
  task = open.find(taskId)
  task == null                          -> ENTRY_SATISFIED | TASK_CLOSED   (unchanged)
  task.status == awaiting_info          -> 409 WORKFLOW_TASK_AWAITING_INFO  (new; nobody can decide a paused entry)
  !TaskRouting.canAct(task, me)         -> 403 WORKFLOW_NOT_ASSIGNEE | 409 WORKFLOW_TASK_ALREADY_CLAIMED
       (routing in §5; replaces lines 149-153)
  implicit claim: if pool && task.claimedBy == null: claimedBy = me, claimedAt = now
  decide; si counter++
  actions.record(approved|rejected, detail += {route: assigned|pool|claimed|delegated|escalated,
                                               delegatedFrom?, escalatedTo?})
  audit (newValue += delegatedFrom, delegatedTo, escalatedTo, route)
  engine.evaluateAndAdvance(inst, si, open - task, me)

WorkflowEngine.cancelTasks(tasks, cause)                    -- used by approveStage, rejectStage, cancel
  for t in tasks (already locked, ascending task_id):
     reqs = infoRequestRepo.lockOpenByTaskIds(ids, tenant)  -- ORDER BY info_request_id FOR UPDATE (decision 22)
     for r in reqs: r.status = cancelled; r.cancelReason = causeText(cause)
                    actions.record(info_cancelled, infoRequestId = r.id, detail{cause}); audit WORKFLOW_INFO_CANCELLED
     if t.slaPausedAt != null: t.slaPausedSeconds += secondsSince(t.slaPausedAt); t.slaPausedAt = null
     t.status = cancelled
  publish InfoRequestsCancelledEvent(requestIds)            -- mail requested_from after commit
  causeText: stage_approved -> "The stage was approved without this approver"
             workflow_rejected -> "The workflow was rejected"; withdrawn -> "The workflow was withdrawn";
             restarted -> "The workflow restarted on a new document version"
```

The `StageOutcomeEvaluator` is unchanged: `awaiting_info` adds to neither a nor r.

---

## 5. Delegation and routing rules

```text
TaskRouting (J/workflow/helper/TaskRouting.java) — the single Java source of "who may act"
  canAct(task, me):
    if task.escalatedTo == me                         -> true          -- decision 6: an extra route
    if task.delegatedTo != null                       -> me == task.delegatedTo
    if task.claimedBy != null                         -> me == task.claimedBy  (covers claimed AND awaiting_info)
    if task.assigneeType == user                      -> me == task.assigneeId
    else                                              -> approverResolver.isPoolMember(task, me)   (live)
  currentHolders(task)  (mail and reminder recipients, A6):
    delegatedTo ? {delegatedTo} : claimedBy ? {claimedBy} : user ? {assigneeId}
       : resolve(participant) - initiator - decidedInStage(si)
    ∪ {escalatedTo} if set
  holderForDelegation(task) = delegatedTo ?: claimedBy ?: (user ? assigneeId : null)

POST /workflow/tasks/{id}/delegate {delegateUserId, note?}
  lock instance -> stage -> task (lockByTaskIdAndTenantId, new)
  guards: stage active / instance in_progress (reuse requireActionable minus "already decided")
          task.status ∈ DECIDABLE                           else 409 WORKFLOW_TASK_AWAITING_INFO | TASK_CLOSED
          me == holderForDelegation(task)                   else 403 WORKFLOW_NOT_TASK_HOLDER
             (an unclaimed pool task has no holder -> 409 WORKFLOW_TASK_NOT_CLAIMED, A4: claim first)
          delegate active, same tenant                      else 422 WORKFLOW_INVALID_DELEGATE
          delegate != me
          delegate != inst.initiatedBy                      else 422 WORKFLOW_DELEGATE_IS_INITIATOR (decision 3)
          !decidedInStage(si, delegate)                     else 422 WORKFLOW_DELEGATE_ALREADY_ACTED (decision 3)
          UserPermissions.holdsAny(delegate, APPROVE, REJECT) else 422 WORKFLOW_DELEGATE_LACKS_PERMISSION
  if delegate == task.delegatedFrom (back to the original, A3):
       delegatedFrom = delegatedTo = delegatedAt = null
  else:
       delegatedFrom = delegatedFrom ?: me                  -- original holder kept across hops (A3)
       delegatedTo = delegate; delegatedAt = now
  status unchanged; claimedBy unchanged (A4)
  actions.record(delegated, detail{from: me, to: delegate, originalHolder, note})
  audit WORKFLOW_TASK_DELEGATED; publish WorkflowTaskDelegatedEvent(previousHolder = me, delegate)

POST /workflow/tasks/{id}/reassign {userId, reason}       @PreAuthorize wa.reassign-workflow-task
  Same locks. Works on ANY open task, including parked (stage_unassignable) and awaiting_info ones.
  If awaiting_info: cancel its open requests (cause task_reassigned) and resume (§7) first, all under the
     same locks (info_requests locked after the task).
  Same target guards as delegate.
  delegatedFrom = holderForDelegation(task) (NULL for an unclaimed pool task, N8); delegatedTo = userId
  actions.record(reassigned, reason); audit WORKFLOW_TASK_REASSIGNED (reason required)
  mail the previous holder(s) and the new one
```

**Release on a delegated task returns 409 `WORKFLOW_TASK_DELEGATED`.** The delegate can re-delegate (A3) or act, and the original holder can no longer act on the task.

---

## 6. Scheduler design (S4)

**Files:**
- `J/workflow/service/impl/WorkflowSlaScheduler.java`: `@Scheduled(fixedDelayString="${dms.workflow.sla.poll-interval:PT1M}")`, `@ConditionalOnProperty(dms.workflow.sla.enabled, default true)`, plus a public `runOnce()` for tests.
- `WorkflowSlaProcessor.java`: one `TransactionTemplate` transaction per task.
- `J/workflow/repository/WorkflowSlaRepository(+Impl).java`: JdbcTemplate, following the `ScanResultRepositoryImpl` style.

**Config** (`application.yml`): `dms.workflow.sla.{enabled: ${WORKFLOW_SLA_ENABLED:true}, poll-interval: PT1M, batch-size: 50}`. It also adds **`spring.task.scheduling.pool.size: ${SCHEDULER_POOL_SIZE:2}`** so a slow ClamAV retry can no longer delay SLA mail, or the other way round. This is the only global change, and it is flagged in R5.

```text
runOnce():
  candidates = sla.findDue(batchSize)       -- plain SELECT, no lock:
    select t.task_id, t.tenant_id, si.instance_id
      from workflow_tasks t
      join workflow_stage_instances si on si.stage_instance_id = t.stage_instance_id and si.tenant_id = t.tenant_id
      join workflow_instances i        on i.instance_id = si.instance_id and i.tenant_id = si.tenant_id
      join workflow_stages s           on s.stage_id = si.stage_id and s.tenant_id = si.tenant_id
     where t.status in ('pending','claimed') and t.is_deleted = false and t.due_at is not null
       and si.status = 'active' and i.status = 'in_progress'
       and (   (t.escalated_at is null and s.escalation_target <> 'none' and t.due_at <= now())
            or (t.reminder_sent_at is null and s.reminder_before_hours is not null
                and t.due_at > now()
                and t.due_at - make_interval(hours => s.reminder_before_hours) <= now()))
     order by t.due_at limit ?
  for c in candidates: try { processor.process(c) } catch (Exception e) { log.warn; continue }   -- one bad row never stops the batch

processor.process(c)  -- new transaction
  applyLockTimeout()
  claimed = sla.lockInstanceSkipLocked(c.instanceId, c.tenantId)
     -- select instance_id from workflow_instances where instance_id=? and tenant_id=? and status='in_progress'
     --   for update skip locked
     -- empty => another node or a user transaction holds it: skip, the next tick retries
  si = lock stage; task = lock task      (lock order kept: instance -> stage -> task)
  re-check everything under the locks:   -- idempotent: a second node or a retry finds the stamp and no-ops
     task.status ∈ {pending, claimed} (awaiting_info skipped, decision 8), si active
  if due_at <= now && escalated_at == null && target != none:  escalate(task)
  else if reminder due (same predicate) && reminder_sent_at == null:  remind(task)
  (a task first seen already past due escalates and never gets a reminder; the reminder predicate needs due_at > now)

remind(task): reminderSentAt = now; actions.record(reminded, actor null, detail{recipients, dueAt})
              audit WORKFLOW_TASK_REMINDER_SENT; publish WorkflowTaskReminderEvent(taskId)
              -- recipients = TaskRouting.currentHolders (A6), resolved after commit
escalate(task):
  target = EscalationTargetResolver.resolve(stage, task, inst):
      named_user      -> stage.escalationUserId
      department_head -> task.assigneeType == department ? dept(task.assigneeId).headUserId
                                                         : dept(initiator.departmentId).headUserId   (A1)
  unusable(target) = null | !active | == initiator | ∈ currentHolders | decidedInStage
                     | !UserPermissions.holdsAny(target, APPROVE, REJECT)                             (A2)
  escalatedAt = now; escalatedTo = usable ? target : null
  actions.record(escalated, detail{target, source, skipped?: reason}); audit WORKFLOW_TASK_ESCALATED
  publish usable ? WorkflowTaskEscalatedEvent (mail the target, and copy the current holders)
                 : WorkflowEscalationFailedEvent (mail reassign holders and tenant admins)
```

| Case | Behaviour |
|---|---|
| Stage with no SLA | `due_at` is NULL and the task is never selected |
| No `reminder_before_hours` | No reminder; escalation is still possible |
| `escalation_target = none` | No escalation. The task stays overdue in My Tasks. The query excludes it, so it's never rescanned |
| Empty or unusable target | Stamped once with `escalated_to = NULL`, and admins are alerted (A2) |
| Already past due when the feature first runs | Escalates once and never sends a reminder. Every existing stage is `none`/NULL (Phase 1 UI never exposed the fields), and in-flight instances point to immutable stage rows, so nothing fires for existing work |
| Multiple app instances | Instance-row `FOR UPDATE SKIP LOCKED` plus the stamp re-check under the lock. A loser either skips, or sees the stamp and does nothing |
| Mail failure | Best effort, never retried, no stamp rollback. This is decision 24 and the Phase 1 rule |
| Task paused (`awaiting_info`) | Excluded by both the query and the re-check. On resume `due_at` has moved, so it can become due again normally |

---

## 7. SLA pause and resume

```text
on first open request:      sla_paused_at = now (only if null)
on each later request:      no change
on last request closing:    paused  = now - sla_paused_at   (whole seconds)
                            sla_paused_seconds += paused
                            due_at  += paused               (only if due_at != null)
                            sla_paused_at = null; status = claimedBy != null ? claimed : pending
on task cancel while paused: sla_paused_seconds += now - sla_paused_at; sla_paused_at = null (due_at untouched)
```

Example: stage SLA 48 h, activated at T0, so `due_at = T0+48h` and the reminder (24 h lead) falls at T0+24h.

| Time | Event | Open | `sla_paused_at` | `sla_paused_seconds` | `due_at` |
|---|---|---|---|---|---|
| T0+10h | R1 raised | 1 | T0+10h | 0 | T0+48h |
| T0+12h | R2 raised (overlapping) | 2 | T0+10h | 0 | T0+48h |
| T0+15h | R1 answered | 1 | T0+10h | 0 | T0+48h |
| T0+20h | R2 declined (last one) | 0 | NULL | 36000 (10 h) | **T0+58h** |
| T0+30h | R3 raised | 1 | T0+30h | 36000 | T0+58h |
| T0+32h | R3 withdrawn | 0 | NULL | 43200 (12 h) | **T0+60h** |

The reminder now falls at T0+36h and escalation at T0+60h, so the approver keeps the full 48 h of working time.

If a reminder was already sent before the pause, it is not sent again: `reminder_sent_at` fires once (decision 7).

The stage's `due_at` is not moved. The status panel shows the task's due time for a paused or extended task.

---

## 8. Concurrency

Lock order (decision 22): `documents → workflow_instances → workflow_stage_instances → workflow_tasks (asc task_id) → workflow_info_requests (asc info_request_id)`.

| Path | Locks | Notes |
|---|---|---|
| Approve/reject | instance → stage → open tasks (asc) → **info requests of the cancelled tasks (asc)** | Requests are only reached inside `cancelTasks`, after every task lock |
| Cancel / restart (version change) | (documents →) instance → stage → open tasks → info requests | Same `cancelTasks` |
| Delegate / reassign | instance → stage → task → (reassign: that task's open requests) | New `WorkflowTaskRepository.lockByTaskIdAndTenantId` |
| Raise request | instance → stage → task | Then INSERT; nothing new to lock |
| Respond / decline / withdraw | instance → stage → task → **all open requests of that task (asc)** | Locking all of them makes "is this the last one?" exact |
| Reassign request | instance → request | Skips the stage and task, which are unchanged; still a prefix of the order |
| Attach a supporting document (standalone) | instance | Serialises with cancel, so nothing is attached to a closed instance |
| Scheduler | instance (**SKIP LOCKED**) → stage → task | Never waits on a busy instance |
| Claim / release | single conditional UPDATE (unchanged) | Can't cycle. Claim requires `pending`, so it can't touch a paused or delegated task |
| Comments / mentions | none (inserts) | `commented` is an INSERT into `workflow_actions` |

**Interaction outcomes:**
- **Raise vs approve on the same task:** serialised by the instance lock. If raise wins, approve gets 409 `WORKFLOW_TASK_AWAITING_INFO`. If approve wins, raise gets 409 `WORKFLOW_TASK_CLOSED`.
- **Respond vs the stage closing:** whoever is second gets 409 (`WORKFLOW_INFO_REQUEST_CLOSED`) or finds the request already `cancelled`.
- **Delegate vs approve by the old holder:** serialised. A late approve gets 403 `WORKFLOW_NOT_ASSIGNEE`.
- **Scheduler vs any user transaction:** the scheduler skips with SKIP LOCKED and the next tick retries. If the user acted, the re-check finds the task closed.

New concurrency tests run 5× (`@RepeatedTest(5)`):
1. raise vs approve on the same task
2. two responses to the last two open requests (exactly one resume, with `due_at` shifted once)
3. respond vs a stage-closing approve by another entry
4. delegate vs approve
5. two scheduler runs in parallel over the same 20 due tasks (exactly one `reminded`/`escalated` action per task)
6. scheduler vs approve

Every run asserts `assertInvariants` (extended): no deadlock, counters match the task rows, and every `awaiting_info` task has at least one open request and vice versa.

---

## 9. Access model for comments, mentions and supporting documents

- **New helper `J/security/auth/UserPermissions.java`:** `holds(tenantId, userId, code)` = `findEffectivePermissionCodes(userId, tenantId).contains(code)` OR the user is a tenant admin (`KeycloakUserService.getPlatformRealmRoles(keycloakId).contains("TENANT_ADMIN")`). It **fails closed** if Keycloak errors, and also has `holdsAny`.
  - Used for: delegate and target permission checks, the escalation target check, and `had_document_access`.
  - The current caller still goes through `@authz` (`AuthorizationService`).
- **Comments:**
  - Reading requires `docm.documents.view`, on top of the tenant check. Writing requires `docm.document-comments.create` **and** `docm.documents.view` (decision 9).
  - Instance-scoped comments also require `workflow_instance.document_id = document_id` (422 otherwise).
- **Mentions** (decision 10):
  - Any active user in the tenant may be mentioned. `had_document_access = UserPermissions.holds(…, DOCM_DOCUMENTS_VIEW)` is captured at insert time.
  - A mentioned user without access receives the mail, which carries the comment text and the document title but no link to the document. They can read **that comment** (and its parent, for context) through `GET /comments/{id}` and `GET /mentions/my`, gated on "a mention row exists for me".
  - `GET /documents/{id}` and its content still return 403 for them.
  - Showing the title is recorded as a known exposure (Q-R3).
- **Supporting documents** (decision 18, **fallback confirmed**):
  - The sender must hold `docm.documents.view` (403 otherwise). `access_mode` is always `already_accessible`.
  - The list response gives each viewer `canOpen = authz.hasPermission(DOCM_DOCUMENTS_VIEW)`. With no per-document ACL, that is the whole access rule.
  - Viewers without the permission see the title, version number, note and who added it, but get no link. A direct `GET /documents/{id}/content` still returns 403.
  - Nothing grants access. A future workflow-scoped grant (F1) only adds rows with `access_mode = workflow_scoped_grant` and a check in `DocumentServiceImpl`.
- **Instance visibility** (`requireVisible`) adds three branches: `requested_from` on any request of the instance, `delegated_to`/`escalated_to` on any task, and the supporting-document sender. Without these, the person asked couldn't open the request's context.

---

## 10. Phase 1 code that must change

| File | Why |
|---|---|
| `J/workflow/enums/TaskStatus.java` | `OPEN` gains `awaiting_info`; add `HELD`, `DECIDABLE`, `DECIDED` |
| `J/workflow/enums/WorkflowActionType.java` | Add `info_reassigned`, `supporting_document_added`, `supporting_document_removed` |
| `J/workflow/entity/WorkflowTask.java` | `slaPausedAt`, `slaPausedSeconds`, `escalatedTo` |
| `J/workflow/entity/WorkflowStage.java`, `dto/UpsertWorkflowStageRequest`, `dto/WorkflowStageResponse` | `allowInfoRequests` |
| `J/workflow/entity/WorkflowAction.java`, `helper/WorkflowActionRecorder.java` | `infoRequestId`, plus a `record(…, infoRequestId)` overload |
| `J/workflow/repository/WorkflowTaskRepository.java` | One shared `MY_TASKS_WHERE` constant for the page and count queries, plus delegated and escalated branches, `:openStatuses`/`:heldStatuses`, overdue counted in SQL, `lockByTaskIdAndTenantId`, and `delegatedTo is null` on release |
| `J/workflow/service/impl/WorkflowTaskServiceImpl.java` | `awaiting_info` guard, `TaskRouting` in act/claim, release refuses delegated or paused tasks, `DECIDED` taken from the enum, `taskAudit` made package-visible for reuse |
| `J/workflow/service/impl/ApproverResolverImpl.java` | `canAct` delegates to `TaskRouting` (`isPoolMember` stays) |
| `J/workflow/service/impl/WorkflowEngine.java` | `cancelTasks` auto-cancels requests and folds the pause; the approved/rejected action detail carries the route |
| `J/workflow/service/impl/WorkflowQueryServiceImpl.java` | New DTO fields, `requireVisible` branches, `myOpenTaskId` through `TaskRouting`, S0(c) entry count, overdue in SQL |
| `J/workflow/service/impl/WorkflowNotificationListener.java` + `helper/WorkflowNotificationMailer.java` | Task-based recipients through `TaskRouting.currentHolders`; new handlers and mail methods (S9); recipient helpers extracted into `helper/WorkflowRecipients` |
| `J/workflow/service/impl/WorkflowTemplateServiceImpl.java` | `allowInfoRequests` in apply, copy-on-version and response |
| `J/workflow/dto/{TaskView, StageInstanceView, WorkflowInstanceResponse, MyTaskResponse}` | §S10 fields |
| `J/audit/catalog/{AuditActionCatalog, AuditObjectTypes}.java`, `J/security/rbac/catalog/PermissionCodes.java` | New actions, object types and codes |
| `J/document/dto/DocumentResponse.java`, `DocumentMapper.java`, `DocumentServiceImpl.java` | S0(b) `documentTypeName` |
| `J/importexport/template/ImportTemplateWriter.java` | S0(a) |
| `T/workflow/WorkflowConcurrencyIT.java` | Invariants include `awaiting_info` |
| `F/lib/workflow/{api.ts, workflow-badges.ts, workflow-errors.ts}`, `F/components/workflow/{workflow-status-panel, workflow-timeline, my-tasks-table, stage-card, schema}.tsx/ts`, `F/app/tasks/tasks-content.tsx`, `F/app/documents/[documentId]/page.tsx`, `F/lib/documents/api.ts`, `F/lib/auth/permission-codes.ts` | S0 and S12–S14 |
| `docs/events/workflow-audit-events.md` | Phase 2 events |

---

## 11. Build steps

Below, *Perm* is the `@PreAuthorize` check, and every endpoint is tenant-scoped (cross-tenant → 404).

### S0 Phase 1 fixes (M, low risk)
- **(a) CSV writer.**
  - In `ImportTemplateWriter.java:242-270`, write the marker and guidance `#` lines raw (`writer.write(line); writer.write("\r\n")`, stripping CR/LF from the guidance text) before `CSVPrinter` is used. `printRecord` is then used only for the header and rows.
  - The test is unchanged. The existing `csv_markerStillFound_whenASpreadsheetAppReQuotedTheHashLines` keeps the reader honest.
- **(b) Document type name.**
  - Add `String documentTypeName` to `DocumentResponse`.
  - In `DocumentServiceImpl.getDocument`, look up the type with `DocumentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse` and pass it to `DocumentMapper.toResponse(Document, String)` (a deleted type maps to null).
  - Frontend: add `documentTypeName` to `DocumentDto` (`F/lib/documents/api.ts:64-76`). In `F/app/documents/[documentId]/page.tsx:36-43,82,97`, remove the `fetchDocumentType` query and use `document.documentTypeName ?? "—"`.
  - Tests: `DocumentUploadIT` deserialisation still passes; add a new `DocumentResponseIT` asserting the name is returned to a user who holds only `docm.documents.view`.
- **(c) Entry count for skipped stages.**
  - In `WorkflowQueryServiceImpl`, for stage instances whose status is `not_started` or `skipped`, fill `entryCount` from the live participant count of `stage_id`. This is one batched `countByStageIdIn…` query. The DB columns are not touched, and the DTO javadoc documents it.
  - The frontend is unchanged.
  - IT: Scenario B's skipped stage 2 reports `entryCount = 1`.
- **(d) Backend error messages.**
  - `F/lib/workflow/workflow-errors.ts`: `workflowErrorMessage` returns `error.message` when the backend sent one. `ERROR_MESSAGES` becomes the fallback used only when there's no message, and the 403 wording comes from `friendlyMessage`.
  - Backend audit: check every `Workflow*Exception` message for UUIDs or developer wording, rephrase where needed, and make sure `WORKFLOW_NO_ELIGIBLE_APPROVERS` names the stage and the empty participant.
- **Done when** the full suite, including failsafe ITs, is green. That baseline is required from S1 on.

### S1 Spike (S, low risk)
Most of the spike was answered during exploration (§1). What remains, as throwaway tests that are not committed:
- **(a)** Confirm that `GET /documents/{id}` returns 403 for a user without `docm.documents.view` even when they are an approver. That means the supporting-document fallback has no hidden path.
- **(b)** Show that `select … for update skip locked` on `workflow_instances` returns empty while another transaction holds the act-path lock, and that `set_config('lock_timeout')` doesn't affect SKIP LOCKED.
- **(c)** Make `UserPermissions.holds` match `@authz.hasPermission` for a DB-granted user, a tenant admin and a user with no grants. This includes Keycloak being unreachable, in which case it fails closed.
- **(d)** Run `department_head` resolution (A1) against `ContractWorld` departments, with and without a head.

Output: notes appended to the plan's deviations section.

### S2 Migrations and entities (M, medium risk)
- V47–V50 and their rollbacks (§2).
- New entities: `J/workflow/entity/{WorkflowInfoRequest, WorkflowSupportingDocument}`, `J/comment/entity/{DocumentComment, CommentMention}`.
- New enums: `J/workflow/enums/{InfoRequestStatus, SupportingDocumentSource, SupportingDocumentAccessMode}`.
- Repositories:
  - `WorkflowInfoRequestRepository`: `lockOpenByTaskIds`, `lockByIdAndTenantId`, `countOpenByTaskId`, `findMyRequests`.
  - `WorkflowSupportingDocumentRepository`.
  - `J/comment/repository/{DocumentCommentRepository, CommentMentionRepository}`.
- The new **`J/comment`** module uses the subpackaged layout (`controller/ service/ service/impl/ repository/ entity/ dto/ helper/ exception/`).
- `PermissionCodes`: `WA_REQUEST_INFORMATION`, `DOCM_COMMENTS_CREATE`. `F/lib/auth/permission-codes.ts`: the same.
- **Tests:** `V47ToV50WorkflowPhase2MigrationTest` asserts:
  - the columns, CHECKs and index predicates (via `pg_indexes.indexdef`) and the composite FKs;
  - `awaiting_info` without a claimer on a pool task fails;
  - `sla_paused_at` on a `pending` task fails;
  - a decline without a reason fails;
  - a duplicate active supporting version fails;
  - the widened action type is accepted;
  - rollback, then re-migrate.

  Entities pass `ddl-auto: validate` (a context-loading IT).
- **Depends on** S0 and S1. Everything else depends on S2.

### S3 Delegation and reassignment (M, high risk)
- **Files:**
  - `J/workflow/helper/TaskRouting.java`
  - `J/workflow/service/WorkflowDelegationService` + `impl/`
  - `dto/{DelegateTaskRequest, ReassignTaskRequest}`
  - endpoints in `WorkflowTaskController`
  - the Phase 1 edits in §10 that concern routing and My Tasks
- **Endpoints:**

| Method | Path | Body → response | Perm |
|---|---|---|---|
| POST | `/workflow/tasks/{id}/delegate` | `{delegateUserId, note? (≤2000)}` → `TaskView` | approve **or** reject, plus being the holder (§5) |
| POST | `/workflow/tasks/{id}/reassign` | `{userId, reason (required)}` → `TaskView` | `wa.reassign-workflow-task` |
| GET | `/workflow/tasks/{id}/delegate-candidates?search&limit=20` | `[{userId, displayName, username, departmentName}]`: active users in the tenant who pass the §5 target guards | same as delegate |

- **Rules:** §5. My Tasks gains the delegated and escalated branches now; the escalated branch is exercised in S4. Mail is published as events here and handled in S9.
- **Tests:**
  - Unit: `TaskRoutingTest`, covering every route × status, including the awaiting_info hole.
  - IT: `WorkflowDelegationIT`:
    - delegation of a user task and of a claimed pool task;
    - unclaimed pool task → 409;
    - delegate is the initiator, has already acted, or lacks permission → 422;
    - re-delegation chain;
    - delegating back to the original clears it;
    - the old holder can't act (403);
    - release on a delegated task → 409;
    - admin reassign of a parked task unblocks the stage;
    - My Tasks views for delegate, original and pool;
    - cross-tenant → 404.
  - Concurrency 4.
- **Depends on** S2. Can run in parallel with S5 and S6.

### S4 Reminder and escalation scheduler (M, high risk)
- **Files:** §6, plus `J/workflow/helper/EscalationTargetResolver.java` and `J/workflow/config/WorkflowProperties` (`sla.*`).
- **Endpoints:** none.
- **Rules:** §6, A1, A2, A6.
- **Tests:**
  - Unit: target resolution and unusable cases.
  - IT `WorkflowSlaIT`: time is simulated by moving `due_at` with `fx.jdbc()`, and `runOnce()` is called directly with `dms.workflow.sla.enabled=false` in tests. It covers:
    - the reminder fires once, and a second run is a no-op;
    - escalation sets `escalated_to` and the task stays open;
    - the target and the original holder both see and can act;
    - no SLA, `none`, or an empty target is stamped and alerts admins;
    - awaiting_info is skipped;
    - past due on first sight → escalation only.
  - Concurrency 5 and 6.
- **Depends on** S3 (`TaskRouting`, the escalated branch).

### S5 Comments (M, medium risk)
- **Files:** `J/comment/{controller/DocumentCommentController, service/DocumentCommentService + impl/, dto/{CreateCommentRequest, UpdateCommentRequest, CommentResponse, CommentThreadResponse}, helper/CommentMapper}`.
- **Endpoints:**

| Method | Path | Body → response | Perm |
|---|---|---|---|
| GET | `/documents/{documentId}/comments?workflowInstanceId&page&size` | `PageResponse<CommentThreadResponse{root: CommentResponse, replies[]}>`, roots oldest first | `docm.documents.view` |
| POST | `/documents/{documentId}/comments` | `{body (1–4000), parentCommentId?, workflowInstanceId?}` → 201 `CommentResponse{commentId, documentId, workflowInstanceId, parentCommentId, body, author{userId, displayName}, createdAt, editedAt, deleted, mentions[{userId, displayName, hadDocumentAccess}]}` | `docm.document-comments.create` **and** `docm.documents.view` |
| PUT | `/comments/{commentId}` | `{body}` → `CommentResponse` (author only, sets `edited_at`) | same |
| DELETE | `/comments/{commentId}` | Soft delete by the author or a tenant admin. The body becomes "[deleted]" and replies stay | `docm.documents.view` |
| GET | `/comments/{commentId}` | `CommentResponse` plus its parent | `docm.documents.view` **or** mentioned (§9) |

- **Rules:**
  - Two-level threads: a reply to a reply attaches to the root.
  - The parent must be on the same document and must not be deleted.
  - `workflowInstanceId` must belong to the document. When set, a `commented` action is recorded with `detail{commentId}` (no body).
  - Audit `DOCUMENT_COMMENT_CREATED/_UPDATED/_DELETED` (module DOCM, object `DOCUMENT_COMMENT`).
- **Tests:** `DocumentCommentIT` covers threading, permission matrix, cross-tenant 404, instance/document mismatch → 422, edit by a non-author → 403, and delete masking.
- **Depends on** S2. Can run in parallel with S3 and S4.

### S6 @mentions (M, medium risk)
- **Files:**
  - `J/comment/helper/MentionParser.java`: regex `@\[([^\]]{1,150})\]\(user:([0-9a-f-]{36})\)`; at most 20 distinct ids.
  - `J/comment/service/impl/MentionServiceImpl`.
  - `J/comment/controller/MentionController`.
  - `J/workflow/controller/PeopleLookupController`, a separate class so it escapes the class-level gate on `WorkflowLookupController`.
  - `UserRepository.searchActive(tenantId, search, Pageable)`, using the `lower(...) like` pattern from `DepartmentRepository.search`.
- **Endpoints:**

| Method | Path | Response | Perm |
|---|---|---|---|
| GET | `/people/lookup?search&limit=20` | `[{userId, displayName, username, departmentName, hasDocumentAccess}]` | any of `docm.document-comments.create`, `wa.request-workflow-information.create`, approve, reject, `wa.reassign-workflow-task` |
| GET | `/mentions/my?page` | `PageResponse<MentionResponse{mentionId, comment: CommentResponse, documentId, documentTitle, canOpenDocument, createdAt}>` | authenticated |

- **Rules:**
  - On create or edit, parse the tokens and validate each id is an active user in the tenant (an unknown id → 422 `COMMENT_INVALID_MENTION`).
  - Mentioning yourself is ignored.
  - Insert `comment_mentions` with `had_document_access`, and publish `CommentMentionedEvent` (S9).
  - On edit, only newly added ids are inserted and notified. Removed tokens keep their rows as history.
  - The display name in the token is cosmetic; the server re-renders from `users.display_name`.
  - `hasDocumentAccess` in the lookup uses `findUserIdsWithPermission` plus one Keycloak admin-list call per request.
- **Tests:** `MentionParserTest`; `MentionIT` covering Scenario I, edit adding a mention, a cross-tenant id → 422, and a lookup with no permission → 403.
- **Depends on** S5.

### S7 Information requests (L, high risk)
- **Files:** `J/workflow/service/WorkflowInfoRequestService` + `impl/`, `J/workflow/controller/WorkflowInfoRequestController`, `dto/{RaiseInfoRequest, RespondInfoRequest, DeclineInfoRequest, WithdrawInfoRequest, ReassignInfoRequest, InfoRequestView}`, plus the `WorkflowEngine.cancelTasks` changes (§4).
- **Endpoints:**

| Method | Path | Body → response | Perm |
|---|---|---|---|
| POST | `/workflow/tasks/{taskId}/info-requests` | `{requestedFromUserId, message (1–2000), dueAt?}` → 201 `InfoRequestView` | `wa.request-workflow-information.create`, plus `TaskRouting.canAct` |
| POST | `/workflow/info-requests/{id}/respond` | `{message?, supportingDocuments?: [{documentId, documentVersionId?, source, note?}]}`, at least one of the two → `InfoRequestView` | `requested_from` only (A8) |
| POST | `/workflow/info-requests/{id}/decline` | `{reason (required)}` | `requested_from` only |
| POST | `/workflow/info-requests/{id}/withdraw` | `{reason?}` | `requested_by` only |
| POST | `/workflow/info-requests/{id}/reassign` | `{requestedFromUserId, reason?}` | initiator or tenant admin |
| GET | `/workflow/instances/{id}/info-requests` | `[InfoRequestView{infoRequestId, taskId, stageOrder, requestedBy, requestedFrom, message, status, responseMessage, declineReason, cancelReason, dueAt, requestedAt, respondedAt, respondedBy, supportingDocuments[]}]` | instance visibility (§9) |
| GET | `/workflow/info-requests/my?status=open&page` | "Requests for me" | authenticated |

- **Rules:**
  - Raise:
    - the stage's `allow_info_requests` must be true (422 `WORKFLOW_INFO_REQUESTS_DISABLED`);
    - the task must be open and the caller the holder, with the same guards as act minus the decision;
    - an unclaimed pool task is claimed for the requester;
    - `requested_from` must be an active tenant user and not the requester (422);
    - at most 10 open requests per task (422);
    - the first open request pauses the clock (§7).
  - Respond, decline and withdraw lock the task's open requests, close this one, and resume on the last (§7).
  - Auto-cancel happens through the engine (§4).
  - Actions `info_requested/provided/declined/cancelled/reassigned` all carry `info_request_id`.
  - Audit `WORKFLOW_INFO_*` (object `WORKFLOW_INFO_REQUEST`). Decline and cancel are reason-required.
- **Tests:** `WorkflowInfoRequestIT` covers Scenario J and its variations, the 10-cap, pause arithmetic with exact timestamps (`due_at` shifted by the summed seconds), reassign by the initiator, and a non-requester responding → 403. Concurrency 1–3.
- **Depends on** S3 (`TaskRouting`). S8 can run alongside it.

### S8 Supporting documents (M, medium risk)
- **Files:** `J/workflow/service/WorkflowSupportingDocumentService` + `impl/`, endpoints on `WorkflowInstanceController`, `dto/{AddSupportingDocumentRequest, SupportingDocumentView}`. Respond (S7) calls the same service.
- **Endpoints:**

| Method | Path | Body → response | Perm |
|---|---|---|---|
| POST | `/workflow/instances/{id}/supporting-documents` | `{documentId, documentVersionId?, source, note?}` → 201 `SupportingDocumentView{supportingDocumentId, documentId, title, versionId, versionNumber, source, note, addedBy, addedAt, infoRequestId, canOpen}` | `docm.documents.view`, plus instance visibility and being a participant (initiator, holder, `requested_from`) |
| GET | `/workflow/instances/{id}/supporting-documents` | list, `canOpen` per viewer (§9) | instance visibility |
| DELETE | `/workflow/instances/{id}/supporting-documents/{sdId}` | Soft remove by the adder while `in_progress` | the adder |

- **Rules:**
  - The version defaults to `documents.latest_version_id` and must belong to the document and be servable (scanned clean).
  - The document must not be the instance's own document (422 `WORKFLOW_SUPPORTING_IS_SUBJECT`).
  - `uploaded_new` requires `documents.created_by = me` (422 otherwise).
  - The instance must be `in_progress`.
  - The same version twice → 409, via a constraint-name translation in the service.
  - Supporting documents never change submission eligibility, never route, and never trigger the version listener: `DocumentVersionChangeListener` only looks at instances *of* that document (decision 17). An IT proves this.
  - Actions `supporting_document_added/removed` are recorded, or folded into `info_provided` when added through a response.
- **Tests:** `WorkflowSupportingDocumentIT` covers sender without view → 403; viewer without view gets `canOpen=false` and `GET /documents/{id}` returns 403; version pinning survives a new version of the supporting document; uploading a new version of it leaves the instance untouched; a subject document → 422.
- **Depends on** S2. Respond integration depends on S7.

### S9 Notifications (M, medium risk)
- **Files:** new event records in `J/workflow/event/`; handlers in `WorkflowNotificationListener` (same `dispatch` and per-recipient `send`); methods in `WorkflowNotificationMailer`; `J/comment/service/impl/CommentNotificationListener` + `helper/CommentNotificationMailer`; `helper/WorkflowRecipients` (active users, tenant admins, permission holders).

| Event | Recipients | Link |
|---|---|---|
| Task delegated / reassigned | Previous holder(s) and the new holder | `/tasks` |
| Reminder | `TaskRouting.currentHolders` (A6) | `/tasks` |
| Escalated | Target, with a copy to the current holders | `/tasks` |
| Escalation failed | `wa.reassign-workflow-task` holders and tenant admins | instance |
| Info requested / reassigned | `requested_from` (old and new on reassign) | `/tasks?view=requests` |
| Info answered / declined | Requester (with the reason on decline) | instance |
| Info cancelled | `requested_from`, and the requester if the cancel was automatic | instance |
| Mentioned | Mentioned user. Comment text always; a document link **only if `had_document_access`**. Sets `notified_at` afterwards | `/mentions` or the document |
| Reply | Author of the parent comment (if not the replier and not already mentioned) | document |

- **Tests:** `WorkflowNotificationMailerTest` (content, the no-link rule for mentions without access), and an IT checking mail goes out only after commit (`@MockitoBean JavaMailSender`, `awaitMail`).
- **Depends on** S3, S4 and S5–S8 for their events. Each event's mail can be written as its step lands.

### S10 API extensions (M, medium risk)
- **`TaskView`** adds `delegatedFrom`, `delegatedTo`, `delegatedAt`, `escalatedTo`, `escalatedAt`, `reminderSentAt`, `slaPausedAt`, `slaPausedSeconds`, `openInfoRequestCount` and `route`.
- **`StageInstanceView`** adds `allowInfoRequests`.
- **`WorkflowInstanceResponse`** adds `infoRequests[]`, `supportingDocuments[]` and `myOpenInfoRequestIds[]`. `myOpenTaskId` now goes through `TaskRouting`.
- **`MyTaskResponse`**: `status` adds `awaiting_info`, plus `route` (`assigned|pool|claimed|delegated|escalated`), `delegatedFrom` and `openInfoRequestCount`.
- **My Tasks views:** add `waiting` (my tasks in `awaiting_info`). "Requests for me" is `/workflow/info-requests/my`.
- **`/tasks/my/count`** returns `{open, overdue, requestsForMe}`. Overdue is computed in SQL and excludes `awaiting_info`.
- **Audit:** catalog entries, in module `WA` unless noted.

  | Action | Severity | Reason required |
  |---|---|---|
  | `WORKFLOW_TASK_DELEGATED` | MED | – |
  | `WORKFLOW_TASK_REASSIGNED` | MED | yes |
  | `WORKFLOW_TASK_REMINDER_SENT` | LOW | – |
  | `WORKFLOW_TASK_ESCALATED` | MED | – |
  | `WORKFLOW_INFO_REQUESTED` | MED | – |
  | `WORKFLOW_INFO_PROVIDED` | MED | – |
  | `WORKFLOW_INFO_DECLINED` | MED | yes |
  | `WORKFLOW_INFO_CANCELLED` | MED | yes |
  | `WORKFLOW_INFO_REQUEST_REASSIGNED` | LOW | – |
  | `WORKFLOW_SUPPORTING_DOCUMENT_ADDED`, `_REMOVED` | LOW | – |
  | `DOCUMENT_COMMENT_CREATED`, `_UPDATED` (module DOCM) | LOW | – |
  | `DOCUMENT_COMMENT_DELETED` (module DOCM) | MED | – |

  New object types: `WORKFLOW_INFO_REQUEST`, `WORKFLOW_SUPPORTING_DOCUMENT`, `DOCUMENT_COMMENT`. Comment audit rows carry ids and length, never the body. All of this is documented in `docs/events/workflow-audit-events.md`.
- **Timeline detail:** `approved`/`rejected` actions carry `route` and `delegatedFrom`, so the UI can render "Approved by Kumari, delegated from Nimal".
- **Tests:** a unit test that every new action is registered; a `WorkflowStatusIT` extension covering the shapes; `MyTasksIT` covering the waiting and requests views and the counts.
- **Depends on** S3, S4, S7 and S8. Fields are added as each step lands; S10 closes the gaps and the documentation.

### S11 Permissions and tenant filtering (S, medium risk)
- Every endpoint above gets a permission-matrix IT entry (`WorkflowPhase2PermissionIT`).
- `WorkflowTenantIsolationIT` is extended so tenant B tries every new endpoint on A's ids and always gets 404, and A's user ids are rejected as delegate, request target, mention or escalation user inside B.
- Checklist: `tenantId` on every new repository method and lock; scheduler audit uses `.tenantId(task.tenantId)`; mail recipients filtered by tenant.
- **Depends on** S3–S10.

### S12 Admin UI (M, low risk)
- `F/components/workflow/schema.ts`: `reminderBeforeHours` (int, ≥1 and < `slaHours`), `escalationTarget`, `escalationUserId` (required when `named_user`), `allowInfoRequests` (default true).
- `stage-card.tsx`:
  - an "SLA & escalation" block with a reminder hours input (disabled without an SLA);
  - escalation target as a `SegmentedControl` (None / Department head / Named user);
  - a user `SearchableSelect` from the existing `useUserOptions`;
  - an explanation line for department head (A1);
  - a `Switch` "Allow information requests";
  - the summary line shows ` · Reminder 24h before · Escalates to Dept head`.
- `F/lib/workflow/api.ts`: `WorkflowStageDto`/`UpsertStagePayload` gain `allowInfoRequests`.
- Checks: `tsc --noEmit` and eslint on the changed paths.
- **Depends on** S2 (API field). Can run in parallel with the backend steps.

### S13 Task and status UI (L, medium risk)
- **New components:**
  - `F/components/workflow/delegate-task-dialog.tsx`: a people picker over `delegate-candidates`, plus a note.
  - `reassign-task-dialog.tsx`, gated by `WA_TASK_REASSIGN`.
  - `request-info-dialog.tsx`: a person picker over `/people/lookup` with a "no document access" hint, a message, and an optional due date via `DatetimePicker`.
  - `respond-info-dialog.tsx`: a note; "Link existing document" (search by id/title among documents the user can view); "Upload new" (the existing upload API, then polling the ticket, A10).
  - `decline-info-dialog.tsx`.
  - `info-requests-list.tsx`.
  - `supporting-documents-list.tsx` (a link when `canOpen`, otherwise title and note with a lock icon).
  - `F/lib/workflow/{use-info-requests.ts, use-supporting-documents.ts}`.
- **Status panel** (`workflow-status-panel.tsx`):
  - per task: "Waiting on <name>", "Delegated from <name>", an "Escalated to <name>" badge, and the adjusted due time;
  - the action bar adds Delegate, Request information (hidden when the stage disallows it) and the pending responses;
  - an "Information requests" and a "Supporting documents" section.
- **Timeline** (`workflow-timeline.tsx` + `workflow-badges.ts`): labels and icons for every new action type, with `detail.route` rendering.
- **My Tasks:**
  - `tasks-content.tsx` `VIEWS` adds "Waiting on info" and "Requests for me" (`?view=requests`, a separate table).
  - `my-tasks-table.tsx` adds a route badge (Delegated / Escalated), an awaiting-info badge, and Delegate and Request-info actions.
- **Depends on** S3, S7, S8 and S10. It can start against agreed DTOs once S10 is fixed.

### S14 Discussion panel UI (L, medium risk)
- **New code:**
  - `F/lib/comments/{api.ts, use-comments.ts (15 s refetchInterval + focus, matching WA-NFR-001), use-mentions.ts}`.
  - `F/components/comments/{discussion-panel.tsx, comment-thread.tsx, comment-item.tsx, comment-composer.tsx, mention-textarea.tsx}`. `mention-textarea` uses the shipped Base UI `autocomplete`/`popover`: typing `@` opens a lookup and selecting inserts the token (A5). `mention-chip.tsx` renders tokens. Don't hand-roll the popover.
  - `F/app/mentions/page.tsx`: "Mentions of me", text only, linking to the document only when `canOpenDocument`.
- **Placement:**
  - The discussion panel goes on `F/app/documents/[documentId]/page.tsx`, as a right-column panel below the history, and on `F/app/workflows/instances/[instanceId]/page.tsx`, filtered to the instance.
  - A "Full history" toggle merges comments with `workflow_actions` decisions, sorted by time.
- Nav: add "Mentions" with a badge from `/mentions/my?unread`, or omit the badge if `notified_at` isn't enough. **Decide in S14; there is no read state in the ERD.**
- Read `node_modules/next/dist/docs/` before adding the page (`F/AGENTS.md`).
- **Depends on** S5, S6 and S10.

### S15 Tests and regression (L)
- All per-step tests, plus `T/workflow/WorkflowScenariosIT` gaining `@Nested` C, D, I and J (below).
- The full suite and failsafe ITs must be green, and every concurrency IT must pass 5 consecutive runs.
- Frontend: `tsc --noEmit` and eslint, plus the manual walkthrough in Verification.

### Dependencies, size and risk
```
S0 ─► S1 ─► S2 ─┬─► S3 ─┬─► S4
                │       └─► S7 ─┐
                ├─► S5 ─► S6    ├─► S10 ─► S11 ─► S15
                ├─► S8 ─────────┘
                └─► S12 (UI, parallel)
S9 grows alongside S3–S8; S13 after S10's DTOs; S14 after S5/S6.
```
| Step | S0 | S1 | S2 | S3 | S4 | S5 | S6 | S7 | S8 | S9 | S10 | S11 | S12 | S13 | S14 | S15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Size | M | S | M | M | M | M | M | L | M | M | M | S | M | L | L | L |
| Risk | Low | Low | Med | High | High | Med | Med | High | Med | Med | Med | Med | Low | Med | Med | — |

Execution runs strictly one step at a time, in order S0 → S15, per your rule 2. "Parallel" above only means "doesn't depend on".

---

## 12. End-to-end scenarios (`WorkflowScenariosIT`, `ContractWorld`)

**Fixture additions:**
- Legal's `head_user_id` = Kumari.
- Chamath's department = Legal, so the initiator's head is also Kumari.
- A user `delegate1` holds the Approver role.
- `outsider` has no `docm.documents.view`.
- The Contract stage 1 template variant has `sla 48`, `reminderBeforeHours 24`, `escalationTarget department_head`.

**C, nobody acts in time.**
1. Submit. A Legal pool task (the department, so its head is Kumari) and a Dept Head role task are created.
2. Set `due_at = now + 23h` and run `runOnce()`.
   - One `reminded` action per open task, and `reminder_sent_at` is set.
   - Mails go to Saman, Dilani and Ruwan (the unclaimed pool, A6) and to Kumari and Asanka.
   - A second `runOnce()` adds nothing.
3. Set `due_at = now - 1m` and run `runOnce()`.
   - The Legal task has `escalated_at` set and `escalated_to = Kumari`, with status still `pending`.
   - The Dept Head task's target is the initiator's head (Kumari), who is in the current holders of the role pool, so it is **unusable** (A2): `escalated_to` NULL, `detail.skipped = target_is_holder`, admins mailed.
   - Kumari's My Tasks shows the Legal task with route `escalated`, and Saman still sees it.
4. Either Saman or Kumari approves the Legal task (tested both ways). The other's attempt then gets `ENTRY_SATISFIED`.
5. Assertions: no task is ever `expired`; one `escalated` action per task; an audit row per event.

**D, delegation.** Stage 2 is the user Nimal (CFO).
1. Stages 1 and 2 are reached, then Nimal posts `/delegate {delegate1}`.
   - The task stays `pending` with `delegated_from = Nimal`, `delegated_to = delegate1`, and assignee still Nimal (A7).
   - It moves out of Nimal's My Tasks into delegate1's (route `delegated`). Both are mailed.
2. Nimal's approve → 403 `WORKFLOW_NOT_ASSIGNEE`.
3. delegate1 approves and the instance is `approved`.
4. The timeline's `approved` action has `actor = delegate1` and `detail.delegatedFrom = Nimal`. The status API `TaskView` shows `actedBy delegate1` and `delegatedFrom Nimal`.
5. Sub-cases: delegating to Chamath (the initiator) → 422; re-delegation delegate1 → asanka keeps `delegated_from = Nimal`; delegate1 → Nimal clears the delegation.

**I, mention without access.**
- Chamath comments on the contract: `"Please check @[Outsider](user:<outsider>)"`.
- The `comment_mentions` row has `had_document_access = false`.
- The outsider gets a mail with the comment text and no document link.
- `GET /mentions/my` returns the comment with `canOpenDocument=false`. `GET /comments/{id}` → 200.
- `GET /documents/{id}` → 403 and `GET /documents/{id}/comments` → 403.
- Control: mentioning Saman (who has view) records `true`, and his mail has the link.

**J, information request.** A parallel stage with Legal (pool) and Dept Head (role), completion `all`.
1. Saman raises a request to Chamath. The Legal task becomes `awaiting_info` with `claimed_by = Saman` and `sla_paused_at` set.
2. Saman's approve → 409 `WORKFLOW_TASK_AWAITING_INFO`.
3. Kumari approves the Dept Head task normally (stage still `active`, a=1).
4. Chamath responds by linking an existing document, `source = linked_existing`, pinned to its latest version.
   - The request is `answered`, Saman is mailed, the task is `claimed` again, `sla_paused_seconds = Δ`, and `due_at = old + Δ` (asserted to the second).
5. Saman approves and the stage and instance advance.

Variations, each on its own instance:
- **(a)** A decline with a reason resumes the task. A decline without a reason → 400.
- **(b)** A sender without `docm.documents.view` (the outsider as `requested_from`) tries to attach → 403. A note-only response still succeeds.
- **(c)** Approving while a request is open → 409 (as step 2).
- **(d)** Two requests: answering one keeps `awaiting_info`, and answering the second resumes with the summed pause.
- **(e)** The stage closes first: under `any`, Dilani can't act because the task is claimed by Saman, so a variant uses the Legal pool plus Dept Head with completion `any`. Kumari approves → the stage is approved, Saman's task is `cancelled`, the request is `cancelled` with `cancel_reason` "The stage was approved without this approver", there is an `info_cancelled` action, and Chamath is mailed.
- **(f)** `allow_info_requests = false` → raise returns 422 `WORKFLOW_INFO_REQUESTS_DISABLED`.

---

## 13. Risks and open questions

**Risks**
- **R1 Routing correctness** is the highest risk. The `awaiting_info` hole (§1-2) is closed by `TaskRouting` plus `TaskRoutingTest` covering every route × status, and by keeping the Java and JPQL predicates side by side with a test that compares My Tasks against `canAct` for every fixture user.
- **R2 Audit serialisation.** Every audit write takes a global `pg_advisory_xact_lock`. The scheduler handles one task per transaction with a batch of at most 50, so at most 50 short serialised writes per minute.
- **R3 Keycloak in hot paths.** `UserPermissions` calls Keycloak for the tenant-admin check (mentions, delegation, escalation). It is bounded (at most 20 mentions, one target) and fails closed. The lookup makes one admin-list call per request.
- **R4 The upload flow for `uploaded_new`** depends on the async ticket. When the scan is pending, the UI keeps the response dialog open and polls. If the user leaves, the document exists and can be linked later as `linked_existing`.
- **R5 `spring.task.scheduling.pool.size: 2`** changes global scheduler behaviour (jobs no longer queue behind each other). The existing jobs are already safe to run concurrently, because they are independent tables.
- **R6 Widened action CHECK (A9).** The rollback fails if new values are present. This is intended and documented.

**Open questions** (all non-blocking; each has a default). **Status: no answer was given when execution started (2026-10-05), so each default below applies.**
- **Q-R1 "Mentions" unread badge.** The ERD has no read state. *Default:* no badge, just a Mentions page. It can be added later with a `read_at` column.
- **Q-R2 Info-request `due_at`.** The column exists, but no reminder for it is in scope. *Default:* display only, and it shows overdue in "Requests for me".
- **Q-R3 Document title in mention mails** to users without access. *Default:* show it, since it's the context they were tagged in. If that is too much exposure, use a "a document" placeholder instead.

**Future items:** F1, a per-document ACL and workflow-scoped grant (`access_mode = workflow_scoped_grant`, `access_revoked_at`). F2, moving mail to the outbox and `notification-worker` (3.2). F3, push updates.

---

## Verification (end to end)
1. Backend: `mvn clean verify` in `backend/dms-core`, using the bundled Maven per the env memory. This runs every `*Test`, the V47–V50 migration test, and the failsafe `*IT` suite (excluding the Keycloak and ClamAV ITs, as in Phase 1).
2. Concurrency: `WorkflowConcurrencyIT` plus the new Phase 2 concurrency tests, green on 5 consecutive runs (`@RepeatedTest(5)`, run 5×).
3. Manual run (`spring-boot:run` on `SERVER_PORT=18080`, the frontend dev server, and Mailpit at http://localhost:8026):
   1. Set a department head.
   2. Create a template with SLA 2 h, a reminder 1 h before due, escalation to the department head, and info requests allowed.
   3. Submit, then move `due_at` with psql and watch the reminder and escalation mails.
   4. Delegate a task and approve as the delegate; check the timeline wording.
   5. Raise an info request, answer it with a linked document, and check that `due_at` moved and the task resumed.
   6. Comment with a mention of a user without access, and check the mail and that `/documents/{id}` is refused for them.
4. Frontend: `npx tsc --noEmit` and `npx eslint` on the changed paths.

---

## Deviations (filled in during execution)
| # | Plan said | Built as | Why |
|---|---|---|---|
| 1 | S0(d): show the backend message in workflow toasts | Also removed raw ids from backend messages. `WORKFLOW_NO_ELIGIBLE_APPROVERS` now names the participant (`role Finance Approvers`). `WORKFLOW_INVALID_ASSIGNEE`, the missing-document-type message and the missing-template-stage message no longer print UUIDs. | Once toasts show backend text, any raw id in a message would reach users. |
| 2 | S0(a): write the `#` lines raw | Raw only when the line has no comma, quote or line break; otherwise it is still written as a quoted record | A raw line with `, "` would open a quoted field in the reader and could swallow the header. The reader already accepts quoted `#` lines. |
| 3 | S0(c): entry count from the template "for `not_started` and `skipped`" | Applied to `not_started`, and to `skipped` only when `activated_at` is null | A stage skipped while active (a cancel) did activate, so its real `entry_count` is kept. |
| 4 | §2 V48: `CHECK (status <> 'declined' OR length(btrim(decline_reason)) > 0)` (same for cancel) | `coalesce(length(btrim(x)), 0) > 0` | `length(btrim(NULL))` is NULL, and a CHECK accepts NULL, so the planned form let a NULL reason through. The migration test caught it. **Finding:** Phase 1's `ck_workflow_tasks_reject_reason` and `ck_workflow_instances_outcome_reason` have the same gap. The service guards them (400 `WORKFLOW_REASON_REQUIRED`), and decision 25 forbids reworking them, so they are left as they are. |
| 5 | S2: the `allowInfoRequests` API field listed under S12 | Wired in S2: `UpsertWorkflowStageRequest.allowInfoRequests` (null = true), `WorkflowStageResponse.allowInfoRequests`, copied by "new version" | S12 depends on the field, and it belongs with the column. |
| 6 | §10: `ApproverResolverImpl.canAct` delegates to `TaskRouting` | `canAct` removed from `ApproverResolver`; every caller (`WorkflowTaskServiceImpl`, `WorkflowQueryServiceImpl`) uses `TaskRouting` directly | `TaskRouting` needs `ApproverResolver.isPoolMember`, so delegating back would be a circular dependency. One owner of the rule is also simpler. |
| 7 | §5 reassign: `delegatedFrom = holderForDelegation(task)` | `delegatedFrom` keeps an existing original holder (`delegatedFrom ?: current holder`), and reassigning back to that original clears the delegation | It keeps the same meaning as delegation (A3: "`delegated_from` keeps the original holder") and avoids the `delegated_from <> delegated_to` CHECK failing on a reassignment back. |
| 8 | S7: auto-cancel of open requests in `cancelTasks`, pause folding and resume | Implemented in `WorkflowEngine` during S3 (`cancelOpenRequests`, `resume`, `foldPause`, `closeInfoRequestsForReassignment`); exercised by the S7 tests | Admin reassignment of an `awaiting_info` task (§5) needs it, and the cancel path must be right before any request can exist. |
| 9 | S10: audit catalog entries | Every Phase 2 action and object type registered in S3 | An unregistered action throws, and S3–S9 each emit some of them. S10 then only documents them. |
| 10 | S6: `UserRepository.searchActive` | Added in S3 | `GET /workflow/tasks/{id}/delegate-candidates` needs it. |
| 11 | §5 lists release refused on delegated tasks | Claim is also refused on a delegated task (409 `WORKFLOW_TASK_DELEGATED`), and the claim/release UPDATEs add `delegated_to is null` | An admin reassignment of an unclaimed pool task leaves it `pending` with `delegated_to` set. Without this a pool member could claim a task they can't act on. |
| 12 | — | New helper `workflow/helper/TaskLocker` (instance, then stage instance, then task, with scalar ids first) | One lock sequence shared by delegate, reassign, info requests and the scheduler, instead of copying it into each. |
| 13 | S4 tests: "Unit: target resolution and unusable cases" | Covered by `WorkflowSlaIT` against the real resolver: pool department head, initiator's department head, named user, `target_is_holder`, `target_lacks_permission`, `target_is_initiator`, plus "none" | The resolver is mostly repository and Keycloak lookups, so an IT exercises it as it really runs, where a mock-heavy unit test would mostly assert the mocks. |
| 14 | — | `src/test/resources/application.properties` sets `dms.workflow.sla.enabled=false`; tests call `WorkflowSlaService.runOnce()` | Every cached Spring test context would otherwise run its own scheduler against the shared test DB and race the SLA tests (with a real mail sender outside the mocks). |
| 15 | §6: `WorkflowSlaScheduler` (+ processor) with `runOnce()` | `service/WorkflowSlaService` + `impl/WorkflowSlaServiceImpl` (`runOnce`, one transaction per task) and a thin `@ConditionalOnProperty` `WorkflowSlaScheduler`; JDBC queries in the class `repository/WorkflowSlaRepository` | The service stays available while the scheduler bean is switched off. |
| 16 | S5 and S6 as separate steps | Built and verified together | Create and edit store mentions in the same transaction as the comment, so the comment service depends on `MentionService`. One green suite run covers both. |
| 17 | S5: `GET /comments/{id}` returns "`CommentResponse` plus its parent" | Returns a `CommentThreadResponse`: the parent as `root` and the comment in `replies`, or the comment as `root` when it is itself a root | Reuses the thread shape the list endpoint already returns. |
| 18 | S5 rules: "a deleted comment keeps its place" | A deleted root is listed (body "[deleted]", no mentions) only while it still has a live reply; a deleted root with no replies disappears; a deleted reply is not listed | Keeps threads readable without showing empty "[deleted]" stubs. |
| 19 | S6: `GET /people/lookup` lives in `workflow/controller/PeopleLookupController` | Same, served at `/api/v1/people/lookup` as planned | — (recorded for the file location) |
| 20 | S7 and S8 as separate steps | Built and verified together | `respond` attaches supporting documents through the S8 service inside the same locked transaction. |
| 21 | S7 raise: "the caller the holder" | Anyone `TaskRouting.canAct` allows: the holder, a member of an unclaimed pool (which claims it for them, decision 12) or the escalation route | An unclaimed pool task has no holder until the request claims it. Decision 12 describes exactly that case. |
| 22 | S8 standalone attach: participant = "initiator, holder, `requested_from`" | Initiator, anyone who can act on an open task of the current stage, or anyone asked for information on the run | Same rule expressed through `TaskRouting`, so pool members and escalation routes count too. |
| 23 | S8: `uploaded_new` requires `documents.created_by = me` | `created_by` or `owner_user_id` = me | The promoter sets both to the uploader. Accepting either keeps the check robust. |
| 24 | — | A `dueAt` in the past on raise is 422 `WORKFLOW_INVALID_INFO_REQUEST`; `InfoRequestView` also carries `documentId`/`documentTitle` | Input validation, plus the title the "Requests for me" queue needs. |
| 25 | — | `WorkflowQueryService.requireVisible(instanceId)` is public; the request and supporting-document lists use it | One visibility rule (§9) for every instance-scoped read. |
| 26 | S9: new handlers inside `WorkflowNotificationListener` | Phase 2 workflow mail in a new `WorkflowActivityNotificationListener`; the after-commit/executor/per-recipient code extracted into `helper/NotificationDispatcher`; recipient lookups (active users, reassigners + tenant admins, document title) into `helper/WorkflowRecipients`; `WorkflowNotificationListener` refactored onto both | Keeps each listener readable, and removes the third copy of the TENANT_ADMIN lookup. `ThreatNotificationListener` (scanning) is left untouched. |
| 27 | Phase 1 task-assigned mail went to `ApproverResolver.resolve(participant)` | Goes to `TaskRouting.currentHolders(task)` | Plan §1-1: mail must follow the task row. For a fresh pool task the set is identical; a user task now always mails its assignee. |
| 28 | — | Comment mails reuse `dms.workflow.notifications` (enabled, mail-from) | Comments ship with workflow Phase 2; a separate switch can be split out with the 3.2 notification module. |
| 29 | S10: `TaskView` gains `route` | Not added to `TaskView`. `route` is on My Tasks rows and on the decision's timeline detail | The status view is shared by every viewer and a route is relative to one viewer. The timeline already says who decided by which route. |
| 30 | — | New helper `workflow/helper/WorkflowEvidenceMapper` builds `InfoRequestView`/`SupportingDocumentView` for the request, supporting-document and status services | The status service embeds both lists, and the two services already depend on it (`requireVisible`), so mapping inside them would be circular. |
| 31 | S12: escalation settings as planned | The stage form also refuses an escalation target without SLA hours ("Escalation happens at the due time - set SLA hours") | Without an SLA a task never has a due time, so the escalation would never fire. The backend still accepts it (Phase 1 validation unchanged), so existing data stays valid. |
| 32 | S13 respond dialog: "Link existing document (search by id/title among documents the user can view)" | Link by pasting the document's address or id; the dialog resolves it with `GET /documents/{id}` and shows the title | There is no document search or list endpoint in the backend (only upload/get/content). Adding one is outside this phase. |
| 33 | S13 My Tasks table: Delegate and Request-info actions | Delegate is in the table; Request information is offered on the status panel (and the document page) | A My Tasks row doesn't carry the stage's `allowInfoRequests`, and the request dialog benefits from the full run context. |
| 34 | — | The My Tasks nav badge counts open tasks + open requests for me | Answering a request is also waiting work for that person. |
| 35 | — | Shared UI pieces: `workflow-form-dialog.tsx` (dialog shell), `person-picker.tsx` (debounced server-side people search), `supporting-document-picker.tsx`; `useWorkflowActions` gained the Phase 2 mutations | One look and one invalidate-everything rule for every new action. |
| 36 | S14: mention autocomplete on Base UI `autocomplete`/`popover` ("don't hand-roll the popover") | A small suggestion list under the textarea, the same approach as the repo's `SearchableSelect`, with arrow/Enter/Tab/Escape keys and ARIA listbox roles | Base UI's Autocomplete binds to the whole value of an input. A mention is an "@word" at the caret inside free text, which it doesn't model. |
| 37 | A5: the autocomplete inserts the token | The textarea shows "@Display Name" and keeps a list of who each "@Name" points to; the token `@[Name](user:<id>)` is built on send (and turned back into "@Name" when editing) | People never see raw ids while typing; the stored format is exactly A5's. |
| 38 | Q-R1 (default taken) | A "Mentions" nav item and page without an unread badge | No read state in the ERD (open question Q-R1, default). |
---

## Progress log

Execution-rule change (2026-10-06): no mutating git commands. All work after S0 stays uncommitted in the working tree. Steps S1–S15 run back to back.

| Step | Files changed | Tests added | Result | Deviations |
|---|---|---|---|---|
| S0 | `ImportTemplateWriter`, `DocumentResponse`/`DocumentMapper`/`DocumentServiceImpl`, `WorkflowStageParticipantRepository`, `WorkflowQueryServiceImpl`, `WorkflowSubmissionServiceImpl`, `WorkflowEngine`, `WorkflowTemplateServiceImpl` (messages); FE `lib/documents/api.ts`, `app/documents/[documentId]/page.tsx`, `lib/workflow/workflow-errors.ts` | `DocumentResponseIT` (2); `WorkflowScenariosIT` B asserts the skipped stage's entry count | `mvn clean verify` green: 117 surefire + 102 failsafe, 0 failures. Committed as `e53eb56`, then soft-reset by the user, so it is uncommitted like every later step | #1–#3 |
| S1 | None. The throwaway `S1SpikeIT` (4 tests, all passed) was deleted after the run | — | Spike 4/4 green. No production code changed, so the S0 full-suite result still stands | — |
| S2 | Migrations V47–V50 and rollbacks; entities `WorkflowInfoRequest`, `WorkflowSupportingDocument`, `comment/entity/{DocumentComment, CommentMention}`; enums `InfoRequestStatus`, `SupportingDocumentSource`, `SupportingDocumentAccessMode`; `TaskStatus` (OPEN with awaiting_info, plus HELD, DECIDABLE, DECIDED), `WorkflowActionType` (+3); `WorkflowTask` (+3 fields), `WorkflowStage.allowInfoRequests`, `WorkflowAction.infoRequestId`, `WorkflowActionRecorder` overload; repositories `WorkflowInfoRequestRepository`, `WorkflowSupportingDocumentRepository`, `DocumentCommentRepository`, `CommentMentionRepository`; `PermissionCodes` (+2); stage request/response/copy; FE `permission-codes.ts` | `V47ToV50WorkflowPhase2MigrationTest` (2); `WorkflowTemplateIT.allowInfoRequests_*` (1) | Green: 119 surefire + 103 failsafe, 0 failures | #4, #5 |
| S3 | New: `security/auth/UserPermissions`, `workflow/helper/{TaskRouting, TaskLocker}`, `service/WorkflowDelegationService` + `impl/`, `dto/{DelegateTaskRequest, ReassignTaskRequest, PersonOptionResponse}`, `event/{WorkflowTaskDelegatedEvent, WorkflowInfoRequestEvent}`. Changed: `WorkflowTaskRepository` (one shared My Tasks predicate with delegated/escalated/waiting, `countMyOverdueTasks`, `lockByTaskIdAndTenantId`, claim/release skip delegated), `WorkflowTaskServiceImpl` (routing, awaiting_info guard, release/claim guards, route in action detail and audit), `WorkflowQueryServiceImpl` (views incl. `waiting`, overdue in SQL, `route`/`delegatedFrom` on My Tasks, delegation on `TaskView`), `ApproverResolver(Impl)`, `WorkflowEngine` (request auto-cancel, resume), `WorkflowTaskController` (+3 endpoints), `UserRepository.searchActive`, `AuditActionCatalog`/`AuditObjectTypes` (all Phase 2 entries) | `TaskRoutingTest` (7); `WorkflowDelegationIT` (6); `WorkflowConcurrencyIT.delegateRacesApprove_oneWins` (×5); invariants now cover `awaiting_info` and open requests | Green: 126 surefire + 114 failsafe. Concurrency suite (35 cases) green on 5 consecutive runs | #6–#12 |
| S4 | New: `service/WorkflowSlaService`, `impl/{WorkflowSlaServiceImpl, WorkflowSlaScheduler}`, `repository/WorkflowSlaRepository`, `helper/EscalationTargetResolver`, `event/{WorkflowTaskReminderEvent, WorkflowTaskEscalatedEvent}`, `src/test/resources/application.properties`. Changed: `WorkflowProperties` (`sla.*`), `application.yml` (`dms.workflow.sla.*`, `spring.task.scheduling.pool.size: 2`), `TaskRouting.currentHolders`, `TaskView` (+ escalatedTo/At, reminderSentAt), `WorkflowQueryServiceImpl` | `WorkflowSlaIT` (6); `WorkflowConcurrencyIT.twoSlaRunnersAtOnce_remindEachTaskOnce` and `.slaRunnerRacesApprove_consistent` (×5 each) | Green: 126 surefire + 130 failsafe. Concurrency suite (45 cases) green on 5 consecutive runs | #13–#15 |
| S5 + S6 | New module `comment/`: `controller/DocumentCommentController`, `service/{DocumentCommentService, MentionService}` + `impl/`, `dto/{CreateCommentRequest, UpdateCommentRequest, CommentResponse, CommentThreadResponse, CommentMentionResponse, MentionResponse}`, `helper/{CommentMapper, MentionParser}`, `event/{CommentCreatedEvent, CommentMentionedEvent}`, `exception/{CommentValidationException, CommentAccessException}`, repository queries (`findRoots`, `findMine`). Also `workflow/controller/PeopleLookupController` | `MentionParserTest` (2); `DocumentCommentIT` (4); `MentionIT` (3, incl. the Scenario I core) | Green: 128 surefire + 137 failsafe. No locking touched | #16–#19 |
| S7 + S8 | New: `service/{WorkflowInfoRequestService, WorkflowSupportingDocumentService}` + `impl/`, `controller/WorkflowInfoRequestController`, `dto/{RaiseInfoRequest, RespondInfoRequest, DeclineInfoRequest, WithdrawInfoRequest, ReassignInfoRequest, SupportingDocumentInput, SupportingDocumentView, InfoRequestView}`. Changed: `WorkflowInstanceController` (supporting-document endpoints), `WorkflowInfoRequestRepository` (addressed-to-me, open counts), `WorkflowSupportingDocumentRepository`, `WorkflowQueryService(Impl)` (public `requireVisible`, §9 visibility branches) | `WorkflowInfoRequestIT` (8); `WorkflowSupportingDocumentIT` (4); `WorkflowConcurrencyIT.{raiseRacesApprove_oneWins, lastTwoResponsesAtOnce_resumeOnce, respondRacesStageClosing_consistent}` (×5 each) | Green: 128 surefire + 164 failsafe. Concurrency suite (60 cases) green on 5 consecutive runs | #20–#25 |
| S9 | New: `workflow/helper/{NotificationDispatcher, WorkflowRecipients}`, `workflow/service/impl/WorkflowActivityNotificationListener`, `comment/helper/CommentNotificationMailer`, `comment/service/impl/CommentNotificationListener`, `CommentMentionRepository.markNotified`, `MentionParser.toPlainText`. Changed: `WorkflowNotificationListener` (helpers, task-based recipients), `WorkflowNotificationMailer` (+7 mails: task moved, reminder, escalated, escalation failed, info requested, answered/declined, closed) | `WorkflowPhase2MailerTest` (3); `WorkflowPhase2NotificationIT` (5: delegation, reminder + escalation, failed escalation, information requests incl. auto-cancel, mention without access + `notified_at` + reply) | Green: 131 surefire + 169 failsafe. No locking touched | #26–#28 |
| S10 | New: `helper/WorkflowEvidenceMapper`. Changed: `TaskView` (+ `slaPausedAt`, `slaPausedSeconds`, `openInfoRequestCount`), `StageInstanceView` (+ `allowInfoRequests`), `WorkflowInstanceResponse` (+ `infoRequests`, `supportingDocuments`, `myOpenInfoRequestIds`), `MyTaskResponse` (+ `openInfoRequestCount`), `MyTaskCountResponse` (+ `requestsForMe`), `WorkflowQueryServiceImpl`, the request and supporting-document services (mapper), `docs/events/workflow-audit-events.md` (Phase 2 events and timeline types) | `WorkflowPhase2AuditCatalogTest` (1); `WorkflowInfoRequestIT.statusAndQueues_showRequestsAndSupportingDocuments` (1) | Green: 132 surefire + 170 failsafe. No locking touched | #29, #30 |
| S11 | No production changes; the tenant/permission checks were built into S3–S10 (every new repository method and lock takes `tenantId`; the SLA scheduler audits with the task's tenant; mail recipients are filtered by tenant in `WorkflowRecipients`) | `WorkflowPhase2AccessIT` (2): the permission matrix for every Phase 2 endpoint, and another tenant's admin getting 404 for every id plus this tenant's people rejected as escalation target. The delegate, request-target and mention cases are in `WorkflowDelegationIT`, `WorkflowInfoRequestIT` and `MentionIT` | Green: 132 surefire + 172 failsafe | — |
| S12 | FE: `components/workflow/schema.ts` (reminder/escalation/info-request fields + cross-field rules), `components/workflow/stage-card.tsx` ("Reminders, escalation & information requests" block: reminder hours, escalation `SegmentedControl` + named-person `SearchableSelect`, `Switch`; summary lines), `lib/workflow/api.ts` (`allowInfoRequests`) | No FE test framework (Phase 1 Q7) | `tsc --noEmit` and eslint clean on the changed files | #31 |
| S13 | FE new: `components/workflow/{workflow-form-dialog, person-picker, delegate-task-dialog, request-info-dialog, respond-info-dialog, supporting-document-picker, info-requests-list, supporting-documents-list, requests-for-me-table}.tsx`, `lib/workflow/use-my-info-requests.ts`. Changed: `lib/workflow/api.ts` (Phase 2 types + 14 calls), `workflow-badges.ts`, `use-workflow-actions.ts`, `workflow-status-panel.tsx` (delegated/escalated/waiting per task, Delegate/Request Info/Reassign, adjusted due time, request and supporting-document sections), `workflow-timeline.tsx` (icons + "Approved by X, delegated from Y"), `my-tasks-table.tsx`, `app/tasks/tasks-content.tsx` (Waiting on info, Requests for me), `components/layout/app-sidebar.tsx`, `lib/documents/api.ts` (`fetchIngestionTicket`) | No FE test framework | `tsc --noEmit` and eslint clean on every changed path | #32–#35 |
| S14 | FE new: `lib/comments/{api.ts, use-comments.ts}`, `components/comments/{discussion-panel, comment-item, comment-composer, mention-textarea, mention-text}.tsx`, `app/mentions/page.tsx`. Changed: `app/documents/[documentId]/page.tsx` and `app/workflows/instances/[instanceId]/page.tsx` (discussion panel; on the run page scoped to the run, with "Full history" merging its decisions), `components/dashboard/nav-config.ts` (Mentions). Read the Next 16 app-router page docs first (AGENTS.md) | No FE test framework | `tsc --noEmit` and eslint clean on every changed path | #36–#38 |
| S15 | `WorkflowScenariosIT`: `@Nested` C (`c1`, `c2`), D (2), I (1), J (main + variations a, b, d, e, f; c is part of the main test). Imports tidied (sorted, unused removed) on every touched Java file | 11 scenario tests (22 in the class) | **Final regression:** `mvn clean verify` green - 132 surefire + 183 failsafe, 0 failures. `WorkflowConcurrencyIT` (60 cases) green on 5 consecutive runs. FE `tsc --noEmit` clean; eslint clean on every changed FE path | — |

### Step notes

**S1 findings:**
- **(a)** An approver without `docm.documents.view` (Saman in `ContractWorld`) gets 403 on both `GET /documents/{id}` and `/content`. There is no hidden access path, so the decision 18 fallback holds. In the fixture **no approver holds `docm.documents.view`**, so in Scenario J the approver sees supporting documents with `canOpen=false` unless the test grants it.
- **(b)** `FOR UPDATE SKIP LOCKED` on a `workflow_instances` row held by another transaction returns no rows in under 1 s even with `lock_timeout = 5s` set, and returns the row once it is released. The scheduler's "skip a busy instance" approach (§6) works.
- **(c)** `RolePermissionRepository.findEffectivePermissionCodes(otherUser, tenant)` works for a third party. **`KeycloakUserService.getPlatformRealmRoles` throws `KeycloakIntegrationException` in the test environment**, which has no Keycloak. `UserPermissions` must catch it and fail closed (treat the user as not a tenant admin). Tests that need a tenant admin as a third party mock `KeycloakUserService`.
- **(d)** `departments.head_user_id` is settable and reads back. `ContractWorld.chamath` has no department, so scenario fixtures must set Chamath's department and Legal's head explicitly (§12).

**Final state (2026-10-06):**
- All of S0–S15 are in the working tree, uncommitted, on `feature/chamath/workflow-phase2`. S0 was committed once (`e53eb56`) and then soft-reset by the user.
- Test database for the runs: the disposable `dms-workflow-pg` container (port 5435), database `dms_phase2_it`.
