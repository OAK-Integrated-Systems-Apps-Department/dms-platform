# Oak DMS · 3.1 Workflow Automation · Phase 1 (core approval engine): development plan

## Context

Requirement registry section 3.1 (`docs/requirement-registry.md:4155`, WA-FR-001…014, WA-NFR-001…005) asks for a configurable multi-stage approval engine. The repo already has the following groundwork:
- The `WA` permission module and nine `wa.*` permission codes (V6/V7/V10).
- `dlm.submit-documents-for-review` (V10:161).
- `user_roles.scope_type = 'department'` (V1:105).
- An empty `com.oak.dms.workflow` package.
- A placeholder "Workflows" nav item.

There are no workflow tables, no departments table and no engine code yet. Phase 1 delivers the core engine:
- Templates
- Runtime
- Approve and reject with parallel per-stage rules
- My Tasks
- Status
- Audit
- Core mail

The schema is designed so that Phase 2 (delegation, reminders and escalation, comments and mentions, information requests) only **adds** tables and columns.

### Decisions confirmed in Q&A (in addition to the 13 locked decisions)

| Topic | Decision |
|---|---|
| Majority | Strict majority: `required = floor(n/2) + 1`. So 2/2, 2/3, 3/4, 3/5. A 2–2 split does not approve. |
| Empty pool | Checked at **submission** for every stage. Any empty pool returns 422 and no instance is created. If a pool is empty when a later stage activates, the task is still created and **parked**: a `stage_unassignable` action is recorded and admins plus the initiator are mailed. Phase 2 reassign fixes it. |
| Department pool | **Direct members only**: `users.department_id = X`. Child departments are not included. |
| Who submits | Anyone holding `dlm.submit-documents-for-review`. Tenant admins hold it implicitly through `AuthorizationService.hasFullAccess`. |
| `wait_for_all` | A rejection never ends the stage early. Approval still short-circuits as soon as `approved_count >= required_count` (only possible early under `any`/`majority`). Otherwise the stage concludes only once **every** entry has responded (`approved_count + rejected_count = entry_count`), and is then rejected because approvals are below `required_count`. Scenario H: Legal approves, Finance rejects, and the Dept Head still acts. |
| CHECK values | Phase 1 CHECK constraints already list every Phase 2 status and action value from the ERD. Phase 1 code never writes the Phase 2 values. |
| Self-approval | **Segregation of duties.** The initiator can never claim or act on a task in their own instance. They are excluded when pools resolve, and a user entry naming the initiator blocks submission (422). |
| Document mutation | **None.** The outcome lives only on the instance. `documents.status` and `published_version_id` are not touched. |

---

## 1. Findings from exploration

### Stack
- **Backend:** `backend/dms-core` is Spring Boot 4.1 on Java 17.
  - Persistence is Spring Data JPA on Hibernate 7, with `ddl-auto: validate` and `open-in-view: false`. Entities must therefore match the migrations exactly.
  - PostgreSQL 18.
  - Flyway: `src/main/resources/db/migration`, currently up to V42. V35 is missing, so the next number is **V43**.
  - Lombok and MapStruct 1.6.3. The newest modules use hand-written `@Component` mappers in `helper/`.
  - Also used: springdoc, RabbitMQ (`oak.dms.events` exchange), Redis, Keycloak OAuth2 resource server, `spring-boot-starter-mail`.
- **Frontend:** `frontend/` is Next 16.2 / React 19.2 / TypeScript.
  - Tailwind 4, shadcn (`base-nova`, built on Base UI).
  - react-query 5, zustand (legacy screens only), react-hook-form with zod 4, sonner, lucide. The package manager is bun.
  - `frontend/AGENTS.md` warns that Next has breaking changes. Read `node_modules/next/dist/docs/` before writing pages.
- **Tests:**
  - JUnit 5, Spring Boot test, `spring-security-test`, MockMvc.
  - **No Testcontainers.** `*IT` tests run against a live Postgres (`DB_URL`, default `localhost:5433/dms_dev`).
  - **`*IT` classes are not run by `mvn verify`, so they don't run in CI.** `*Test` classes do run in CI, which provides postgres:18, redis and rabbit services.
  - Migration tests (`importexport/V41ExportJobsMigrationTest`) are `*Test`. They migrate a scratch DB to N−1, then N, assert the schema, run the rollback script, then re-migrate.
  - The frontend has **no test framework**. CI runs only `bun run typecheck` and a non-blocking lint.

### Conventions to follow (with the source of each)
- **Package layout** for new modules is subpackaged: `controller/ service/ service/impl/ repository/ entity/ enums/ dto/ helper/ exception/` (see `scanning`, `importexport`). `document/` and `user/` are older flat modules.
- **Primary keys:** `UUID PRIMARY KEY DEFAULT gen_random_uuid()`. Entities use `@UuidGenerator`, as in `importexport/entity/MigrationBatch.java:39`.
- **Audit block**, copied verbatim on every table (V28/V32/V39/V42):
  ```sql
  created_by UUID REFERENCES users (user_id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by UUID REFERENCES users (user_id), updated_at TIMESTAMPTZ,
  is_deleted BOOLEAN NOT NULL DEFAULT false, deleted_at TIMESTAMPTZ
  ```
  - There is **no** `@MappedSuperclass`, auditing listener, `@SQLRestriction` or `@Version`.
  - Each entity declares the columns itself. The Java field is `deleted` mapped to `is_deleted`, and `createdAt` uses `@Builder.Default = OffsetDateTime.now()`.
  - Services set `updatedBy` and `updatedAt` by hand.
  - Soft delete is filtered through repository method names such as `...AndDeletedFalse`.
- **Enums:** `VARCHAR` plus a `CHECK`, never a Postgres enum (V28:18 states this). Java uses `@Enumerated(EnumType.STRING)` with **lowercase** constants that match the CHECK values (e.g. `importexport/enums/MigrationBatchStatus`).
- **Naming:**
  - `uq_<table>_<cols>`, `ix_<table>_<cols>`, `ck_<table>_<col>`, `fk_<table>_<target>`.
  - Every table gets `ix_<table>_tenant_id`.
  - Tenant-safe composite FKs `(tenant_id, x_id)`, each backed by a `uq_<table>_tenant_<id>` constraint (V36:121, V39:127).
- **Partial unique indexes** already exist, e.g. V28:58 `... WHERE is_deleted = false` and V40:58 for a status-filtered one.
- **Soft-delete-aware, deferrable ordering:** the V31:97-103 pattern is a generated `display_order_uniq_key` column plus `UNIQUE ... DEFERRABLE INITIALLY DEFERRED`. **Reuse it for `stage_order`.**
- **Rollback scripts:** `db/rollback/V<n>__<desc>.rollback.sql`, each exercised by its migration test. The convention started at V41.
- **Tenant isolation:**
  - `TenantContextFilter` binds `TenantContext` (a ThreadLocal) from the local `users` row found by the JWT `sub`.
  - There is **no ORM-level filter**. Every repository method takes `tenantId` explicitly, e.g. `DocumentRepository.findByDocumentIdAndTenantIdAndDeletedFalse`.
  - A cross-tenant record returns **404** (`ResourceNotFoundException` / `TenantMismatchException`).
- **Permissions:**
  - Pattern: `@PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).X)")`, or `@authz.hasAnyPermission(...)`.
  - `AuthorizationService.hasFullAccess()` grants everything to `ROLE_TENANT_ADMIN` and `ROLE_PLATFORM_ADMIN`.
  - `RolePermissionRepository.findEffectivePermissionCodes(userId, tenantId)` and `findUserIdsWithPermission(tenantId, code)` already exist.
  - **`user_roles.scope_type` and `scope_id` are stored but never evaluated anywhere.**
  - Permission seed SQL follows the V40:133-163 pattern.
- **Current user:** `TenantContext.getUserId()` and `getDisplayNameOrNull()`.
- **Pessimistic locking** (the pattern to reuse):
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select x from X x where x.id = :id and x.tenantId = :tenantId and x.deleted = false")
  Optional<X> lockByIdAndTenantId(...)
  ```
  - Schema publishing uses this in `DocumentTypeFieldServiceImpl.replaceMapping` (`metadata/service/impl/DocumentTypeFieldServiceImpl.java:88-206`).
  - It locks the **parent** `document_types` row via `DocumentTypeRepository.lockByDocumentTypeIdAndTenantId` (`metadata/repository/DocumentTypeRepository.java:41-44`), then bumps the version, archives the old row and inserts the new one.
  - The unique constraint `uq_dts_type_version` is the database backstop.
  - Other `lockBy*` examples are in `QuarantineRecordRepository` and `ScanResultRepository`.
  - **No lock timeouts are configured anywhere.**
- **Audit:**
  - Calls look like `auditService.event(AuditActionCatalog.X).objectType(AuditObjectTypes.Y).objectId(..).resourceName(..).outcome(SUCCESS).reason(..).previousValue(..).newValue(Map).log()`. Example: `scanning/service/impl/QuarantineServiceImpl.java:142-150`.
  - Events are written synchronously in the caller's transaction, hash-chained under `pg_advisory_xact_lock`.
  - Actions must be registered in the `AuditActionCatalog` map as `AuditActionSpec(module, severity, reasonRequired)`. An unregistered action throws `IllegalStateException`.
- **Mail:**
  - There is no shared mail service. `scanning/helper/ThreatNotificationMailer.java` sends a plain-text `SimpleMailMessage` through `JavaMailSender`, with the sender from `dms.scanning.notifications.mail-from` and links built from `app.frontend-base-url`.
  - Mail is triggered after commit by `ThreatNotificationListener.java:83`: `@TransactionalEventListener(AFTER_COMMIT)`, then `applicationTaskExecutor`, with a per-recipient try/catch and no retry.
- **Errors:**
  - `ApiException(HttpStatus, code, message)` subclasses go through `GlobalExceptionHandler`. `DataIntegrityViolationException` becomes 409.
  - `AccessDeniedException` becomes 403 and is audited.
- **Pagination:** `common/dto/PageResponse.from(Page)`, `@PageableDefault(size = 20, sort = "createdAt", direction = DESC)`, and Specifications for filtered lists.
- **jsonb:** `@JdbcTypeCode(SqlTypes.JSON)` with `@Column(columnDefinition = "jsonb")`. Avoid the `?` operator in Hibernate native queries; use `@>` or `JdbcTemplate`.

### Surprising things that affect the plan
1. **Throwing `IllegalStateException` is a trap.** `GlobalExceptionHandler` maps *every* `IllegalStateException` to **401 `TENANT_CONTEXT_MISSING`**. Workflow code must throw `ApiException` subclasses only.
2. **No departments table and no `users.department_id`**, as expected. Department-scoped `user_roles` exist in the CHECK constraint only; no code honours them.
3. **No document view page exists in the frontend.** Documents appear only as mock data on the dashboard (`components/dashboard/panels/recent-documents-panel.tsx`, `pending-approvals-panel.tsx`). The status panel therefore needs a host page (see W18a).
4. **`notification-worker` is an empty placeholder**, and nothing in the system consumes RabbitMQ events. Notifications must be sent in-process, as the threat mails are.
5. **`DocumentVersionPromoter` takes no locks and publishes no event.** Phase 1 needs a hook there to set `version_changed_flag`.
6. **The most important tests (concurrency, the engine) are ITs, which CI doesn't run.** Resolved by Q1: a failsafe execution runs them in CI.
7. Permission codes `wa.*` exist in the database but are missing from both `PermissionCodes.java` and `frontend/lib/auth/permission-codes.ts`.
8. There are no permission codes for departments. New `iam.manage-departments.*` codes are needed.
9. Mail sending is synchronous inside a transaction in `PasswordResetService`. **Do not copy that.** Copy the after-commit threat-listener pattern instead.

### ERD vs the real schema: mismatches and how they are resolved
| # | ERD | Reality / issue | Resolution |
|---|---|---|---|
| M1 | `document_version_id` FK to "document_versions" | The primary key is `document_versions.version_id`. A tenant-safe unique key exists: `uq_document_versions_tenant_version (tenant_id, version_id)` (V39:127). | Keep the column name `document_version_id`. FK: `(tenant_id, document_version_id) → document_versions(tenant_id, version_id)`. |
| M2 | documents has `latest_version_id` | It also has `current_version_id` and `published_version_id`, all with deferred FKs. | Snapshot **`latest_version_id`**, the newest scanned version. Version-change detection watches `latest_version_id`. |
| M3 | `workflow_templates.code` unique per tenant | Each template version is a **new row**. This is required so that in-flight instances keep pointing at immutable stages (decision 9). A per-tenant unique code would forbid versions. | Unique on `(tenant_id, code, version_number)` where the row is not deleted. `code` identifies the template family and cannot change. |
| M4 | `stage_order` unique per template | A plain unique constraint breaks both soft delete and reordering. | Use the V31 pattern: a generated `stage_order_uniq_key` column plus a deferrable unique constraint. |
| M5 | Decision 10 setting has no column | The ERD has no column for "flag only vs restart". | Add `workflow_templates.on_document_change VARCHAR(20) DEFAULT 'flag_only'` with CHECK `('flag_only','restart')`. |
| M6 | Stage instance counters | Counting outcomes needs `n`, the number of entries. | Add `workflow_stage_instances.entry_count INT`. This avoids a count query while the row is locked. |
| M7 | `workflow_actions.action_type` list | Phase 1 also records claim, release, stage and restart events. | Add `claimed, released, stage_activated, stage_approved, stage_rejected, stage_unassignable, workflow_approved, workflow_rejected, restarted`. Keep every ERD value, including the Phase 2 ones. |
| M8 | Participants carry only `created_*` and `is_deleted`/`deleted_at` | The repo convention is the full audit block. | Add `updated_by` and `updated_at`. |
| M9 | `allow_info_requests`, `sla_paused_at`, `sla_paused_seconds`, `info_request_id` | These are Phase 2 features. | Leave them out of Phase 1. Phase 2 adds them with `ADD COLUMN` (nullable or with a default). |
| M10 | FK targets to users, roles and departments | `users` has no `(tenant_id, user_id)` unique key. The repo's `created_by` style is a plain `REFERENCES users(user_id)`. | Use plain FKs to `users(user_id)` for actor columns, and check the tenant in the service. `assignee_id` is a **soft reference with no FK**, like `user_roles.scope_id`. |
| M11 | `workflow_actions` has no audit block | It is append-only. | Keep it without the audit block. `REVOKE UPDATE, DELETE` as `audit_events` does (V23:79). |
| M12 | Role pool membership | `user_roles` carries `scope_type`/`scope_id`. | A role pool means any `user_roles` row for that `role_id`, ignoring scope. This matches `RolePermissionRepository`. Confirmed (Q3). |

---

## 2. Migration plan (in order)

Each migration gets a rollback script `db/rollback/V<n>__<desc>.rollback.sql` and a migration `*Test`.

### V43__create_departments.sql
```sql
CREATE TABLE departments (
  department_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
  name VARCHAR(150) NOT NULL,
  code VARCHAR(50)  NOT NULL,
  parent_department_id UUID,
  head_user_id UUID REFERENCES users (user_id),
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  <audit block>,
  CONSTRAINT uq_departments_tenant_department UNIQUE (tenant_id, department_id),
  CONSTRAINT fk_departments_parent FOREIGN KEY (tenant_id, parent_department_id)
      REFERENCES departments (tenant_id, department_id),
  CONSTRAINT ck_departments_status CHECK (status IN ('active','inactive')),
  CONSTRAINT ck_departments_not_own_parent CHECK (parent_department_id IS NULL OR parent_department_id <> department_id)
);
CREATE UNIQUE INDEX uq_departments_tenant_code ON departments (tenant_id, code) WHERE is_deleted = false;
CREATE INDEX ix_departments_tenant_id ON departments (tenant_id);
CREATE INDEX ix_departments_parent ON departments (parent_department_id) WHERE parent_department_id IS NOT NULL;

ALTER TABLE users ADD COLUMN department_id UUID;
ALTER TABLE users ADD CONSTRAINT fk_users_department FOREIGN KEY (tenant_id, department_id)
      REFERENCES departments (tenant_id, department_id);        -- MATCH SIMPLE: NULL allowed
CREATE INDEX ix_users_department ON users (tenant_id, department_id) WHERE department_id IS NOT NULL AND is_deleted = false;
```
Deeper cycles in the parent hierarchy are prevented in the service by walking up the ancestors before saving.

### V44__create_workflow_templates.sql
```sql
CREATE TABLE workflow_templates (
  template_id UUID PK DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants,
  document_type_id UUID NOT NULL, name VARCHAR(150) NOT NULL, code VARCHAR(50) NOT NULL,
  description VARCHAR(1000), version_number INT NOT NULL DEFAULT 1,
  status VARCHAR(20) NOT NULL DEFAULT 'draft',
  on_document_change VARCHAR(20) NOT NULL DEFAULT 'flag_only',
  <audit block>,
  CONSTRAINT uq_workflow_templates_tenant_template UNIQUE (tenant_id, template_id),
  CONSTRAINT fk_workflow_templates_document_type FOREIGN KEY (tenant_id, document_type_id)
      REFERENCES document_types (tenant_id, document_type_id),
  CONSTRAINT ck_workflow_templates_status CHECK (status IN ('draft','active','inactive')),
  CONSTRAINT ck_workflow_templates_on_document_change CHECK (on_document_change IN ('flag_only','restart')),
  CONSTRAINT ck_workflow_templates_version CHECK (version_number >= 1)
);
-- Decision 7: one active template per document type per tenant
CREATE UNIQUE INDEX uq_workflow_templates_one_active_per_type
    ON workflow_templates (tenant_id, document_type_id) WHERE status = 'active' AND is_deleted = false;
-- M3: versions share a code
CREATE UNIQUE INDEX uq_workflow_templates_tenant_code_version
    ON workflow_templates (tenant_id, code, version_number) WHERE is_deleted = false;
-- at most one open draft per template family
CREATE UNIQUE INDEX uq_workflow_templates_one_draft_per_code
    ON workflow_templates (tenant_id, code) WHERE status = 'draft' AND is_deleted = false;
CREATE INDEX ix_workflow_templates_tenant_id ON workflow_templates (tenant_id);
CREATE INDEX ix_workflow_templates_document_type ON workflow_templates (tenant_id, document_type_id);

CREATE TABLE workflow_stages (
  stage_id UUID PK, tenant_id UUID NOT NULL REFERENCES tenants, template_id UUID NOT NULL,
  stage_order INT NOT NULL, name VARCHAR(150) NOT NULL,
  completion_rule VARCHAR(20) NOT NULL, rejection_rule VARCHAR(20) NOT NULL,
  sla_hours INT, reminder_before_hours INT,
  escalation_target VARCHAR(20) NOT NULL DEFAULT 'none', escalation_user_id UUID REFERENCES users (user_id),
  <audit block>,
  stage_order_uniq_key INT GENERATED ALWAYS AS (CASE WHEN is_deleted THEN NULL ELSE stage_order END) STORED,
  CONSTRAINT uq_workflow_stages_tenant_stage UNIQUE (tenant_id, stage_id),
  CONSTRAINT fk_workflow_stages_template FOREIGN KEY (tenant_id, template_id) REFERENCES workflow_templates (tenant_id, template_id),
  CONSTRAINT uq_workflow_stages_template_order UNIQUE (template_id, stage_order_uniq_key) DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT ck_workflow_stages_order CHECK (stage_order >= 1),
  CONSTRAINT ck_workflow_stages_completion_rule CHECK (completion_rule IN ('all','any','majority')),
  CONSTRAINT ck_workflow_stages_rejection_rule CHECK (rejection_rule IN ('halt_immediately','wait_for_all')),
  CONSTRAINT ck_workflow_stages_sla CHECK (sla_hours IS NULL OR sla_hours > 0),
  CONSTRAINT ck_workflow_stages_reminder CHECK (reminder_before_hours IS NULL OR (sla_hours IS NOT NULL AND reminder_before_hours > 0 AND reminder_before_hours < sla_hours)),
  CONSTRAINT ck_workflow_stages_escalation_target CHECK (escalation_target IN ('none','department_head','named_user')),
  CONSTRAINT ck_workflow_stages_escalation_user CHECK ((escalation_target = 'named_user') = (escalation_user_id IS NOT NULL))
);
CREATE INDEX ix_workflow_stages_tenant_id ...; CREATE INDEX ix_workflow_stages_template ON workflow_stages (template_id) WHERE is_deleted = false;

CREATE TABLE workflow_stage_participants (
  participant_id UUID PK, tenant_id UUID NOT NULL REFERENCES tenants, stage_id UUID NOT NULL,
  assignee_type VARCHAR(20) NOT NULL, assignee_id UUID NOT NULL,     -- soft ref (M10)
  display_order INT NOT NULL DEFAULT 0, <audit block>,
  CONSTRAINT uq_workflow_stage_participants_tenant_participant UNIQUE (tenant_id, participant_id),
  CONSTRAINT fk_workflow_stage_participants_stage FOREIGN KEY (tenant_id, stage_id) REFERENCES workflow_stages (tenant_id, stage_id),
  CONSTRAINT ck_workflow_stage_participants_assignee_type CHECK (assignee_type IN ('user','role','department'))
);
CREATE UNIQUE INDEX uq_workflow_stage_participants_stage_assignee
    ON workflow_stage_participants (stage_id, assignee_type, assignee_id) WHERE is_deleted = false;
CREATE INDEX ix_workflow_stage_participants_tenant_id ...;
CREATE INDEX ix_workflow_stage_participants_assignee ON workflow_stage_participants (tenant_id, assignee_type, assignee_id) WHERE is_deleted = false;  -- "is role/dept in use?" checks
```
The `reminder_before_hours`, `escalation_target` and `escalation_user_id` columns are stored in Phase 1 but have no behaviour until Phase 2.

### V45__create_workflow_runtime.sql
```sql
CREATE TABLE workflow_instances (
  instance_id UUID PK, tenant_id UUID NOT NULL REFERENCES tenants,
  document_id UUID NOT NULL, document_version_id UUID NOT NULL,
  template_id UUID NOT NULL, template_version_number INT NOT NULL,
  current_stage_instance_id UUID,
  status VARCHAR(20) NOT NULL DEFAULT 'in_progress',
  initiated_by UUID NOT NULL REFERENCES users (user_id),
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
  outcome_reason VARCHAR(2000), version_changed_flag BOOLEAN NOT NULL DEFAULT false,
  <audit block>,
  CONSTRAINT uq_workflow_instances_tenant_instance UNIQUE (tenant_id, instance_id),
  CONSTRAINT fk_workflow_instances_document FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, document_id),
  CONSTRAINT fk_workflow_instances_document_version FOREIGN KEY (tenant_id, document_version_id) REFERENCES document_versions (tenant_id, version_id),
  CONSTRAINT fk_workflow_instances_template FOREIGN KEY (tenant_id, template_id) REFERENCES workflow_templates (tenant_id, template_id),
  CONSTRAINT ck_workflow_instances_status CHECK (status IN ('in_progress','approved','rejected','cancelled')),
  CONSTRAINT ck_workflow_instances_completed CHECK ((status = 'in_progress') = (completed_at IS NULL)),
  CONSTRAINT ck_workflow_instances_outcome_reason CHECK (status NOT IN ('rejected','cancelled') OR length(btrim(outcome_reason)) > 0)
);
-- Decision 8: one in_progress instance per document (deliberately no is_deleted predicate)
CREATE UNIQUE INDEX uq_workflow_instances_one_in_progress
    ON workflow_instances (tenant_id, document_id) WHERE status = 'in_progress';
CREATE INDEX ix_workflow_instances_tenant_id ...;
CREATE INDEX ix_workflow_instances_document ON workflow_instances (tenant_id, document_id, started_at DESC);
CREATE INDEX ix_workflow_instances_initiator ON workflow_instances (tenant_id, initiated_by, status);

CREATE TABLE workflow_stage_instances (
  stage_instance_id UUID PK, tenant_id UUID NOT NULL REFERENCES tenants,
  instance_id UUID NOT NULL, stage_id UUID NOT NULL, stage_order INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'not_started',
  entry_count INT NOT NULL DEFAULT 0, required_count INT NOT NULL DEFAULT 0,
  approved_count INT NOT NULL DEFAULT 0, rejected_count INT NOT NULL DEFAULT 0,
  activated_at TIMESTAMPTZ, due_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, <audit block>,
  CONSTRAINT uq_workflow_stage_instances_tenant_stage_instance UNIQUE (tenant_id, stage_instance_id),
  CONSTRAINT uq_workflow_stage_instances_instance_order UNIQUE (instance_id, stage_order),
  CONSTRAINT fk_workflow_stage_instances_instance FOREIGN KEY (tenant_id, instance_id) REFERENCES workflow_instances (tenant_id, instance_id),
  CONSTRAINT fk_workflow_stage_instances_stage FOREIGN KEY (tenant_id, stage_id) REFERENCES workflow_stages (tenant_id, stage_id),
  CONSTRAINT ck_workflow_stage_instances_status CHECK (status IN ('not_started','active','approved','rejected','skipped')),
  CONSTRAINT ck_workflow_stage_instances_counts CHECK (approved_count >= 0 AND rejected_count >= 0
      AND approved_count + rejected_count <= entry_count AND required_count <= entry_count)
);
-- invariant: at most one active stage per instance
CREATE UNIQUE INDEX uq_workflow_stage_instances_one_active
    ON workflow_stage_instances (instance_id) WHERE status = 'active';
CREATE INDEX ix_workflow_stage_instances_tenant_id ...;
CREATE INDEX ix_workflow_stage_instances_due ON workflow_stage_instances (due_at) WHERE status = 'active' AND due_at IS NOT NULL; -- Phase 2 SLA scan

ALTER TABLE workflow_instances ADD CONSTRAINT fk_workflow_instances_current_stage
  FOREIGN KEY (tenant_id, current_stage_instance_id) REFERENCES workflow_stage_instances (tenant_id, stage_instance_id)
  DEFERRABLE INITIALLY DEFERRED;   -- circular, same approach as documents ↔ document_versions

CREATE TABLE workflow_tasks (
  task_id UUID PK, tenant_id UUID NOT NULL REFERENCES tenants,
  stage_instance_id UUID NOT NULL, participant_id UUID NOT NULL,
  assignee_type VARCHAR(20) NOT NULL, assignee_id UUID NOT NULL,
  claimed_by UUID REFERENCES users (user_id), claimed_at TIMESTAMPTZ,
  delegated_from UUID REFERENCES users (user_id), delegated_to UUID REFERENCES users (user_id), delegated_at TIMESTAMPTZ, -- Phase 2
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  decision VARCHAR(10), decision_reason VARCHAR(2000),
  acted_by UUID REFERENCES users (user_id), acted_at TIMESTAMPTZ,
  due_at TIMESTAMPTZ, reminder_sent_at TIMESTAMPTZ, escalated_at TIMESTAMPTZ,                                    -- Phase 2
  <audit block>,
  CONSTRAINT uq_workflow_tasks_tenant_task UNIQUE (tenant_id, task_id),
  CONSTRAINT uq_workflow_tasks_stage_participant UNIQUE (stage_instance_id, participant_id),   -- one task per entry
  CONSTRAINT fk_workflow_tasks_stage_instance FOREIGN KEY (tenant_id, stage_instance_id) REFERENCES workflow_stage_instances (tenant_id, stage_instance_id),
  CONSTRAINT fk_workflow_tasks_participant FOREIGN KEY (tenant_id, participant_id) REFERENCES workflow_stage_participants (tenant_id, participant_id),
  CONSTRAINT ck_workflow_tasks_assignee_type CHECK (assignee_type IN ('user','role','department')),
  CONSTRAINT ck_workflow_tasks_status CHECK (status IN ('pending','claimed','awaiting_info','approved','rejected','delegated','expired','cancelled')),
  CONSTRAINT ck_workflow_tasks_decision CHECK (decision IS NULL OR decision IN ('approve','reject')),
  CONSTRAINT ck_workflow_tasks_decided CHECK ((status IN ('approved','rejected')) = (decision IS NOT NULL AND acted_by IS NOT NULL AND acted_at IS NOT NULL)),
  CONSTRAINT ck_workflow_tasks_decision_matches CHECK (decision IS NULL OR (decision = 'approve') = (status = 'approved')),
  CONSTRAINT ck_workflow_tasks_reject_reason CHECK (status <> 'rejected' OR length(btrim(decision_reason)) > 0),
  CONSTRAINT ck_workflow_tasks_claimed CHECK (status <> 'claimed' OR claimed_by IS NOT NULL)
);
-- WA-FR-013: a user decides at most once per stage instance
CREATE UNIQUE INDEX uq_workflow_tasks_one_decision_per_user
    ON workflow_tasks (stage_instance_id, acted_by) WHERE status IN ('approved','rejected');
CREATE INDEX ix_workflow_tasks_tenant_id ...;
CREATE INDEX ix_workflow_tasks_stage_instance ON workflow_tasks (stage_instance_id);
CREATE INDEX ix_workflow_tasks_open_assignee ON workflow_tasks (tenant_id, assignee_type, assignee_id) WHERE status IN ('pending','claimed');
CREATE INDEX ix_workflow_tasks_claimed_by ON workflow_tasks (tenant_id, claimed_by) WHERE status = 'claimed';

CREATE TABLE workflow_actions (
  action_id UUID PK, tenant_id UUID NOT NULL REFERENCES tenants,
  instance_id UUID NOT NULL, stage_instance_id UUID, task_id UUID,
  action_type VARCHAR(40) NOT NULL, actor_user_id UUID REFERENCES users (user_id),  -- NULL = system
  reason VARCHAR(2000), detail JSONB NOT NULL DEFAULT '{}'::jsonb,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_workflow_actions_instance FOREIGN KEY (tenant_id, instance_id) REFERENCES workflow_instances (tenant_id, instance_id),
  CONSTRAINT fk_workflow_actions_stage_instance FOREIGN KEY (tenant_id, stage_instance_id) REFERENCES workflow_stage_instances (tenant_id, stage_instance_id),
  CONSTRAINT fk_workflow_actions_task FOREIGN KEY (tenant_id, task_id) REFERENCES workflow_tasks (tenant_id, task_id),
  CONSTRAINT ck_workflow_actions_action_type CHECK (action_type IN (
     'submitted','claimed','released','approved','rejected',
     'stage_activated','stage_approved','stage_rejected','stage_unassignable',
     'workflow_approved','workflow_rejected','cancelled','version_changed','restarted',
     -- Phase 2 (allowed now, unused):
     'reassigned','delegated','escalated','reminded','commented',
     'info_requested','info_provided','info_declined','info_cancelled'))
);
CREATE INDEX ix_workflow_actions_tenant_id ...;
CREATE INDEX ix_workflow_actions_instance ON workflow_actions (instance_id, occurred_at);
REVOKE UPDATE, DELETE ON workflow_actions FROM PUBLIC;   -- mirror V23:79 exactly as written there
```

### V46__seed_workflow_and_department_permissions.sql
- Uses the V33/V40 pattern.
- Adds an `IAM` action `MANAGE_DEPARTMENTS` and the permissions `iam.manage-departments.{view,create,update,delete}`.
- All `wa.*` and `dlm.submit-documents-for-review` codes already exist, so nothing new is needed for WA.
- No grants are given to any role, which follows the repo convention.

### Rollbacks
`V43…V46.rollback.sql` drop children first:
1. actions
2. tasks
3. the stage-instance FK and stage instances
4. instances
5. participants
6. stages
7. templates
8. `users.department_id`
9. departments

The V46 rollback deletes the seeded rows.

---

## 3. State machines

### Workflow instance
| From | To | Trigger |
|---|---|---|
| (none) | `in_progress` | `POST /workflow/instances` (submission) or a restart re-submission |
| `in_progress` | `approved` | The last stage instance becomes `approved` |
| `in_progress` | `rejected` | Any stage instance becomes `rejected`. `outcome_reason` is set from the deciding rejection reason. |
| `in_progress` | `cancelled` | Withdrawn by the initiator or a tenant admin (with a reason), or a version-change **restart** |

`approved`, `rejected` and `cancelled` are terminal. `current_stage_instance_id` is set to NULL on any terminal transition.

### Stage instance
| From | To | Trigger |
|---|---|---|
| (none) | `not_started` | All stage instances are created at submission |
| `not_started` | `active` | Stage 1 activates at submission. Stage *k* activates when stage *k−1* becomes `approved`. |
| `active` | `approved` | `approved_count >= required_count` |
| `active` | `rejected` | `halt_immediately`: the first rejection arrives. `wait_for_all`: every entry has responded (`approved_count + rejected_count = entry_count`) and `approved_count < required_count`. |
| `not_started` | `skipped` | The instance became rejected or cancelled before this stage was reached |
| `active` | `skipped` | The instance was cancelled while this stage was active |

Required counts: `all` → n, `any` → 1, `majority` → floor(n/2)+1.

### Task
| From | To | Trigger |
|---|---|---|
| (none) | `pending` | The stage activates (one task per participant entry) |
| `pending` | `claimed` | `POST /tasks/{id}/claim`. Only for **pool** tasks (role or department). |
| `claimed` | `pending` | `POST /tasks/{id}/release`, by the claimer |
| `pending`/`claimed` | `approved`/`rejected` | `approve` or `reject` by an eligible user. Acting on a pending pool task claims it implicitly (`claimed_by`/`claimed_at` are set). |
| `pending`/`claimed` | `cancelled` | The stage concluded without this entry, or the instance was rejected, cancelled or restarted |
| `pending`/`claimed` | `expired` | *Phase 2 SLA job.* Allowed by the CHECK, never written in Phase 1. |
| *Phase 2* | `awaiting_info` / `delegated` | Allowed by the CHECK, never written in Phase 1 |

`approved`, `rejected`, `cancelled` and `expired` are terminal.

---

## 4. Approve/reject algorithm

```text
@Transactional  WorkflowTaskServiceImpl.act(taskId, decision, reason)
  tenant = TenantContext.getTenantId(); me = TenantContext.getUserId()
  if decision == REJECT and isBlank(reason): throw 400 WORKFLOW_REASON_REQUIRED   (bean validation + service guard)

  t0  = taskRepo.findByTaskIdAndTenantIdAndDeletedFalse(taskId, tenant)  -> 404 if missing   (no lock: just to find parents)
  si0 = stageInstanceRepo.findByIdAndTenant(t0.stageInstanceId, tenant)

  -- lock order: instance -> stage instance -> tasks (by task_id)   (§6)
  inst = instanceRepo.lockByInstanceIdAndTenantId(si0.instanceId, tenant)             -- LOCK 1
  si   = stageInstanceRepo.lockByStageInstanceIdAndTenantId(si0.id, tenant)           -- LOCK 2 (decision 6)
  open = taskRepo.lockOpenByStageInstance(si.id)  -- status IN (pending, claimed) ORDER BY task_id FOR UPDATE   -- LOCK 3
  task = open.find(taskId)

  -- guards (re-evaluated under the locks, in this order so each scenario-E refusal gets its own code)
  inst.status != in_progress                  -> 409 WORKFLOW_NOT_IN_PROGRESS
  si.status   != active                       -> 409 WORKFLOW_STAGE_NOT_ACTIVE      (WA-FR-013 "not yet reached"/closed)
  me == inst.initiatedBy                      -> 403 WORKFLOW_SELF_APPROVAL_FORBIDDEN
  taskRepo.existsDecisionBy(si.id, me)        -> 409 WORKFLOW_ALREADY_ACTED_IN_STAGE (WA-FR-013; DB backstop uq_workflow_tasks_one_decision_per_user)
  task == null:                                  (task is no longer open; re-read t0's current status)
       t0.status in (approved, rejected)      -> 409 WORKFLOW_ENTRY_SATISFIED       (another pool member already decided this entry)
       otherwise (cancelled/expired)          -> 409 WORKFLOW_TASK_CLOSED
  !eligibility.canAct(task, me)               -> 403 WORKFLOW_NOT_ASSIGNEE
       user task : task.assigneeId == me
       pool task : (status=claimed and claimedBy==me) or (status=pending and me ∈ livePool(task))

  -- record the decision for this entry (the entry is now satisfied; each entry has exactly one task)
  if task is pool and task.status == pending: task.claimedBy = me; task.claimedAt = now   (implicit claim)
  task.status = APPROVED|REJECTED; task.decision; task.decisionReason = reason; task.actedBy = me; task.actedAt = now
  if APPROVE: si.approvedCount++ else si.rejectedCount++
  actions.record(approved|rejected, inst, si, task, me, reason)
  audit WORKFLOW_TASK_APPROVED|WORKFLOW_TASK_REJECTED

  -- evaluate the stage (pure function: helper/StageOutcomeEvaluator)
  n = si.entryCount; req = si.requiredCount; a = si.approvedCount; r = si.rejectedCount
  outcome =
     a >= req                                       ? APPROVED     -- approval short-circuits under every rule
   : r > 0 && rule == halt_immediately              ? REJECTED     -- first rejection halts
   : rule == wait_for_all && (a + r) == n           ? REJECTED     -- everyone has responded, a < req
   :                                                  STILL_OPEN   -- wait_for_all keeps collecting, even when
                                                                   -- approval is already out of reach
  -- completion=all + wait_for_all: a rejection does NOT end the stage; it rejects only once all n have responded.
  -- Scenario H (majority, n=3, req=2, wait_for_all): Legal approves (a=1) -> open; Finance rejects (a=1,r=1) -> open;
  --   Dept Head approves -> a=2 -> APPROVED, or rejects -> a+r=3, a<2 -> REJECTED.

  switch outcome:
   STILL_OPEN: save; return
   APPROVED:
     cancelRemaining(open - task, "stage_approved")   -- other entries' tasks no longer needed; no-op under 'all'
     si.status = approved; si.completedAt = now; actions.record(stage_approved)
     next = stageInstanceRepo.findFirstByInstanceIdAndStatusOrderByStageOrder(inst.id, not_started)
     if next: activateStage(inst, next)   (§5)  -> inst.currentStageInstanceId = next.id
     else:    inst.status = approved; completedAt = now; current = null
              actions.record(workflow_approved); audit WORKFLOW_APPROVED; publish WorkflowOutcomeEvent
   REJECTED:
     cancelRemaining(open - task, "workflow_rejected")
     si.status = rejected; si.completedAt = now; actions.record(stage_rejected)
     markLaterStagesSkipped(inst)      -- not_started -> skipped
     -- the concluding act may be an approval under wait_for_all, so take the reasons from the stage's rejected tasks
     inst.status = rejected; completedAt = now; current = null
     outcomeReason = rejectedTasks(si).size == 1
                       ? thatTask.decisionReason                       -- verbatim (Scenario B mail asserts the exact text)
                       : join("; ", rejectedTasks(si).map(actorName + ": " + decisionReason)) truncated to 2000
     actions.record(workflow_rejected, detail={rejections:[{taskId, actedBy, reason}]}); audit WORKFLOW_REJECTED; publish WorkflowOutcomeEvent
  save all; return TaskActionResponse(task, stage status, instance status)

cancelRemaining(tasks, cause): for t in tasks: t.status = cancelled; actions.record(task_cancelled-as-detail of stage event)
  (one stage_* action whose detail lists cancelled task ids; this keeps the timeline readable)
```

Pool cancellation in plain terms: a pool is **one** task, so satisfying the entry means closing that task. "Cancel remaining" only affects *other entries'* open tasks once the **stage** has concluded, or all open tasks once the workflow halts.

---

## 5. Approver resolution at stage activation (`service/impl/ApproverResolverImpl`)

```text
activateStage(inst, si):
  participants = participantRepo.findByStageIdAndTenantIdAndDeletedFalseOrderByDisplayOrder(si.stageId, tenant)
  for p in participants:
    candidates = resolve(p) − {inst.initiatedBy}
       user       : users where user_id = p.assigneeId and status = 'active' and is_deleted = false (same tenant)
       role       : users u JOIN user_roles ur (ur.role_id = p.assigneeId, ur.tenant_id = tenant, ANY scope_type — Q3)
                    JOIN roles r (r.status='active', not deleted); u.status='active', not deleted
       department : users where department_id = p.assigneeId (direct members only), status='active', not deleted;
                    the department itself must be active
    candidates = candidates ∩ holdersOf(wa.approve-reject-workflow-task.approve|reject)   -- see Risk R4
    task = new pending task (assignee_type/id = p, participant_id = p, due_at = si.dueAt)
    if candidates empty: actions.record(stage_unassignable, detail={participantId}); publish StageUnassignableEvent
    resolved[p] = candidates
  si.entryCount = participants.size; si.requiredCount = required(rule, n); si.status = active
  si.activatedAt = now; si.dueAt = slaHours ? now + slaHours : null
  inst.currentStageInstanceId = si.id
  actions.record(stage_activated, detail={entries:[{participantId, type, id, resolvedUserIds}]})
  publish WorkflowTaskAssignedEvent(tenant, instanceId, taskIds)   -- recipients are recomputed after commit
```

- **What "resolved at activation" means:**
  - The stage's entries are fixed and the tasks are created at activation.
  - Each pool's members are computed then: for the empty-pool check, for notification recipients, and as an audit snapshot in the `stage_activated` detail.
  - **Eligibility to act is checked against live membership** at claim or act time. This needs no candidate table and matches the ERD. Confirmed (Q2).
- **Empty pool:**
  - **At submission**, the submit service dry-runs `resolve()` for **every** stage of the template. If any entry is empty, it throws 422 `WORKFLOW_NO_ELIGIBLE_APPROVERS` with `{stageOrder, stageName, participant}` and writes nothing.
  - A `user` entry that names the initiator is reported the same way, as `WORKFLOW_INITIATOR_IS_APPROVER`.
  - **Mid-flight** (a pool emptied after submission): the task is created and parked, `stage_unassignable` is recorded, and the holders of `wa.reassign-workflow-task`, tenant admins and the initiator are mailed. The stage waits until a member is added (eligibility is live) or Phase 2 reassignment.

---

## 6. Concurrency strategy

**Global lock order.** Every path acquires locks in this order and never goes backwards:

```
documents  →  workflow_instances  →  workflow_stage_instances  →  workflow_tasks (ascending task_id)
document_types (template activation only; never combined with the chain above)
```

| Path | Locks taken |
|---|---|
| Submit | `documents` row (new `DocumentRepository.lockByDocumentIdAndTenantId`), then inserts. The backstop is `uq_workflow_instances_one_in_progress`; a 23505 violation maps to 409 `WORKFLOW_ALREADY_IN_PROGRESS`. |
| Approve/reject | instance → stage instance → open tasks of that stage, ordered by `task_id`. The approve path never touches `documents`. |
| Claim / release | **No explicit lock.** One conditional `UPDATE workflow_tasks SET status='claimed', claimed_by=:me ... WHERE task_id=:id AND tenant_id=:t AND status='pending' AND assignee_type IN ('role','department')`. Zero rows updated means 409 `WORKFLOW_TASK_ALREADY_CLAIMED`. It holds only one task row lock and never waits on a parent row, so it cannot form a cycle with the approve path. If a stage is concluding, the UPDATE blocks on the task row, re-checks `status='pending'` and fails cleanly. |
| Cancel (withdraw) | instance → stage instance → open tasks |
| Version change (listener inside the promoter's transaction) | `documents` → instance → stage instance → tasks. Restart then inserts the new instance under the same document lock. |
| Template activate | `DocumentTypeRepository.lockByDocumentTypeIdAndTenantId` (the existing schema-publish pattern), then deactivate the old active row, flush, and activate the new one. The backstop is `uq_workflow_templates_one_active_per_type`. |

Why this is enough to prevent "two last approvers":
- Locking the instance alone already serialises every mutation on that instance.
- The stage-instance lock is still taken, as decision 6 requires, and in a fixed order.
- Both approvers are serialised. The second one re-reads the counters under the lock and either finds the stage already concluded (409 `WORKFLOW_STAGE_NOT_ACTIVE`) or sees the up-to-date counts.

Lock timeouts:
- A W1 spike confirms how Hibernate 7 applies `jakarta.persistence.lock.timeout` on PostgreSQL. The fallback is `SET LOCAL lock_timeout = '5s'` issued at the start of the engine transaction.
- New handling in `GlobalExceptionHandler`: `PessimisticLockingFailureException` (which covers `CannotAcquireLockException` for timeouts and deadlock losers) maps to **409 `CONCURRENT_UPDATE`**, which is retryable.
- The UI retries once and then refreshes.

---

## 7. Build steps

Below, `J` = `backend/dms-core/src/main/java/com/oak/dms`, `T` = `backend/dms-core/src/test/java/com/oak/dms`, `F` = `frontend`, and every API path starts with `/api/v1`.

### W1 Confirm scope and spike (S, low risk)
- **Deliverables:**
  - This plan, with the questions resolved (§10).
  - Spike A: how Hibernate 7 applies the lock-timeout hint on PostgreSQL. Write a throwaway test that `lockBy...` with the hint produces `SET LOCAL lock_timeout` or a `NOWAIT` equivalent, using a 5 s timeout (Q8).
  - Spike B: whether entity mapping of the generated column (`stage_order_uniq_key` with `insertable=false, updatable=false`) passes `ddl-auto: validate`. Copy what `DocumentType` does for `display_order_uniq_key`.
  - Spike C: where `DocumentVersionPromoter.persist` is called from (the upload request, the scan worker, bulk import) and whether `TenantContext` is bound there. This decides whether the version-change listener can use `TenantContext` or must rely only on the event payload.
  - Spike D (Q1): run the full existing `*IT` suite once against a fresh CI-like database through the new failsafe execution. Confirm the exclusion list (the Keycloak-dependent ITs), and report any other failures before the execution goes live in CI.
- **Parallel:** all four spikes are independent.

### W2 Schema and migrations (M, medium risk)
- **Deliverables:** migrations V43–V46 and their rollbacks as described in §2.
- **Entities:**
  - `J/department/entity/Department.java`
  - `J/workflow/entity/{WorkflowTemplate, WorkflowStage, WorkflowStageParticipant, WorkflowInstance, WorkflowStageInstance, WorkflowTask, WorkflowAction}.java`
- **Enums** in `J/workflow/enums/`: `TemplateStatus, CompletionRule, RejectionRule, AssigneeType, EscalationTarget, OnDocumentChange, InstanceStatus, StageInstanceStatus, TaskStatus, TaskDecision, WorkflowActionType`. The constants are lowercase and include the Phase 2 values, so that reads never fail.
- **Other changes:**
  - `J/user/User.java` gets `departmentId`.
  - Repositories with `lockBy*` methods (§6).
  - `J/document/DocumentRepository.java` gets `lockByDocumentIdAndTenantId`.
- **Business rules:** the CHECKs and partial indexes from §2. Entities must validate against the schema.
- **Tests:**
  - `T/workflow/V43DepartmentsMigrationTest`, `V44WorkflowTemplatesMigrationTest`, `V45WorkflowRuntimeMigrationTest` and `V46...MigrationTest`, following the V41 pattern: assert the columns, CHECKs, partial-index predicates and composite FKs, then run the rollback and re-migrate.
  - Extra assertions: a second `active` template for the same type fails with 23505; a second `in_progress` instance for the same document fails; two `active` stage instances fail; a duplicate decision by the same user fails; a reject without a reason fails the CHECK.
- **Dependencies:** W1. Everything else depends on W2.

### W3 Template CRUD (M, medium risk)
- **Files:**
  - `J/workflow/controller/WorkflowTemplateController`
  - `service/WorkflowTemplateService` + `impl/`
  - `dto/{CreateWorkflowTemplateRequest, UpdateWorkflowTemplateRequest, WorkflowTemplateResponse, WorkflowTemplateSummaryResponse}`
  - `helper/WorkflowTemplateMapper`
  - `exception/{WorkflowStateException (409), WorkflowValidationException (422)}`, both subclassing `ApiException` with a code.
- **Endpoints:**

| Method | Path | Body / response | Permission |
|---|---|---|---|
| GET | `/workflow/templates?status&documentTypeId&search&page&size&sort` | `PageResponse<WorkflowTemplateSummaryResponse{templateId, code, name, versionNumber, status, documentType{id,name}, stageCount, hasInstances, updatedAt}>` | any `wa.configure-workflow-templates.*` |
| GET | `/workflow/templates/{id}` | `WorkflowTemplateResponse{…, onDocumentChange, stages[{stageId, stageOrder, name, completionRule, rejectionRule, slaHours, reminderBeforeHours, escalationTarget, escalationUserId, participants[{participantId, assigneeType, assigneeId, displayName, displayOrder}]}]}` | same |
| GET | `/workflow/templates/{id}/versions` | Every version sharing the same code | same |
| POST | `/workflow/templates` | `{name, code, description?, documentTypeId, onDocumentChange?}` → 201, a draft at v1 | `.create` |
| PUT | `/workflow/templates/{id}` | `{name, description, documentTypeId (v1 drafts only), onDocumentChange}` | `.update` |
| POST | `/workflow/templates/{id}/versions` | Deep-copies the template, stages and participants into a new draft at max+1 → 201 | `.update` |
| POST | `/workflow/templates/{id}/activate` | Validates (see below) and makes this the active template for its document type | `.toggle-active` |
| POST | `/workflow/templates/{id}/deactivate` | active → inactive | `.toggle-active` |
| DELETE | `/workflow/templates/{id}` | Soft delete | `.delete` |

- **Business rules:**
  - **Only `draft` templates can be edited** (template fields and stages). Because drafts are never instantiated, "a template with instances is versioned, not edited" holds automatically.
  - `code` is uppercase, matches `[A-Z0-9_-]{2,50}`, and cannot change after creation.
  - The document type must be `active` and belong to the tenant.
  - Activation requires at least one stage, at least one participant per stage, and every assignee to still exist and be active.
  - Activation locks the `document_types` row, demotes any other active template for that type to `inactive`, and audits both changes.
  - Deactivating a template blocks **new** submissions only. In-flight instances continue.
  - Delete is allowed only for `draft` or `inactive` templates with no instances. Otherwise it returns 409 `WORKFLOW_TEMPLATE_IN_USE`.
  - Creating a new version when a draft already exists for that code returns 409 `WORKFLOW_DRAFT_EXISTS`.
  - Condition-based routing later can be added as a new nullable `condition` column on templates, by relaxing `uq_..._one_active_per_type` in a Phase-N migration, or as a separate routing table. Phase 1 code resolves the template through a single `TemplateSelector.selectFor(document)` method so that this stays a local change.
- **Tests:**
  - Unit: the validator and the code normalisation.
  - IT (`WorkflowTemplateIT`): CRUD, draft-only edits, activation demotes the previous template, a two-thread activation race leaves exactly one active, versioning copies stages and participants, delete is blocked when in use, cross-tenant access returns 404, a missing permission returns 403.
- **Dependencies:** W2. Runs in parallel with W9 and W16a.

### W4 Stage configuration (M, medium risk)
- **Files:** `J/workflow/controller/WorkflowStageController`, `service/WorkflowStageService` + `impl/`, `dto/{UpsertWorkflowStageRequest, ParticipantRequest, ReorderStagesRequest}`.
- **Endpoints** (draft templates only; all require `wa.configure-workflow-templates.update`):

| Method | Path | Body |
|---|---|---|
| POST | `/workflow/templates/{id}/stages` | `{name, completionRule, rejectionRule, slaHours?, reminderBeforeHours?, escalationTarget?, escalationUserId?, participants[{assigneeType, assigneeId, displayOrder}]}`. The stage is appended at max+1. |
| PUT | `/workflow/templates/{id}/stages/{stageId}` | Same body. The participant list is **replaced**: rows are soft-deleted and new ones inserted. |
| DELETE | `/workflow/templates/{id}/stages/{stageId}` | Soft delete, then compact the remaining orders to 1..n in the same transaction (the deferrable unique constraint allows this). |
| PUT | `/workflow/templates/{id}/stages/order` | `{stageIds:[…]}` must be an exact permutation of the live stages. Orders are reassigned to 1..n. |
| GET | `/departments?status=active` (W16a), plus the existing users and roles list endpoints | Approver pickers |

- **Rules:**
  - Each stage has 1 to 50 participants, with no duplicate `(type, id)` pairs.
  - Each assignee must exist in the tenant and be active (user `status='active'`, role `status='active'`, department `status='active'`).
  - `sla_hours` is between 1 and 8760.
  - `reminder_before_hours` is less than `sla_hours`.
  - `named_user` requires `escalationUserId`. The escalation fields are stored now and used in Phase 2.
  - `majority` with n = 1 or 2 is allowed but warned about in the UI: it means "all".
  - The approver is **not** expanded here (decision 2).
- **Tests:**
  - Unit: participant validation.
  - IT: add, update, delete and reorder; the reorder permutation is checked; a soft-deleted stage frees its order; editing a non-draft template returns 409; an inactive role or user is rejected; a cross-tenant `assigneeId` is rejected (422).
- **Dependencies:** W3. The UI for this is W17a.

### W5 Submit to workflow (M, high risk)
- **Files:** `J/workflow/controller/WorkflowInstanceController`, `service/WorkflowSubmissionService` + `impl/`, `service/WorkflowEngine` (an internal component with `activateStage`, `evaluate`, `advance`, `halt`, `cancel`), `service/ApproverResolver` + `impl/`, `service/WorkflowActionRecorder` (writes `workflow_actions`).
- **Endpoints:**

| Method | Path | Body / response | Permission |
|---|---|---|---|
| POST | `/workflow/instances` | `{documentId}` → 201 `WorkflowInstanceResponse` (the W8 shape) | `dlm.submit-documents-for-review` |
| POST | `/workflow/instances/{id}/cancel` | `{reason}` (required) → 200 | initiator with the submit permission, or `@authz.isTenantAdmin` |

- **Algorithm (one transaction):**
  1. Lock the document. Return 404 if it is missing or in another tenant.
  2. The document status must be `draft` or `published`. Otherwise return 409 `WORKFLOW_DOCUMENT_NOT_ELIGIBLE`.
  3. If an `in_progress` instance exists, return 409 `WORKFLOW_ALREADY_IN_PROGRESS`.
  4. `TemplateSelector.selectFor(doc)` finds the active template. If there is none, return 422 `WORKFLOW_NO_ACTIVE_TEMPLATE`.
  5. Dry-run approver resolution for every stage. An empty pool returns 422 `WORKFLOW_NO_ELIGIBLE_APPROVERS`. An entry naming the initiator returns 422 `WORKFLOW_INITIATOR_IS_APPROVER`.
  6. Insert the instance, snapshotting `document_version_id = doc.latestVersionId` and `template_version_number`.
  7. Insert every stage instance as `not_started`.
  8. `activateStage(stage 1)`.
  9. Record the `submitted` action and audit `WORKFLOW_SUBMITTED`.
  10. Publish `WorkflowTaskAssignedEvent`.
- Any failure rolls back everything, so a half-created instance is impossible (decision 11). The partial unique index catches a submit race.
- **Cancel** marks open tasks `cancelled`, the active stage and `not_started` stages `skipped`, and the instance `cancelled` with its `outcome_reason`. It records the `cancelled` action, audits it and mails the initiator if the cancel was done by an admin.
- **Tests:**
  - IT `WorkflowSubmissionIT` covers: the happy path (instance, N stage instances, stage 1 active, tasks, `required_count` correct for all/any/majority with n = 1…5); no active template gives 422 with zero rows written; already in a workflow gives 409; an empty pool gives 422 with zero rows; initiator as approver gives 422; an archived document gives 409; missing permission gives 403; cross-tenant gives 404.
  - Concurrency `WorkflowSubmitConcurrencyIT`: ten threads submit the same document, producing one 201, nine 409s and exactly one instance.

### W6 Approve/reject with auto-advance (L, high risk)
- **Files:**
  - `J/workflow/controller/WorkflowTaskController`
  - `service/WorkflowTaskService` + `impl/` (implementing §4)
  - `helper/StageOutcomeEvaluator`, a pure static function
  - `helper/TaskEligibility`
- **Endpoints:**

| Method | Path | Body | Permission |
|---|---|---|---|
| POST | `/workflow/tasks/{taskId}/approve` | `{comment?}` (stored as `decision_reason`, which is optional for approve) | `wa.approve-reject-workflow-task.approve` |
| POST | `/workflow/tasks/{taskId}/reject` | `{reason}` (required, `@NotBlank`, max 2000) | `wa.approve-reject-workflow-task.reject` |

  The response is `TaskActionResponse{taskId, taskStatus, stageInstance{id, status, approvedCount, rejectedCount, requiredCount}, instance{id, status, currentStageInstanceId}}`.
- **Rules:** as in §4. Auto-advance runs in the same transaction.
- **Tests:**
  - Unit `StageOutcomeEvaluatorTest`: a parameterised test over n = 1…6 × {all, any, majority} × {halt, wait} × every reachable (a, r) with a + r ≤ n. It checks each row against the oracle below, plus these named rows:

    | Completion | Rejection | n | req | a | r | Expected | Why |
    |---|---|---|---|---|---|---|---|
    | all | halt | 3 | 3 | 1 | 1 | REJECTED | first rejection halts |
    | all | wait | 3 | 3 | 1 | 1 | STILL_OPEN | must wait for the third entry |
    | all | wait | 3 | 3 | 2 | 1 | REJECTED | all responded, 2 < 3 |
    | all | wait | 3 | 3 | 3 | 0 | APPROVED | |
    | any | halt | 3 | 1 | 0 | 1 | REJECTED | |
    | any | wait | 3 | 1 | 0 | 2 | STILL_OPEN | one entry still to respond |
    | any | wait | 3 | 1 | 0 | 3 | REJECTED | all responded, 0 < 1 |
    | any | wait | 3 | 1 | 1 | 1 | APPROVED | approval short-circuits |
    | majority | wait | 3 | 2 | 1 | 1 | STILL_OPEN | **Scenario H** after Legal approves and Finance rejects |
    | majority | wait | 3 | 2 | 2 | 1 | APPROVED | H: Dept Head approves |
    | majority | wait | 3 | 2 | 1 | 2 | REJECTED | H: Dept Head rejects |
    | majority | wait | 4 | 3 | 0 | 2 | STILL_OPEN | approval already impossible, but still waits |
    | majority | wait | 4 | 3 | 2 | 2 | REJECTED | 2–2 split, all responded |
    | majority | halt | 4 | 3 | 2 | 1 | REJECTED | |
    | majority | wait | 4 | 3 | 3 | 0 | APPROVED | short-circuits; 4th task cancelled |

    Oracle: `a >= req → APPROVED; halt && r > 0 → REJECTED; wait && a + r == n → REJECTED; else STILL_OPEN`.
  - IT `WorkflowApprovalIT` covers:
    - A 3-stage sequential approval reaching `approved`.
    - `any` short-circuits and cancels the other tasks.
    - `majority` with n = 4: three approvals are needed. With `wait_for_all`, a 2–2 split rejects only after the 4th response.
    - `halt_immediately` rejects on the first rejection and cancels the other open tasks.
    - `wait_for_all` under `all`: after one rejection the stage stays `active` and the remaining tasks stay actionable. It rejects only after the last response.
    - `wait_for_all` under `majority`, n = 4: after 0 approvals and 2 rejections the stage is still `active` and both open tasks can still be acted on.
    - Scenario H at the engine level (completion `all`, Legal / Finance / Dept Head): see `WorkflowScenariosIT` H in W20a. The evaluator rows `all/wait n=3 (1,1) → STILL_OPEN` and `(2,1) → REJECTED` cover it at unit level.
    - A rejection marks later stages `skipped` and sets the instance's `outcome_reason`.
    - Acting twice on the same stage returns 409.
    - Acting on a `not_started` stage returns 409.
    - Self-approval returns 403.
    - A non-member acting on a pool task returns 403.
    - Reject without a reason returns 400.

### W7 Stage guards, parallel rule, locking, version change (L, high risk)
- **Deliverables:**
  - Lock methods in the repositories, the lock-timeout hint (from Spike A) and the `GlobalExceptionHandler` mapping to 409 `CONCURRENT_UPDATE`.
  - Every guard in §4 is enforced and the database backstops are mapped to coded 409s by inspecting the constraint name in `DataIntegrityViolationException`.
  - **Version change:**
    - Add `J/document/event/DocumentVersionCreatedEvent(tenantId, documentId, previousLatestVersionId, newVersionId, actorUserId?)`, published from `DocumentVersionPromoter.persist` after `latestVersionId` is updated.
    - Add `J/workflow/service/impl/DocumentVersionChangeListener` with a **synchronous** `@EventListener`, so it runs in the promoter's transaction.
    - The listener locks the document and then the instance. With `flag_only` it sets `version_changed_flag=true` (idempotently), records `version_changed {from, to}` and audits `WORKFLOW_DOCUMENT_VERSION_CHANGED`.
    - With `restart` it cancels the old instance (reason "Restarted: document version changed") and re-submits under the **currently active** template, with the original initiator and the new version, then records `restarted`.
    - If the re-submission preconditions fail, it falls back to flag-only and records why in the action detail.
    - **An upload never fails because of the workflow.** The listener catches `ApiException` and falls back. Unexpected errors still propagate, because the listener shares the upload's transaction.
- **Tests:**
  - **Concurrency `WorkflowApprovalConcurrencyIT`** (an `ExecutorService` with a `CountDownLatch` start gate, each thread in its own transaction and JWT):
    1. `all`, n = 2: both approve together. Exactly one transaction advances the stage, the next stage is activated **once**, `approved_count=2`, and there is exactly one `stage_activated` action for stage 2.
    2. `any`, n = 3: three approve together. Exactly one success; the others get 409 `TASK_CLOSED` or `STAGE_NOT_ACTIVE`; the remaining tasks are cancelled.
    3. Approve vs reject under `halt_immediately`: a deterministic final state with counters that match the task rows.
    4. Claim vs approve on the same pool task: no lost update, and the final state is valid.
    5. Approve vs cancel: one wins and the other gets 409.
    6. Twenty threads claim one pool task: exactly one claimer.
  - Every scenario asserts that no deadlock occurs and that the counters equal `count(tasks where status = …)`.
  - Version change: flag mode sets the flag and the instance continues; restart mode leaves the old instance `cancelled` and a new one `in_progress` on the new version; a restart with no active template falls back to the flag; an upload with no workflow is unaffected.

### W8 Status API (S, low risk)
- **Files:** `service/WorkflowQueryService` + `impl/`, `dto/{WorkflowInstanceResponse, StageInstanceView, TaskView, WorkflowActionView}`.
- **Endpoints:**

| Method | Path | Response | Permission |
|---|---|---|---|
| GET | `/workflow/instances/{id}` | `{instanceId, document{id, title, latestVersionId}, documentVersionId, versionChangedFlag, template{id, code, name, versionNumber}, status, initiatedBy{id, displayName}, startedAt, completedAt, outcomeReason, currentStageInstanceId, stages[{stageInstanceId, stageOrder, name, status, completionRule, rejectionRule, entryCount, requiredCount, approvedCount, rejectedCount, activatedAt, dueAt, completedAt, tasks[{taskId, assigneeType, assignee{id, displayName}, status, claimedBy, actedBy, decision, decisionReason, actedAt, dueAt}]}], nextAssignees[…], myOpenTaskId?}` | record-level visibility (below) |
| GET | `/documents/{documentId}/workflow-instances?page` | History, newest first | same |
| GET | `/workflow/instances/{id}/actions?page` | `PageResponse<WorkflowActionView{actionType, actor, reason, detail, occurredAt, stageOrder}>`, the UI timeline | same |

- **Visibility:** the caller can see an instance if any of these is true:
  - they are the initiator
  - they appear as `acted_by` or `claimed_by` on any task
  - they are eligible for an open task
  - they hold `wa.view-workflow-audit-trail.view`
  - they are a tenant admin

  Otherwise throw `AccessDeniedException`, which returns 403 and is audited. Cross-tenant access returns 404.
- Data is loaded with a few batched queries (stage instances, tasks, user display names) to avoid N+1.
- `nextAssignees` lists the open tasks of the current stage (WA-FR-005).
- **Tests:** an IT covering the response shape at each lifecycle point, plus the visibility matrix.

### W9 Audit event emission (S, low risk)
- **Files:**
  - `J/audit/catalog/AuditActionCatalog.java`: add module-`WA` entries.

    | Action | Severity | Reason required |
    |---|---|---|
    | `WORKFLOW_TEMPLATE_CREATED`, `_UPDATED`, `_STAGES_UPDATED`, `_VERSION_CREATED` | MEDIUM | no |
    | `WORKFLOW_TEMPLATE_ACTIVATED`, `_DEACTIVATED` | MEDIUM | no |
    | `WORKFLOW_TEMPLATE_DELETED` | HIGH | no |
    | `WORKFLOW_SUBMITTED` | MEDIUM | no |
    | `WORKFLOW_TASK_CLAIMED`, `_RELEASED` | LOW | no |
    | `WORKFLOW_TASK_APPROVED` | MEDIUM | no |
    | `WORKFLOW_TASK_REJECTED` | MEDIUM | **yes** |
    | `WORKFLOW_STAGE_COMPLETED` | LOW | no |
    | `WORKFLOW_APPROVED` | MEDIUM | no |
    | `WORKFLOW_REJECTED`, `WORKFLOW_CANCELLED` | HIGH | **yes** |
    | `WORKFLOW_DOCUMENT_VERSION_CHANGED`, `WORKFLOW_RESTARTED` | MEDIUM | no |

    Module `IAM` gets `DEPARTMENT_CREATED`, `_UPDATED`, `_DELETED` and `USER_DEPARTMENT_CHANGED`.
  - `AuditObjectTypes`: `WORKFLOW_TEMPLATE`, `WORKFLOW_INSTANCE`, `WORKFLOW_TASK`, `DEPARTMENT`.
  - `J/workflow/helper/WorkflowAuditPayloads`, which builds the `newValue` maps.
  - `docs/events/workflow-audit-events.md`, documenting the format.
- **Event format:** standard `audit_events` columns, with:
  - `object_type`/`object_id` = the instance or task
  - `resource_name` = the document title
  - `object_version` = `template_version_number`
  - `new_value` = `{instanceId, documentId, documentVersionId, templateId, templateCode, templateVersionNumber, stageInstanceId, stageOrder, stageName, taskId, assigneeType, assigneeId, decision, counts{approved, rejected, required, entries}}`
  - `previous_value` for template edits
  - `reason` = the rejection or cancel reason
- Events are emitted in the same transaction as the state change. `workflow_actions` stays the separate UI history (decision 13).
- From the version listener, when no `TenantContext` is bound, the event uses `.tenantId(...)` and `.systemActor()` or `.actor(...)`.
- **Tests:** a unit test checking every new action is registered; an IT checking that one audit row is written per transition with the right fields and that a reject without a reason throws.
- **Dependencies:** W2 for the object ids. The catalog work can start right after W2.

### W10 Task model and My Tasks with claim (M, medium risk)
- **Files:** `WorkflowTaskController` (shared with W6), `repository/WorkflowTaskQueryRepository` (JPQL, or `JdbcTemplate` if the pool join is awkward), `dto/{MyTaskResponse, MyTaskCountResponse}`.
- **Endpoints:**

| Method | Path | Notes | Permission |
|---|---|---|---|
| GET | `/workflow/tasks/my?view=all\|assigned\|available\|claimed&page&size&sort=dueAt` | `PageResponse<MyTaskResponse{taskId, instanceId, document{id, title, typeName}, templateName, stage{order, name, totalStages}, assigneeType, poolName, status, claimedByMe, dueAt, overdue, activatedAt, initiatedBy{id, displayName}, versionChangedFlag}>` | authenticated (own tasks only) |
| GET | `/workflow/tasks/my/count` | `{open, overdue}`, used for the nav badge | authenticated |
| POST | `/workflow/tasks/{id}/claim` | conditional UPDATE (§6) | approve **or** reject permission |
| POST | `/workflow/tasks/{id}/release` | claimer only: claimed → pending | same |

- **Query:** first compute `myRoleIds` (from active `user_roles`) and `myDeptId`. Then select tasks where `status IN ('pending','claimed')`, the stage instance is `active` and the instance is `in_progress`, and any of these holds:
  - `assignee_type='user' AND assignee_id=:me`
  - `status='claimed' AND claimed_by=:me`
  - `status='pending' AND assignee_type='role' AND assignee_id IN :myRoleIds`
  - `status='pending' AND assignee_type='department' AND assignee_id=:myDeptId`

  Then exclude instances where `initiated_by=:me` and stage instances where I have already decided. This uses `ix_workflow_tasks_open_assignee`.
- Phase 2 adds `OR delegated_to=:me` as one extra clause.
- **Rules:** claiming a `user` task returns 409 `WORKFLOW_TASK_NOT_CLAIMABLE`. Claiming as the initiator returns 403. Claiming as a non-member returns 403. A claimed pool task disappears from other members' "available" view.
- **Tests:** an IT checking that each view is correct for a user, a role member, a department member, the initiator and a claimer; claim/release semantics; pagination and sorting. The claim race is in W7.

### W13a Core notifications (S/M, medium risk)
- **Files:**
  - `J/workflow/event/{WorkflowTaskAssignedEvent, WorkflowOutcomeEvent, WorkflowStageUnassignableEvent}` records that carry `tenantId`.
  - `J/workflow/service/impl/WorkflowNotificationListener`, built like `ThreatNotificationListener`: `@TransactionalEventListener(AFTER_COMMIT)`, then `applicationTaskExecutor`, with a per-recipient try/catch.
  - `J/workflow/helper/WorkflowNotificationMailer`, built like `ThreatNotificationMailer`: `SimpleMailMessage` with links to `${app.frontend-base-url}/tasks/{taskId}` and `/workflows/instances/{id}`.
  - `J/workflow/config/WorkflowProperties` (`dms.workflow.notifications.{enabled, mail-from}`), plus entries in `application.yml`.
- **Mails:**
  - **Task assigned:** to the user, or to every eligible pool member resolved after commit, minus the initiator.
  - **Outcome:** to the initiator, with the reason if rejected or cancelled (WA-FR-014).
  - **Unassignable:** to holders of `wa.reassign-workflow-task` (via `findUserIdsWithPermission`), tenant admins (reusing the recipient lookup from `ThreatNotificationListener`, extracted into a shared helper if it is private) and the initiator.
- Mail is best effort with no retry, matching the existing pattern. WA-NFR-004 ("not lost if offline") is met by the durable My Tasks queue.
- Phase 2 can move sending to the outbox and `notification-worker` without schema changes.
- **Tests:** unit tests for the recipient resolution and message content with a mocked `JavaMailSender`. An IT checking that mail is sent only after commit, and not at all on rollback (using `@MockitoBean JavaMailSender` and awaiting the executor).

### W16a Permissions and tenant filtering (M, medium risk)
- **Files:**
  - `J/security/rbac/catalog/PermissionCodes.java`: `WA_TEMPLATES_{CREATE,UPDATE,DELETE,TOGGLE_ACTIVE}`, `WA_TASK_{APPROVE,REJECT}`, `WA_TASK_REASSIGN` (Phase 2), `WA_AUDIT_TRAIL_{VIEW,EXPORT}`, `DLM_SUBMIT_DOCUMENTS_FOR_REVIEW`, `IAM_DEPARTMENTS_{VIEW,CREATE,UPDATE,DELETE}`.
  - The departments module `J/department/{controller/DepartmentController, service/DepartmentService + impl/, repository/DepartmentRepository, entity/Department, dto/…, helper/DepartmentMapper}`.
  - User department assignment through the existing `UpdateUserRequest`, `CreateUserRequest` and `UserResponse` (adding `departmentId` and `departmentName`) in `UserServiceImpl`.
- **Department endpoints:**

| Method | Path | Permission |
|---|---|---|
| GET | `/departments?status&search&page` | `iam.manage-departments.view` or any `wa.configure-workflow-templates.*` (the builder needs the picker) |
| GET | `/departments/{id}` | same |
| POST | `/departments` | `.create` |
| PUT | `/departments/{id}` | `.update` (prevents cycles and checks the head user is in the tenant) |
| DELETE | `/departments/{id}` | `.delete`; 409 if it has users, children, or is referenced by a non-deleted template participant |

- **Tenant filtering checklist:**
  - Every repository method takes `tenantId`.
  - Every lock method filters by tenant.
  - Composite FKs prevent cross-tenant links at the database level.
  - Assignee validation checks the tenant.
  - The My Tasks query filters by tenant.
  - Mail recipients are loaded per tenant.
- **Tests:** an IT `WorkflowTenantIsolationIT` in which tenant B tries every endpoint against tenant A's ids and always gets 404, and tenant A's assignee ids are rejected inside tenant B's template. A permission matrix IT covers each endpoint with and without its permission. Department CRUD and cycle prevention ITs.

### W17a Admin UI (L, medium risk)
- **Files** (following the Document Types and Quarantine screens):
  - `F/lib/auth/permission-codes.ts`: add the `wa.*`, `dlm.submit-documents-for-review` and `iam.manage-departments.*` codes.
  - `F/lib/workflow/{api.ts, use-workflow-templates.ts, use-workflow-template.ts, permissions.ts, workflow-badges.ts}`.
  - `F/lib/departments/{api.ts, use-departments.ts}`.
  - `F/app/admin/workflows/{page.tsx, workflows-content.tsx, new/page.tsx, [templateId]/page.tsx, [templateId]/edit/page.tsx}`.
  - `F/components/workflow/{workflow-templates-table.tsx, workflow-template-form.tsx, schema.ts, stage-builder.tsx, stage-card.tsx, stage-rules-fields.tsx, participant-picker.tsx, template-status-actions.tsx, template-versions.tsx}`.
  - A minimal departments admin: `F/app/admin/departments/{page.tsx, new, [id]/edit}` and `F/components/departments/*`, plus a department select on the user edit form. Without these, department pools cannot be set up (in scope per Q5).
  - `F/components/dashboard/nav-config.ts`: add admin entries for "Workflow templates" and "Departments".
- **UX:**
  - The list has status and document-type filters.
  - Create sets the name, code, document type and on-change behaviour.
  - The **stage builder** shows ordered cards, reordered with **up/down buttons**, since no drag-and-drop library is installed. Each card has completion and rejection segmented controls, SLA hours, and a participant picker with user, role and department tabs that reuses `searchable-select` and `multi-select`.
  - Read-only mode is used for non-draft templates, with a "Create new version" call to action.
  - Activate and deactivate go through confirmation dialogs.
  - The UI shows a warning for majority with n ≤ 2, and maps backend 409/422 codes to toasts.
- **Checks:** `bun run typecheck`, eslint on the changed paths, and a manual walkthrough (see Verification).

### W18a My Tasks screen and status panel (M/L, medium risk)
- **Files:**
  - `F/lib/workflow/{use-my-tasks.ts, use-my-task-count.ts, use-workflow-instance.ts, use-workflow-actions.ts}`.
  - `F/app/tasks/{page.tsx, tasks-content.tsx}`: a tabbed table (Assigned to me, Available to claim, Claimed by me) with claim and release buttons, an overdue badge and the version-changed badge.
  - `F/app/workflows/instances/[instanceId]/page.tsx`: the **status page** that hosts the panel.
  - `F/components/workflow/{workflow-status-panel.tsx (stage stepper/progress indicator, WA-NFR-003), stage-detail.tsx, workflow-timeline.tsx, task-action-dialog.tsx (approve with an optional comment, reject with a required reason via zod), submit-to-workflow-button.tsx}`.
  - `nav-config.ts`: the existing "Workflows" placeholder becomes `href: "/tasks"`, with the badge taken from `/workflow/tasks/my/count`.
  - Replace the mock `pending-approvals-panel.tsx` data with `useMyTasks`.
- **Near-real-time (WA-NFR-001):** react-query `refetchInterval` of 15 s on the task list and the instance view, plus refetch on window focus. Push updates are out of scope.
- **Minimal document page (Q6):** `F/app/documents/[documentId]/{page.tsx, document-content.tsx}` plus `F/lib/documents/{api.ts, use-document.ts}`.
  - It shows the title, type, status and latest version from the existing `GET /api/v1/documents/{id}` (requires `docm.documents.view`).
  - It has a **Submit for approval** button (`submit-to-workflow-button.tsx`), gated on `dlm.submit-documents-for-review` and hidden while an instance is `in_progress`. Backend 409/422 codes map to messages, e.g. "No active workflow template for this document type".
  - It embeds `workflow-status-panel.tsx` for the current instance, plus a short history list from `GET /documents/{id}/workflow-instances`.
  - Nothing more: no preview, metadata editing or version upload.
  - It is linked from My Tasks rows, the instance status page and the notification mails.
- **Status page:** `/workflows/instances/[id]` also hosts the same panel, for a deep link to a specific instance.

### W20a Tests (L; spread across all steps)
All of the tests listed per step above, plus:
- **Unit:** `StageOutcomeEvaluatorTest`, `RequiredCountTest`, `TaskEligibilityTest`, `ApproverResolverTest` (mocked repositories), `WorkflowNotificationMailerTest`, `AuditCatalogWorkflowTest`.
- **Migration:** `V43`–`V46*MigrationTest`. These are `*Test` classes, so CI runs them.
- **IT:**
  - Template, Stage, Submission, Approval, ApprovalConcurrency, SubmitConcurrency, MyTasks, Status/Visibility, VersionChange, TenantIsolation, PermissionMatrix, Department, Notification.
  - These use the existing hand-built MockMvc with `jwt()` helpers (`security/auth/ImpersonationIT.java:388-399`).
  - Each test class creates its own tenant and users, and permissions are granted through `RolePermissionRepository`/`UserRoleRepository`.
- A shared `T/workflow/support/WorkflowFixtures` builds tenants, users, roles, departments, document types, documents with versions, and templates.
- **End-to-end scenario ITs** (`T/workflow/WorkflowScenariosIT`, one `@Nested` class per scenario). Each scenario drives the real HTTP API end to end: submit, claim, approve or reject, then the status API, the `workflow_actions` timeline, the `audit_events` rows and the mails (via `@MockitoBean JavaMailSender`).

  **Shared fixture** (`WorkflowFixtures.contractScenario()`, a fresh tenant per nested class):
  - **Departments:** Legal (Saman, Dilani, Ruwan) and Finance (one member, `finance1`, used by H only).
  - **Roles:** "Dept Head", held by Kumari and Asanka.
  - **Users:** Nimal (CFO), plus Chamath as the initiator, who holds `dlm.submit-documents-for-review`. The approvers hold `wa.approve-reject-workflow-task.{approve,reject}`.
  - **Document types:** Contract and Invoice.
  - **Contract template** (active):
    - Stage 1 "Department Review": entries = Legal (department) and Dept Head (role). Completion `all`, rejection `halt_immediately` (B requires an immediate end), SLA 48 h.
    - Stage 2 "Final Sign-off": entry = Nimal (user). Completion `any`, SLA 24 h.
  - **Invoice:** no active template.
  - **Documents:** one Contract document owned by Chamath, with a clean version.

  | # | Steps | Assertions |
  |---|---|---|
  | **A** Happy path (comments/mentions skipped, Phase 2) | 1. Chamath `POST /workflow/instances` 2. Saman approves the Legal task 3. Kumari approves the Dept Head task 4. Nimal approves | After 1: 201; instance `in_progress`; stage 1 `active`, stage 2 `not_started`; 2 pool tasks (Legal, Dept Head); `required_count=2`, `entry_count=2`. After 2: `approved_count=1`; stage 1 still `active`; no stage 2 tasks; the Legal task no longer appears in Dilani's or Ruwan's `/workflow/tasks/my`. After 3: stage 1 `approved`, stage 2 `active`, exactly one task, assigned to Nimal, `due_at ≈ activated_at + 24h` (stage 1 `due_at ≈ +48h`). After 4: instance `approved`, `completed_at` set; Chamath receives exactly one outcome mail. **Timeline:** the user-driven actions (`actor_user_id` not null), in order, are `submitted, approved, approved, approved`. System actions (`stage_activated`, `stage_approved`, `workflow_approved`) are interleaved and asserted separately. |
  | **B** Rejection | 1. Submit 2. Asanka `POST …/reject` with `{}` 3. Asanka rejects with `"Payment terms exceed policy"` | Step 2: **400**, and nothing changes (counts, statuses, actions). Step 3: stage 1 `rejected`; the Legal task `cancelled`; stage 2 `skipped` with **no task rows**; instance `rejected` with `outcome_reason = "Payment terms exceed policy"` (verbatim). Nimal's My Tasks stays empty throughout. Chamath's outcome mail body contains that exact reason. The `WORKFLOW_REJECTED` audit row has `reason` set. |
  | **E** Out of turn | Each sub-case runs on its own instance, because any two stage 1 approvals close the stage. **E1:** Submit; Nimal tries to act on stage 1. **E2:** Submit; Kumari approves Dept Head; Kumari approves again. **E3:** Submit; Saman approves Legal; Dilani approves the same Legal task. | **E1:** refused, and nothing changes. **Note:** Nimal has no task until stage 2 activates (tasks are created at activation). The test asserts that his My Tasks is empty, that the status API shows stage 2 `not_started`, and that acting on either stage 1 task returns 403 `WORKFLOW_NOT_ASSIGNEE`. **E2:** second call → 409 `WORKFLOW_ALREADY_ACTED_IN_STAGE`. **E3:** Dilani → 409 `WORKFLOW_ENTRY_SATISFIED`. In every sub-case the counters, statuses and action count are unchanged by the refusal. |
  | **F** Document edited mid-approval | 1. Submit; Saman and Kumari approve (stage 1 `approved`, stage 2 `active`) 2. A new version of the Contract is ingested as Chamath | **F1 `flag_only`:** `version_changed_flag=true`; one `version_changed` action with `{from, to}`; the instance is still `in_progress` on the **original** `document_version_id`; Nimal can still approve, and then the instance is `approved`. **F2 `restart`:** the old instance is `cancelled` (`outcome_reason` mentions the version change), its open Nimal task is `cancelled`, and stage 2 is `skipped`. A new instance is `in_progress` with `document_version_id = new version`, on the currently active template, with stage 1 `active` and 2 fresh pool tasks. `restarted` is recorded on the old instance. At most one `in_progress` row exists for the document. **Note:** there is no HTTP endpoint for uploading a new version today (`IngestionRequest.newVersion` has no caller). The test drives the existing ingestion/scan-gate service path, so `DocumentVersionPromoter.persist` publishes the event as it would in production. |
  | **G** No chain | Create an Invoice document; Chamath submits it | **422** `WORKFLOW_NO_ACTIVE_TEMPLATE`. Row counts in `workflow_instances`, `workflow_stage_instances`, `workflow_tasks`, `workflow_actions` and the `WA` `audit_events` rows for the tenant are all **0** before and after. No mail is sent. |
  | **H** Parallel + rejection | A separate template, stage 1 with entries Legal (dept), Finance (dept) and Dept Head (role), completion `all`. **H1 `wait_for_all`:** Saman approves (Legal), finance1 rejects with a reason (Finance), Kumari acts (Dept Head). **H2 `halt_immediately`:** Saman approves, finance1 rejects. | **H1:** after the Finance rejection the stage is still `active` (`a=1, r=1`); the Dept Head task is still `pending` and actionable, and still appears in Kumari's My Tasks. When Kumari acts (tested both ways, approve and reject), the stage becomes `rejected` only then (`a+r=3`, `a<3`); the instance is `rejected`; later stages are `skipped`; `outcome_reason` holds the rejection reason(s). **H2:** right after the Finance rejection the stage and instance are `rejected`, and the Dept Head task is `cancelled`. Kumari acting then returns 409 `WORKFLOW_STAGE_NOT_ACTIVE`. |
  | C, D, I, J | **Phase 2**, not written in Phase 1 | |
- **CI (Q1):** a `maven-failsafe-plugin` execution is added to `backend/dms-core/pom.xml`, bound to `integration-test` and `verify` and including `**/*IT.java`, so `./dms-core/mvnw verify` in CI now runs the ITs against CI's postgres service (`DB_URL=jdbc:postgresql://localhost:5432/dms_ci`).
  - The existing ITs that need a live Keycloak (`ImpersonationIT`, `PermissionCodeAuditIT`) are excluded in that execution, since CI has no Keycloak service. The exclusion is listed explicitly in the pom with a comment.
  - **W1 runs the whole existing IT suite once** against a CI-like database before switching the execution on. Any other pre-existing IT that fails for environmental reasons is reported to you rather than silently excluded.
- **Frontend (Q7):** no test framework in Phase 1. Checks are `bun run typecheck` (or `npx tsc --noEmit`) and eslint on the changed paths, plus the manual walkthrough in Verification.

### Dependency and parallelism overview
```
W1 ─► W2 ─┬─► W3 ─► W4 ─────────────┐
          ├─► W9 (catalog)          ├─► W17a (UI; can start on agreed DTOs after W3 contract)
          ├─► W16a (dept + codes) ──┘
          └─► W5 ─► W6 ─► W7
                 ├─► W8 ─────────────┐
                 └─► W10 (claim/queue; act part after W6)
                          W6 ─► W13a  └─► W18a
W20a: written alongside each step; concurrency suite after W7.
```

| Step | Size | Risk |
|---|---|---|
| W1 | S | Low |
| W2 | M | Med |
| W3 | M | Med |
| W4 | M | Med |
| W5 | M | High |
| W6 | L | High |
| W7 | L | High |
| W8 | S | Low |
| W9 | S | Low |
| W10 | M | Med |
| W13a | S/M | Med |
| W16a | M | Med |
| W17a | L | Med |
| W18a | M/L | Med |
| W20a | L | Med |

---

## 8. Phase 2 compatibility check

| Phase 2 feature | What Phase 2 adds | Phase 1 rework needed? |
|---|---|---|
| Delegation / reassign | Writes the existing nullable `delegated_from`/`delegated_to`/`delegated_at` and changes the assignee in place on the same task. Adds `OR delegated_to = :me` to eligibility and the My Tasks query. Action types `reassigned`/`delegated` are already allowed. The permission `wa.reassign-workflow-task` already exists. | No. The `uq_workflow_tasks_stage_participant` constraint still holds because delegation does not create a new task. |
| Reminders / escalation | A scheduler using `FOR UPDATE SKIP LOCKED` (the `ScanResultRepositoryImpl` pattern) over `ix_workflow_stage_instances_due`. Writes `reminder_sent_at`/`escalated_at` and the `expired` status. Uses the existing `reminder_before_hours`/`escalation_*` columns. Action types `reminded`/`escalated` are allowed. | No. |
| Comments / mentions | New tables `document_comments` and `comment_mentions`, with an FK to `workflow_instances(tenant_id, instance_id)` using the existing `uq_workflow_instances_tenant_instance`. Action type `commented` is allowed. | No. |
| Information requests | `ALTER TABLE workflow_stages ADD allow_info_requests BOOLEAN NOT NULL DEFAULT true`. `ALTER TABLE workflow_tasks ADD sla_paused_at, sla_paused_seconds`. `ALTER TABLE workflow_actions ADD info_request_id` with an FK. New tables `workflow_info_requests` and `workflow_supporting_documents`. The `awaiting_info` status and the `info_*` actions are already in the CHECKs. | No. All additions are nullable or have defaults. In the engine, `awaiting_info` counts as an open, undecided task. Phase 1's `lockOpenByStageInstance` must therefore select by a single `TaskStatus.OPEN_STATUSES` constant set, which Phase 2 extends. |
| More notifications | New event records and new mailer methods, or move everything to the outbox/worker. | No. |
| Condition-based routing | Isolated behind `TemplateSelector`. The schema change is additive (a new condition column or table, and relaxing one partial index in the same migration that introduces conditions). | Isolated by design. |

---

## 9. Risks

- **R1 Concurrency correctness.** This is the highest risk. It is mitigated by the single lock order, the DB backstops (the four partial unique indexes and the count CHECKs) and the concurrency IT suite, which now runs in CI through failsafe (Q1).
- **R1b Turning on failsafe may surface pre-existing IT failures** in CI that have nothing to do with workflow. W1 checks this first; see W20a.
- **R2 Lock timeouts.** None exist in the repo today. The timeout is **5 s** (Q8). If Spike A shows the Hibernate hint is ignored on PostgreSQL, the engine transactions issue `SET LOCAL lock_timeout = '5s'` explicitly.
- **R2b `wait_for_all` keeps stages open longer.** A stage whose approval is already impossible stays `active` until the last entry responds, and those approvers still get tasks and mails. This is intended (Scenario H), but a parked entry from an empty pool (§5) under `wait_for_all` blocks the stage until Phase 2 reassign.
- **R3 Version-change hook.** It runs inside the upload/scan transaction. A bug in the workflow code could fail an upload, so the listener is defensive about `ApiException`. Spike C confirms the calling contexts and the tenant binding.
- **R4 Permission vs membership.** A pool member who lacks the approve/reject permission can't act. The resolver intersects members with permission holders, using the database role permissions only. Tenant admins hold everything through Keycloak roles, so they don't count toward pool non-emptiness unless they also hold the role. This is conservative and may report an empty pool when the only eligible users are tenant admins.
- **R5 Stuck stage from the one-decision rule.** Under `all`, if the same single user is the only eligible member of two entries, the stage can never complete. Phase 1 detects the simple case at submission (two entries whose candidate sets are both exactly {u}) and returns 422. The general case is documented and handled by Phase 2 reassign.
- **R6 422 codes.** No `ApiException` returns 422 today. The new `WorkflowValidationException` uses `HttpStatus.UNPROCESSABLE_CONTENT` through the existing handler, which works but is a new pattern.
- **R7 Frontend Next 16 differences.** Read `node_modules/next/dist/docs` first. `next build` already fails on HEAD because of the commented-out permission pages, so verify with `tsc --noEmit` and eslint.
- **R8 Users and roles pickers.** The existing users and roles list endpoints may require IAM permissions that a template designer doesn't have. If so, add `wa.configure-workflow-templates.*` to their `hasAnyPermission`, or add slim lookup endpoints under `/workflow/lookups/*`. W17a confirms which.

## 10. Resolved questions

| # | Question | Decision | Where it lands |
|---|---|---|---|
| Q1 | How do the engine ITs run in CI? | Add a **failsafe execution** to the pom so `*IT` runs in CI | W1 (pre-check of the existing ITs), W20a, `backend/dms-core/pom.xml` |
| Q2 | Pool eligibility | **Live** membership check at claim and act time. Pools are resolved at activation for the empty-pool check, mail recipients and the audit snapshot only. No candidates table. | §4 `livePool(task)`, §5, W10 |
| Q3 | Role pools vs `user_roles` scope | Role pools **ignore** `scope_type`/`scope_id`. Any active grant of the role counts. | §5 role resolution, M12 |
| Q4 | Restart target | A restart uses the **currently active** template for the document type, falling back to flag-only if there is none | W7 |
| Q5 | Departments admin UI | **In scope**: departments list/create/edit pages and a department field on the user form | W16a (API), W17a (UI) |
| Q6 | Where Submit lives | A new **minimal `/documents/[id]` page** with the submit button and status panel | W18a |
| Q7 | Frontend tests | **None in Phase 1**. Typecheck and lint only. | W20a |
| Q8 | Lock timeout | **5 seconds** | §6, W7, R2 |

The scenario definitions (A, B, E, F, G, H) were received and are specified in W20a.

---

## Verification (end to end)
1. **Build the backend:** `mvn clean verify` in `backend/dms-core`, using the bundled Maven per the env memory. All `*Test` classes must pass, including the V43–V46 migration tests.
2. **Run the ITs:** `-Dtest='*Test,*Tests,*IT'` against a scratch DB on the local Postgres (port 5433). `WorkflowApprovalConcurrencyIT` must be green on 5 consecutive runs.
3. **Manual run** (`spring-boot:run` on `SERVER_PORT=18080` with the frontend dev server; Mailpit at :8026 shows the mails):
   1. Create a department and assign users to it.
   2. Create a template with 2 stages: stage 1 is a role pool plus a department pool under `majority`; stage 2 is a single user. Activate it.
   3. Submit a document. Check the task mail arrives in Mailpit.
   4. Claim the task as a pool member and approve it. Check the stage advances, then approve stage 2. Check the initiator receives the outcome mail.
   5. Repeat with a rejection. Check the reason is required and later stages show as skipped.
   6. Upload a new version mid-flight. Check the version-changed flag, then repeat with the restart setting.
   7. Check the status page stepper and timeline, and check that `audit_events` contains the `WA` rows.
4. **Frontend:** `npx tsc --noEmit` and `npx eslint` on the changed paths.

---

## 11. Implementation notes (deviations found while building)

| Plan said | Built as | Why |
|---|---|---|
| Department permissions `iam.manage-departments.*` | `otua.manage-departments.{view,create,update,delete}` (V46, module OTUA) | `IAM` is the *Third-Party Integration* module. Organisation and user administration is `OTUA`. |
| Guard order in §4: instance status checked before stage status | Stage status first, then instance | A halted or finished workflow has no active stage, and `WORKFLOW_STAGE_NOT_ACTIVE` is the precise answer (Scenario H2). The other refusal codes are unchanged. |
| "Load the task, then lock" (§4 pseudocode) | Parent ids are fetched as **scalar queries**, so the first load of each row is its `FOR UPDATE` | Hibernate returns an entity already in the persistence context unchanged from a lock query. `WorkflowConcurrencyIT` caught two last approvers both reading `approved_count = 0`. The same fix is applied in the version-change listener. |
| Separate `WorkflowStageService` | Stage operations live in `WorkflowTemplateService`, behind their own `WorkflowStageController` | They share the draft-lock, audit and response building. The API paths are as planned. |
| User department set through `UpdateUserRequest` | `GET/PUT /api/v1/users/{id}/department` in `DepartmentController`. `UserResponse` gained `departmentId`. | Keeps the existing user-edit contract unchanged. Clearing a department is explicit (`departmentId: null`). |
| Lock timeout hint (Spike A) | `select set_config('lock_timeout', '5s', true)` at the start of each engine transaction (`WorkflowLocks`). `PessimisticLockingFailureException` returns 409 `CONCURRENT_UPDATE`. | Transaction-local and independent of Hibernate's PostgreSQL hint support. Verified by `WorkflowAccessIT.lockWaitTimesOutAfterFiveSeconds`. |
| Spike C | `DocumentVersionPromoter` never reads `TenantContext`. The listener uses only the event payload, and its audit falls back to the SYSTEM actor. | Promotion can run in the retry scheduler or a cloud callback. |
| Q1 failsafe | `maven-failsafe-plugin` runs `*IT` in `mvn verify`, excluding `ImpersonationIT` and `PermissionCodeAuditIT` (they need Keycloak) and `DocumentUploadIT` (it needs ClamAV). CI uploads failsafe reports too. | Spike D: every other IT passes on a fresh CI-like database. |
| W20a scenario tests use HTTP only | Documents and new versions are created by driving the real `DocumentVersionPromoter` with a clean scan result (`WorkflowFixtures`) | There is no HTTP endpoint for uploading a new version, and the scan gate needs ClamAV. The real version-change event still fires. |
| — | The single-reason rejection keeps the reason verbatim; several (`wait_for_all`) are joined `Name: reason; …` | Scenario B's mail must carry the exact reason. |
| R8: the users and roles pickers might need IAM permissions | Added `GET /api/v1/workflow/lookups/{users,roles,document-types}` (active rows, names only), readable with `wa.configure-workflow-templates.create/update` or `otua.manage-departments.create/update`. The stage builder, the template form and the department-head select use them. | The admin `/users`, `/roles` and `/document-types` lists need `otua.*`, `asm.*` and `mdtm.*` codes a designer doesn't hold. Covered by `WorkflowTemplateIT.lookups_*`. |
| W18a | Claim is **optional** in the UI. Approve/Reject are offered on any task in My Tasks and on the status panel. Deciding an unclaimed pool task claims it implicitly, as the backend does (Scenario A). | Matches the engine and Scenario A. |
| W18a status host | The status panel is on `/documents/[id]` (Q6) and on `/workflows/instances/[id]`. My Tasks is at `/tasks`. The nav "Workflows" placeholder became "My Tasks" with a live open-count badge. | — |

**Pre-existing issues found (not caused by this work):**
- On HEAD, `ImportTemplateRoundTripTest` was package-private while `BulkImportValidatorTest` imports from it, so *no* test sources compiled. It was made `public` (a visibility-only change).
- `ImportTemplateRoundTripTest.csvTemplate_carriesMarkerAndGuidanceLines_andReadsBack` then fails because the CSV writer quotes the `#` guidance lines. This is an importexport bug and was left untouched. It will fail `mvn verify` until fixed.
