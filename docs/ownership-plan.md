# Document Ownership and Transfer (DOT) – Plan

Status: PLAN ONLY. No application code has been written or changed. Only read-only git was used (`branch -r`, `ls-tree`, `status`). Waiting for "start execution"; my answers are then recorded in §14 and O2–O13 are built in one run.

## 0. Context
Oak DMS records one owner per document (`documents.owner_user_id`, set once at upload) and nothing else: no department, no transfer, no history, and deactivating a user leaves their documents and workflow tasks untouched. DOT adds an owner department, a holding state, an append-only ownership history, single and bulk transfer, department-level holding owners, an offboarding flow that transfers documents then deactivates the account, owner filters, notifications, and the admin UI. Everything is additive (tables/columns only). Locked decisions D1–D15 are applied as written; where the code forces a mechanism change I say so in §1 and §13.

## 1. Findings (verified in the code) and where the brief is wrong

| # | Brief assumption | Reality |
|---|---|---|
| F1 | `documents.owner_user_id` NOT NULL, set once in `DocumentVersionPromoter` (`.ownerUserId(uploadedBy)`), no owner dept, no owner field in catalog | **Confirmed.** V32 l.82: `owner_user_id UUID NOT NULL REFERENCES users (user_id)` (plain FK, not tenant-composite), index `ix_documents_owner_user_id`. Only writer: `DocumentVersionPromoter.persist` l.111-122 (new_document branch; `uploadedBy = scan_results.uploaded_by`, NOT NULL). Nothing else ever changes it. Small correction: `StarterCatalog` l.36 *does* have a metadata lookup field `department` ("Owning department or business unit") on CONTRACT/INVOICE/POLICY/GENERAL. It is free metadata, not wired to anything; **we leave it alone** and do not derive ownership from it. |
| F2 | Ownership does not affect access | **Confirmed.** `DocumentAccessView.level` (DocumentAccessService l.74-91): admin → manage; restricted doc → its own `document_access` entries; else folder level; templates capped at view; workflow-readable lifts none→view. `FolderAccessView`: no governing restricted folder (or `everyone`) ⇒ manage ("open"), top-level docs open; restricted governing folder ⇒ entries or none. An owner of a restricted document gets **no** implicit access. |
| F3 | Owner reads | Brief list confirmed; **two more found**: (a) `WorkflowSupportingDocumentServiceImpl` l.176-181 – for `uploaded_new` supporting docs the actor must equal `createdBy` OR `ownerUserId` (a transfer changes who may do this); (b) `DashboardService` l.214 native `count(*) filter (where d.owner_user_id = :me)` ("mine" tile) plus l.392-395/500 owner names. Full table in §9. |
| F4 | Six DOT permissions seeded and unused, no constants | **Confirmed** (V10 l.93-98; DOT module in V6/V7; frontend `components/roles/data.ts` lists them). `PermissionCodes.java` only holds codes that `@PreAuthorize` references; `permission-codes.ts` header says add only once a backend check exists → we add 5 used + `approve-transfer` declared-unused (precedent: `DRM_RELATIONSHIPS_VIEW_GRAPH`). |
| F5 | `deactivateUser` does nothing about documents/tasks | **Confirmed.** `UserServiceImpl.deactivateUser` l.404-435: loads via `findOwnedByCallerTenant`, refuses the tenant-admin account (live Keycloak realm-role check), sets `deleted=true, deletedAt, status=disabled`, calls `keycloakUserService.disableUser` (class `@Transactional` → a Keycloak failure rolls the DB write back), audits `USER_DEACTIVATED` (OTUA, HIGH, **reason required**). Endpoint `DELETE /api/v1/users/{id}` body `{reason}`, permission `otua.manage-users.deactivate`. Extra: `purgeUser` is a real `DELETE FROM users`; it fails on the plain FK `documents.owner_user_id` and on `workflow_instances.initiated_by` etc., and trigger `drop_access_entries_of_deleted_user` clears the user's access entries. |
| F6 | How names resolve for deleted/deactivated users | **No shared resolver.** Every site does its own `findAllById`/raw SQL **without** an `is_deleted` filter, so a *deactivated* user's name resolves everywhere (dashboard `DashboardService` l.500, version "created by", recycle bin, comments, workflow, relationship panel, department head/members). A *purged* user resolves to `null` at all of them (some fall back to email or "Someone"). Audit snapshots `actor_display_name`/`resource_name`. Lookups that **fail** for a deactivated user (`...AndDeletedFalse`): approver resolution, `requireEligibleTarget` (reassign), `EscalationTargetResolver`, `DepartmentServiceImpl.findTenantUser`, auth filter. → **History must not depend on the live `users` row**: the history API resolves via `findAllById` (works for deactivated) **and** falls back to name snapshots stored on the history row (new columns, Q2) for purged users. |
| F7 | departments.head_user_id; no holding owner; users.department_id | **Confirmed** (V91). `departments.head_user_id UUID REFERENCES users` (nullable), soft delete + `status active|inactive`, `uq_departments_tenant_department`. `users.department_id` nullable with composite FK. **No manager helper**; precedent `EscalationTargetResolver.headOf(deptId)` (department must be non-deleted and active; head may be null). |
| F8 | Reassign endpoint + how to list open tasks/runs | `POST /api/v1/workflow/tasks/{id}/reassign` (perm `wa.reassign-workflow-task`, body `{userId, reason}`) confirmed. **Corrections:** (1) there is **no repository method** for "open tasks held by user X": `WorkflowTaskRepository.findMyTasks` is parameterised by user but excludes runs the user initiated and stages they already decided and ignores pool tasks – unsuitable; I add a purpose-built query. (2) `TaskStatus.delegated` exists but is **never written**; delegation lives in `delegated_to`/`delegated_from`, escalation in `escalated_to`. Open = `pending, claimed, awaiting_info` in an `active` stage of an `in_progress` run. Holder = `delegated_to`, else `claimed_by`, else `assignee_id` when `assignee_type='user'` (`TaskRouting.holder`); also include tasks with `escalated_to = user`. (3) `workflow_instances.initiated_by` is `updatable=false` and has **no reassign** – runs started by the departing user can only be **flagged and cancelled** (`POST /workflow/instances/{id}/cancel`), not reassigned. D6's "reuse the existing Reassign" therefore applies to tasks only (Q4). (4) `requireEligibleTarget` rejects deactivated targets, so reassigning must happen before or independent of deactivation – the source holder is not validated, so an admin *can* still reassign a task away from an already-deactivated user. |
| F9 | Legal hold / archive not implemented; recycle bin / purge | **Confirmed.** Only inert traces: permissions `crm.place-legal-hold`/`crm.lift-legal-hold`, nullable `audit_events.legal_hold_flag`. No archive. `documents.status` never written. Purge is **soft**: `RecycleBinService.purge` (l.316-362) sets `purged_at`, row stays as tombstone with `owner_user_id`. Recycle-bin = `is_deleted=true AND purged_at IS NULL`. **Important:** `DocumentRepository.lockByDocumentIdAndTenantId` and `findByDocumentIdAndTenantIdAndDeletedFalse` both filter `deleted=false`, so D7 (transfer Recycle Bin docs) needs **new** lookup/lock queries that allow deleted and exclude purged. |
| F10 | Browse endpoint has no owner filter | **Confirmed**; `GET /api/v1/documents` (`DocumentController` l.140-166) → `DocumentServiceImpl.listDocuments` → **eight** queries (`findVisible`, `…InFolder`, `…AtTopLevel`, `…Related`, and the four `findLive*`) built from `LIVE_TITLED + NARROWED` JPQL constants. Add one `OWNED_BY` constant ANDed into all eight (the relationship filter precedent), so access filtering is untouched. |
| F11 | **Access grant mechanism (D4) does not work as written** | (a) Cache: invalidation is **not** a service duty – V61 DB triggers bump `access_generations` on any insert/update/delete of `document_access` and on `documents.access_mode` changes, so every write path invalidates. We still route through a service method (audit + consistency). (b) **Bigger problem:** `document_access` entries are **ignored unless `documents.access_mode = 'restricted'`** (`level()` only reads `granted` for restricted docs). A document in a restricted *folder* normally has mode `inherit`, so "add a document-level Can manage grant" does nothing for exactly the case the brief wants tested ("a restricted-folder document is openable by the new owner afterwards"). There is also no programmatic grant API: `updateAccess` needs `TenantContext`, manage rights and replaces the whole list. **Plan (Q1):** new `DocumentAccessService.ensureCanManage(tenantId, actorId, documents, userId)` (no TenantContext, set-based): restricted doc → upsert manage entry; `inherit` doc the new owner cannot open ⇒ convert it to `restricted`, **copying the governing folder's effective entries** (so nobody else loses access) + the new owner as manage. Previous owner is never removed (they are in the copied list if they had access). |
| F12 | RelationshipLocks / RelationshipKeys | `RelationshipLocks` (`@Component`): `applyLockTimeout()` (`set_config('lock_timeout','5s',true)`, `Propagation.MANDATORY`), `lockTypeForCycleCheck`. `RelationshipKeys.compare(UUID,UUID)` = string compare (matches Postgres `uuid` order; `UUID.compareTo` is signed and wrong). The "scalar id lookups then `lockByDocumentIdAndTenantId` ascending" loop is **private** in `RelationshipService.lockDocuments` l.436-446 → I write `OwnershipLocks` with the same pattern (sorted `select … for update`), reusing `applyLockTimeout()` and `RelationshipKeys.compare`. Lock timeout → `PessimisticLockingFailureException` → 409 `CONCURRENT_UPDATE`. Audit takes a global advisory lock → audit always **last**. |
| F13 | Highest Flyway on all branches | `origin/feature/ushani/OCR-scanning` **V112** (highest overall; its V108–V112 = signatures, visual signatures, position). This branch V109; `origin/feature/chamath/document-relationship` and `origin/Feat/bulk-import-export` V109; all others ≤V105. Our names V108/V109 already clash with OCR's V108/V109 (pre-existing merge issue, not ours). **DOT starts at V113**: V113 schema, V114 backfill, V115 categories. Re-check with `git ls-tree` before each migration; renumber at merge. |
| F14 | Rollback + migration-test convention | Manual rollbacks in `backend/dms-core/src/main/resources/db/rollback/V<n>__<name>.rollback.sql` (outside the Flyway location, run in reverse). Template test: `relationship/V106ToV109RelationshipMigrationTest` (plain JUnit, scratch DB per test, migrate to N-1, seed, migrate up, assert, run rollbacks reversed, delete `flyway_schema_history` rows, re-apply). It defaults to `localhost:5433` (the dev server) → always export `DB_URL/DB_USERNAME/DB_PASSWORD` for 5435. Append-only precedent: V103 `notification_attempts_immutable()` + `BEFORE UPDATE OR DELETE` trigger + `REVOKE UPDATE, DELETE … FROM PUBLIC`. |
| F15 | Audit | `AuditService.event(action)…log()`; action must be in `AuditActionCatalog` (constant + `Map.entry(X, new AuditActionSpec(module, severity, reasonRequired))`, `Map.ofEntries` fails at class load on duplicates); module string must equal a `permission_modules.module_code` – **`DOT` exists** (V6/V7). Object types in `AuditObjectTypes`. Frontend mirrors `lib/audit-events/audit-action-catalog.ts` (`AUDIT_ACTION_CATALOG`, `AUDIT_MODULE_LABELS` – **has no DOT label**) and `components/audit-log/audit-log-filters.tsx` `OBJECT_TYPE_OPTIONS` (already incomplete vs backend). Event doc template: `docs/events/relationship-audit-events.md`. |
| F16 | Notifications | `notification_categories` is global, seeded by migration (V103/V109: `INSERT … ON CONFLICT (category_code) DO NOTHING`). `NotificationRequest`: `documentId`, `neutralTitle/Body/LinkUrl`, `requireViewPermission`, `dedupeKey`, `sourceModule`. Resolver `NotificationAccessResolver` uses `DocumentAccessService.forUsers`. Listener convention (`AccessGrantedNotificationListener`, `RelationshipNotificationListener`): `@TransactionalEventListener(BEFORE_COMMIT)` so the notification is written in the same tx. **The brief says "event after commit"; I follow the project convention (BEFORE_COMMIT)** – `NotificationService.notify` is `@Transactional` and AFTER_COMMIT would need a new tx and could lose the notification. Deviation D-1. |
| F17 | nav-config "Governance group" | **There is none.** Administration groups: Access (Users, Roles, Permissions, Departments), Content setup, Workflow, Security, Policies, Platform; plus a "Documents" section. Item shape `{label, icon, href, permission?: string|string[]}`. → "Document Ownership" goes in **Administration → Access** (Q3). |
| F18 | Document page "Owner field" | Page `app/documents/[documentId]/page.tsx` shows **no owner at all** today (`DocumentDto.ownerUserId` exists but unused). Panels: Document, `RelatedDocumentsPanel`, `DiscussionPanel`, workflow panel. Owner field + history panel are new. Owner names today appear only on the dashboard. |
| F19 | Tests | `WorkflowApiSupport` (MockMvc + JWT, `fx.user/role/document/jwtFor/tenantAdminJwtFor`), `RelationshipApiSupport`, tenant isolation (404) and permission-matrix (403) patterns, `RelationshipConcurrencyIT` (latches + thread pool, "run 5×"), `@Tag("perf")` opt-in with `-Pperf`. `*IT` run by failsafe. Frontend: vitest 5 + Testing Library (`npm test`), `typecheck`, `lint`; Next 16.2.6 – read `node_modules/next/dist/docs/` first. No Testcontainers: needs the 5435 Postgres. Possible gotcha: fixtures that delete documents will be blocked by the composite FK from the append-only `ownership_transfers`; scratch tenants/DBs are used instead. |
| F20 | Tenant admin account | No stored admin pointer except provisioning audit. Reuse `UserPermissions.tenantAdminIds(tenantId)` (Keycloak realm-role lookup, set of ids); the D5 last resort = oldest active (not deleted, `status=active`) of those. |

Everything else in the brief was confirmed. No git state was touched.

## 2. Assumptions (no reply needed unless you disagree)
- A1 Templates (`is_template`) and Recycle Bin documents appear in the owned list (flagged `template` / `inRecycleBin`) and **are transferred**; purged ones are skipped (D7).
- A2 "Manager" (D3) = active head of a non-deleted, active department; the scope is users whose `users.department_id` equals a department they head (direct only, no child departments). A non-admin holding DOT codes who heads no department can do nothing (403 `OWNERSHIP_SCOPE_DENIED`). In-tenant scope denial is 403; hidden-document and cross-tenant cases are 404 (D8/D14).
- A3 Target owner must be an active (`status=active`, not deleted) user of the tenant; a given target department must be non-deleted and active. `newOwnerDepartmentId` defaults to the new owner's `users.department_id` (may be null).
- A4 Transfer to the same owner **and** same department **and** state `assigned` is a skip (`already_owner`), never a history row. Moving a `holding` document to the same user as `assigned` is a real transfer (state change).
- A5 Audit: single transfer → one `DOCUMENT_OWNERSHIP_TRANSFERRED`; bulk/offboarding → one `OWNERSHIP_BATCH_TRANSFERRED` per batch (counts, source, target, batch id) – **not** one per document (5,000 audit writes under the global chain lock would hurt). Per-document facts live in `ownership_transfers` (NFR-001). Offboarding adds `USER_OFFBOARDED` (+ the existing `USER_DEACTIVATED`).
- A6 A bulk request is **one database transaction** (locks all affected documents ascending, then chunked set-based statements of 500). All-or-nothing: a lock timeout returns 409 and nothing changes. Chunking is for statement size/memory only.
- A7 `mode:"all"` over a scope with >5,000 documents is refused (422 `OWNERSHIP_BATCH_TOO_LARGE`, with the total); the caller may pass `limit` (≤5000, lowest ids first) and repeat – transferred documents leave the scope so repeating converges. The Offboard dialog and the bulk dialog do this loop automatically with progress (NFR-002: no per-document effort).
- A8 Document-level Can-manage conversion only touches documents the new owner cannot open; `docm.documents.view` is a role permission we cannot fix – if the new owner lacks it, the result carries `newOwnerLacksViewPermission: true` (a warning, not an error).
- A9 History/export visibility: both need `dot.view-ownership-history.*`; non-admins additionally see only documents they can open (404 on a single hidden document; filtered rows in export). Tenant admins see all.
- A10 Offboarding flag list is computed inside the transaction after locking the user row, so counts in the confirmation cannot go stale (race → 409 `OFFBOARD_CONFIRMATION_REQUIRED` with fresh numbers).
- A11 Holding chain skips the departing user: `holding_owner_user_id`, then `head_user_id`, then oldest active tenant admin; each candidate must be active, in the tenant, and not the source user.

## 3. Migration plan (V113 onward; renumber on collision; all rollbacks in `db/rollback/`)

**V113__create_document_ownership.sql**
```sql
-- 1. documents: owner department + state
ALTER TABLE documents
    ADD COLUMN owner_department_id UUID NULL,
    ADD COLUMN ownership_state VARCHAR(10) NOT NULL DEFAULT 'assigned',
    ADD CONSTRAINT ck_documents_ownership_state CHECK (ownership_state IN ('assigned','holding')),
    ADD CONSTRAINT fk_documents_owner_department FOREIGN KEY (tenant_id, owner_department_id)
        REFERENCES departments (tenant_id, department_id);          -- MATCH SIMPLE: NULL skips the check
CREATE INDEX ix_documents_tenant_owner ON documents (tenant_id, owner_user_id) WHERE purged_at IS NULL;
CREATE INDEX ix_documents_owner_department ON documents (tenant_id, owner_department_id)
    WHERE owner_department_id IS NOT NULL AND purged_at IS NULL;
CREATE INDEX ix_documents_holding ON documents (tenant_id, owner_department_id)
    WHERE ownership_state = 'holding' AND purged_at IS NULL;

-- 2. departments: holding owner
ALTER TABLE departments
    ADD COLUMN holding_owner_user_id UUID NULL REFERENCES users (user_id) ON DELETE SET NULL;

-- 3. batches (inserted once, at the end of the transaction)
CREATE TABLE ownership_transfer_batches (
    batch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    kind VARCHAR(16) NOT NULL,
    source_user_id UUID NULL,                 -- plain ids: the user may be purged later
    source_department_id UUID NULL,
    new_owner_user_id UUID NOT NULL,
    new_owner_department_id UUID NULL,
    reason VARCHAR(16) NOT NULL,
    total_count INT NOT NULL,
    transferred_count INT NOT NULL,
    skipped_count INT NOT NULL,
    skipped_summary JSONB NOT NULL DEFAULT '{}'::jsonb,   -- {"already_owner":3,"purged":1,...}
    access_granted_count INT NOT NULL DEFAULT 0,
    converted_to_restricted_count INT NOT NULL DEFAULT 0,
    flagged_task_count INT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ NULL,
    note VARCHAR(1000) NULL,
    CONSTRAINT uq_ownership_batches_tenant_batch UNIQUE (tenant_id, batch_id),
    CONSTRAINT ck_ownership_batches_kind CHECK (kind IN ('single','bulk','offboarding','reorganization')),
    CONSTRAINT ck_ownership_batches_reason CHECK (reason IN ('manual','offboarding','reorganization','holding')),
    CONSTRAINT ck_ownership_batches_counts CHECK (transferred_count + skipped_count = total_count)
);
CREATE INDEX ix_ownership_batches_tenant_created ON ownership_transfer_batches (tenant_id, created_at DESC);

-- 4. history (append-only)
CREATE TABLE ownership_transfers (
    transfer_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL,
    previous_owner_user_id UUID NOT NULL,
    previous_owner_department_id UUID NULL,
    new_owner_user_id UUID NOT NULL,
    new_owner_department_id UUID NULL,
    reason VARCHAR(16) NOT NULL,
    batch_id UUID NULL,
    actor_user_id UUID NOT NULL,
    access_granted BOOLEAN NOT NULL DEFAULT false,
    note VARCHAR(1000) NULL,
    previous_owner_name VARCHAR(200) NULL,     -- snapshots so history survives a purged user (Q2)
    new_owner_name VARCHAR(200) NULL,
    actor_name VARCHAR(200) NULL,
    transferred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ownership_transfers_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT fk_ownership_transfers_batch FOREIGN KEY (tenant_id, batch_id)
        REFERENCES ownership_transfer_batches (tenant_id, batch_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_ownership_transfers_reason CHECK (reason IN ('manual','offboarding','reorganization','holding'))
);
CREATE INDEX ix_ownership_transfers_document ON ownership_transfers (tenant_id, document_id, transferred_at DESC);
CREATE INDEX ix_ownership_transfers_batch ON ownership_transfers (tenant_id, batch_id);
CREATE INDEX ix_ownership_transfers_time ON ownership_transfers (tenant_id, transferred_at DESC);
CREATE INDEX ix_ownership_transfers_prev ON ownership_transfers (tenant_id, previous_owner_user_id);
CREATE INDEX ix_ownership_transfers_new ON ownership_transfers (tenant_id, new_owner_user_id);

CREATE FUNCTION ownership_transfers_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ownership_transfers is append-only (% blocked)', TG_OP;
END; $$;
CREATE TRIGGER trg_ownership_transfers_immutable BEFORE UPDATE OR DELETE ON ownership_transfers
    FOR EACH ROW EXECUTE FUNCTION ownership_transfers_immutable();
REVOKE UPDATE, DELETE ON ownership_transfers FROM PUBLIC;
```
Rollback `V113__create_document_ownership.rollback.sql`: `DROP TABLE IF EXISTS ownership_transfers; DROP FUNCTION IF EXISTS ownership_transfers_immutable(); DROP TABLE IF EXISTS ownership_transfer_batches; ALTER TABLE departments DROP COLUMN IF EXISTS holding_owner_user_id; DROP INDEX IF EXISTS ix_documents_holding, ix_documents_owner_department, ix_documents_tenant_owner; ALTER TABLE documents DROP CONSTRAINT IF EXISTS fk_documents_owner_department, DROP CONSTRAINT IF EXISTS ck_documents_ownership_state, DROP COLUMN IF EXISTS ownership_state, DROP COLUMN IF EXISTS owner_department_id;`

**V114__backfill_document_owner_department.sql** (set-based, idempotent, does not touch `updated_at`; documents' triggers only fire on `access_mode`/`is_deleted` changes so no access-generation bump)
```sql
UPDATE documents d SET owner_department_id = u.department_id
  FROM users u
 WHERE u.user_id = d.owner_user_id AND u.tenant_id = d.tenant_id
   AND u.department_id IS NOT NULL AND d.owner_department_id IS NULL;
```
Rollback: `UPDATE documents SET owner_department_id = NULL;` (comment: also undone by V113 rollback; run only if V114 alone is rolled back).

**V115__seed_ownership_notification_categories.sql**
```sql
INSERT INTO notification_categories (category_code, module, tier, default_email_enabled, display_name, description) VALUES
 ('document.ownership.received','documents','standard',true,'Document ownership received','Documents were transferred to you.'),
 ('document.ownership.released','documents','optional',false,'Document ownership released','Documents you owned were transferred to someone else.'),
 ('document.ownership.holding','documents','standard',true,'Documents awaiting a new owner','Documents were placed with a holding owner pending reassignment.')
ON CONFLICT (category_code) DO NOTHING;
```
Rollback: same as V109 rollback (delete preference/tenant-setting rows; deactivate a category already used in `notifications`, delete otherwise).

Migration test `V112ToV115OwnershipMigrationTest` (template V106ToV109): migrate to 109 (or the highest below ours at execution), seed tenant, users with/without department, documents; migrate up; assert backfill (owner dept = owner's dept, null when none), CHECKs (`ownership_state`, reason, kind, counts), composite FK cross-tenant rejection, **UPDATE and DELETE on `ownership_transfers` rejected even as the owning role**, defaults on existing rows, 3 categories; run rollbacks reversed, delete history rows, assert gone, re-apply, assert same state.

## 4. Package layout and shared pieces
New package `com.oak.dms.ownership`: `entity/` (`OwnershipTransfer`, `OwnershipTransferBatch`), `repository/`, `service/` (`OwnershipTransferService`, `OwnershipBatchService`, `OwnershipQueryService`, `OffboardingService`, `HoldingOwnerResolver`, `OwnershipScope`), `controller/`, `dto/`, `helper/` (`OwnershipLocks`, `OwnershipNames`, `OwnershipCsv`), `listener/` (`OwnershipNotificationListener`), `event/`. Entity style = `DocumentComment`/`DocumentRelationship` (Lombok, plain UUID columns, no JPA relations, `@UuidGenerator`). Set-based statements use `NamedParameterJdbcTemplate` (the relationship code already does).
Existing code changed (additive only): `Document` (+2 fields), `Department` (+1), `DocumentVersionPromoter`, `DocumentRepository` (new queries + `OWNED_BY`), `DocumentServiceImpl/DocumentController/DocumentResponse/DocumentListItem`, `DocumentAccessService` (+`ensureCanManage`), `DepartmentServiceImpl` + DTOs, `PermissionCodes`, `AuditActionCatalog`, `AuditObjectTypes`, `UserServiceImpl` (**no change** – offboarding calls `deactivateUser` as is).

## 5. Algorithms

### 5.1 Single transfer (`OwnershipTransferService.transferOne`, `@Transactional`)
```
POST /documents/{id}/ownership/transfer {newOwnerUserId, newOwnerDepartmentId?, grantAccess=true, note?}
 1  tenant = TenantContext; actor = caller
 2  authz: holds dot.transfer-document-ownership.initiate-transfer (else 403 by @PreAuthorize)
 3  locks.applyLockTimeout()                                  // 5s, tx-local
 4  scalar lookup: docId = select document_id from documents where tenant_id=? and document_id=? and purged_at is null
      none -> 404 (missing / other tenant / purged all identical)
 5  target = users.findByUserIdAndTenantIdAndDeletedFalse(newOwnerUserId); status active else 422 OWNERSHIP_TARGET_INVALID
    dept   = explicit dept (active, non-deleted, same tenant else 422) else target.departmentId
 6  OwnershipLocks.lockDocuments(tenant, [docId])             // select ... for update, includes is_deleted, excludes purged
 7  doc = re-read after lock
 8  scope: actor admin -> ok
          else head-of check: owner user's department in departmentsHeadedBy(actor), AND access.canSee(doc) else
          (not in scope -> 403 OWNERSHIP_SCOPE_DENIED; cannot open -> 404)
 9  if same owner/dept/state=assigned -> 409 OWNERSHIP_UNCHANGED
10  access = grantAccess ? DocumentAccessService.ensureCanManage(tenant, actor, [doc], newOwner) : none
11  insert ownership_transfers (prev owner/dept, new owner/dept, reason 'manual', batch_id = new single batch, actor, access_granted, note, name snapshots)
    update documents set owner_user_id, owner_department_id, ownership_state='assigned', updated_by, updated_at
12  insert ownership_transfer_batches (kind 'single', counts 1/1/0)   // FK is deferred, so order is free
13  audit DOCUMENT_OWNERSHIP_TRANSFERRED (previous/new JSON, accessGranted)       // LAST (global audit lock)
14  publishEvent(OwnershipTransferredEvent)                                       // BEFORE_COMMIT listener, §O9
15  return TransferResultDto
```
Relationships, versions, comments, workflow runs, audit records are keyed by `document_id`; none are read or written (D12).

### 5.2 Bulk / selective (`OwnershipBatchService.transfer`, one tx)
```
POST /ownership/transfers  { scope:{sourceUserId|sourceDepartmentId}, mode:"all"|"selected",
   documentIds[]? (mode selected, <=5000), excludeDocumentIds[]?, limit? (<=5000),
   target:{ type:"user", newOwnerUserId, newOwnerDepartmentId? } | { type:"holding", departmentId? },
   reason:"manual"|"reorganization", grantAccess=true, note? }
 1  perm execute-transfer; applyLockTimeout()
 2  scope check: admin unrestricted; else scope user's dept / scope dept must be headed by actor (else 403)
 3  resolve target (user validation as 5.1) or holding chain (5.4) -> (ownerId, deptId, state)
 4  candidate ids (scalar, no entities), ordered ascending by uuid:
      mode all:      select document_id from documents where tenant_id=? and purged_at is null and <scope predicate>
                     [non-admin: AND <visible-to-actor predicate> ]  minus excludeDocumentIds
      mode selected: the given ids (dedupe), then classify: not found / other tenant / purged / hidden -> skipped
    count > 5000 and no limit -> 422 OWNERSHIP_BATCH_TOO_LARGE {total, max:5000}; with limit -> first `limit`
 5  OwnershipLocks.lockDocuments(tenant, ids)   // sorted ascending, `select ... order by document_id for update`
 6  re-read locked rows; skip reasons (never name hidden docs, only counts + ids the actor can open):
      not_found (missing/other tenant/hidden – one identical bucket), purged, already_owner, out_of_scope (manager)
 7  for chunk of 500 in ascending order:
      a. if grantAccess: ensureCanManage for the chunk (computes the new owner's AccessView once; only documents
         the owner cannot open get set-based upserts / restricted conversion) and returns the granted ids
      b. insert into ownership_transfers (...) select ... from documents where document_id = any(:chunk)
         -- BEFORE the update, with access_granted = (id in granted ids); history is append-only, never patched
      c. update documents set owner_user_id=:u, owner_department_id=:d, ownership_state=:state, updated_by, updated_at
         where tenant_id=:t and document_id = any(:chunk)
 8  insert batch row (counts, skipped_summary JSON, access_granted_count)
 9  audit OWNERSHIP_BATCH_TRANSFERRED (one event, last); publish OwnershipBatchTransferredEvent
10  return {batchId, totalCount, transferredCount, skippedCount, skipped:[{reason,count,documentIds? (only openable ones)}],
          accessGrantedCount, newOwnerLacksViewPermission}
```
Step order inside a chunk is: access conversions → insert history (with `access_granted`) → update documents. A single lock timeout rolls the whole batch back.

### 5.3 Offboarding (`OffboardingService`, one tx; user row locked first)
```
GET  /users/{id}/offboarding-preview   (select-documents; scope as above)
POST /users/{id}/offboard {reason (required), mode:"transfer"|"holding", newOwnerUserId?, newOwnerDepartmentId?,
                           grantAccess=true, acknowledgedStuckTasks:int}
 1  perms: execute-transfer AND otua.manage-users.deactivate (else 403); applyLockTimeout()
 2  lock the user row (select ... for update). Not found / already deactivated / other tenant -> 404.
    Tenant-admin account -> 422 (same rule as deactivateUser). Second concurrent offboarding blocks, then 404s, and
    creates no second batch.
 3  openTasks  = OpenTaskQuery.heldBy(user)  (pending|claimed|awaiting_info, active stage, in_progress run;
               holder = delegated_to | claimed_by | assignee(user) | escalated_to)
    startedRuns = in_progress runs with initiated_by = user
    stuck = openTasks.size     // started runs are listed but cannot be reassigned (F8)
 4  if stuck > 0 and acknowledgedStuckTasks != stuck -> 409 OFFBOARD_CONFIRMATION_REQUIRED {stuckTaskCount, tasks[], startedRuns[]}
 5  owned = count of the user's documents (excl. purged) ; if > 5000 -> 422 OWNERSHIP_BATCH_TOO_LARGE (UI loops 5.2 first, A7)
 6  target: mode transfer -> validate newOwner; mode holding -> HoldingOwnerResolver(user.departmentId, excluding user)
    run 5.2 with scope = user, mode all, reason 'offboarding' (or 'holding'), kind 'offboarding'
 7  UserServiceImpl.deactivateUser(userId, reason)   // same tx; Keycloak failure rolls everything back, incl. transfers
 8  batch.flagged_task_count = stuck ; audit USER_OFFBOARDED (counts, mode, target, flagged tasks) ; events
 9  return {batch summary, flaggedTasks[], startedRuns[], deactivated:true}
```
Flagged-items shape (preview and response):
```json
{ "openTasks":[{"taskId","instanceId","documentId?","documentTitle?","stageName","status":"pending|claimed|awaiting_info",
                "route":"assigned|claimed|delegated|escalated","dueAt","taskUrl":"/tasks?...","instanceUrl":"/workflows/instances/{id}",
                "canReassign":true}],
  "startedRuns":[{"instanceId","documentId?","documentTitle?","startedAt","instanceUrl","canCancel":true}],
  "stuckTaskCount":3, "startedRunCount":1 }
```
`documentId`/`documentTitle` are present only when the actor can open the document (D8). Reassign deep link = the existing workflow UI (`workflow-status-panel` reassign mode); we do not add a second reassign implementation.

### 5.4 Holding owner chain (`HoldingOwnerResolver.resolve(tenantId, departmentId?, excludeUserId?)`)
1. department (if non-null, non-deleted, `active`): `holding_owner_user_id` if active user ≠ excluded;
2. else the department's `head_user_id` if active user ≠ excluded;
3. else oldest active user of `UserPermissions.tenantAdminIds(tenantId)` ≠ excluded;
4. none -> 422 `OWNERSHIP_NO_HOLDING_OWNER`.
Result `{userId, source: holding_owner|department_head|tenant_admin, departmentId}`. Documents get `ownership_state='holding'`, `owner_department_id = departmentId` (the holding department; null on tenant-admin fallback with no department), history reason `holding`. Holding department = request `departmentId`, else the source user's department, else the source department.

## 6. Permission matrix
| Action | Endpoint | Permission(s) | Admin | Manager (head of owner's dept) | Others |
|---|---|---|---|---|---|
| Single transfer | POST `/documents/{id}/ownership/transfer` | initiate-transfer | any doc | docs of people in own dept **and** openable | 403 (no perm / not head); hidden doc 404 |
| Owned-docs list, pending list, preview | GET `/ownership/documents`, `/ownership/pending`, `/users/{id}/offboarding-preview` | select-documents | all | own dept's people; hidden docs excluded, `hiddenCount` shown (D8) | 403 |
| Bulk transfer / holding assign | POST `/ownership/transfers` | execute-transfer | any | own dept, openable only | 403 |
| Batch status | GET `/ownership/batches/{id}` | select-documents or execute-transfer | any | own batches (`created_by`) | 404 |
| Offboard | POST `/users/{id}/offboard` | execute-transfer **+** `otua.manage-users.deactivate` | yes | only if they also hold deactivate and head the user's dept | 403 |
| History | GET `/documents/{id}/ownership-history` | view-ownership-history.view + can open doc | all | openable docs | 403 / 404 |
| History export | GET `/ownership/history/export` | view-ownership-history.export | all | rows of openable docs only | 403 |
| Holding owner setting | PUT `/departments/{id}` | `otua.manage-departments.update` (existing) | yes | per existing rule | 403 |
| Browse owner filters | GET `/documents?ownerId&ownerDepartmentId` | `docm.documents.view` (existing) | results already access-filtered | same | same |
`approve-transfer` is held by nobody's check (D2). Cross-tenant ids on every endpoint → 404, identical to missing.

## 7. Build steps

### O2 – Schema, entities, constants, audit, promoter (size M, risk low-medium)
- Create: V113–V115 + rollbacks; entities/repos; backfill; `Document.ownerDepartmentId/ownershipState`, `Department.holdingOwnerUserId`; `PermissionCodes.java` + `permission-codes.ts` (`DOT_TRANSFER_INITIATE`, `DOT_TRANSFER_APPROVE` (declared, unused), `DOT_BULK_SELECT`, `DOT_BULK_EXECUTE`, `DOT_HISTORY_VIEW`, `DOT_HISTORY_EXPORT`; both files together); `AuditActionCatalog` (module `DOT`): `DOCUMENT_OWNERSHIP_TRANSFERRED` MEDIUM, `OWNERSHIP_BATCH_TRANSFERRED` HIGH, `USER_OFFBOARDED` HIGH (reason required – offboarding supplies it), `DEPARTMENT_HOLDING_OWNER_CHANGED` MEDIUM; `AuditObjectTypes.OWNERSHIP_TRANSFER`, `OWNERSHIP_BATCH` (user/department/document reuse existing types); frontend `audit-action-catalog.ts` (+`DOT` in `AUDIT_MODULE_LABELS`) and `audit-log-filters.tsx`; `DocumentVersionPromoter.persist`: look up `uploadedBy` department (tenant-scoped `userRepository`) and set `.ownerDepartmentId(...)` (null-safe; a deactivated/missing uploader never fails promotion).
- Tests: migration test; `DocumentVersionPromoter` test (new doc gets uploader's department, null when none; new version of existing doc leaves owner fields alone); catalog registration test; `PermissionCodes`/TS parity (existing audit tests stay green).

### O3 – Single transfer (size M, risk medium)
`OwnershipTransferController` + `OwnershipTransferService.transferOne` (§5.1); `OwnershipLocks` (ascending `select … for update`, deleted allowed, purged excluded, `RelationshipKeys.compare`); `DocumentAccessService.ensureCanManage`; `OwnershipScope` (admin/manager scope, `departmentsHeadedBy`); `OwnershipNames` (batched `findAllById`, tenant-filtered, deactivated resolve, snapshot names written at insert).
`POST /api/v1/documents/{id}/ownership/transfer` – body `{newOwnerUserId, newOwnerDepartmentId?, grantAccess?, note?}` → 200 `{transferId, batchId, documentId, previousOwner{userId,name}, newOwner{…}, newOwnerDepartment{id,name}?, accessGranted, accessConvertedToRestricted, newOwnerLacksViewPermission}`. Errors: 404, 403 `OWNERSHIP_SCOPE_DENIED`, 409 `OWNERSHIP_UNCHANGED`, 409 `CONCURRENT_UPDATE`, 422 `OWNERSHIP_TARGET_INVALID`.
Edge cases: Recycle Bin doc allowed; purged 404; target in another tenant = same 422 as missing/inactive (no existence leak) – the picker only offers tenant users anyway; note ≤1000 chars.
Tests: IT – transfer writes owner/dept/state, history row (prior owner preserved, names snapshotted), `DOCUMENT_OWNERSHIP_TRANSFERRED` audit, relationships/versions/comments/workflow rows byte-identical before/after (D12); validation (missing, inactive, deactivated, other tenant, unchanged); D4 on/off; **restricted-folder doc openable by the new owner afterwards and other folder members unaffected**; previous owner keeps access; document with its own list gets an upserted manage entry; Recycle Bin allowed, purged 404; lock timeout → 409; access-cache effect checked by calling the access API as the new owner immediately after.

### O4 – Bulk and selective (size L, risk high)
`OwnershipBatchService`, `OwnershipQueryService`; endpoints:
- `GET /api/v1/ownership/documents?userId|departmentId&q&state&inRecycleBin&page&size` (select-documents) → `{items:[{documentId,title,documentTypeName,folderId,folderName?,inRecycleBin,isTemplate,ownershipState,ownerUserId,ownerName,ownerStatus:active|deactivated|deleted,ownerDepartment{id,name}?,createdAt}], page, totalOwned, visibleCount, hiddenCount}`. Admin: everything not purged; non-admin: visible predicate (same clause as `findVisible`: hidden folders / granted ids / workflow-readable) + scope predicate; `hiddenCount = totalOwned − visible` ("N more documents only a tenant admin can transfer", never any title/type/id – D8).
- `POST /api/v1/ownership/transfers` (execute-transfer) – §5.2 → 200 `BatchSummaryDto`.
- `GET /api/v1/ownership/batches/{id}` → same summary + `createdBy`, `createdAt`, `completedAt`.
Rules: A6, A7, skip reasons `not_found | purged | already_owner | out_of_scope`; hidden/missing/foreign documents share the identical `not_found` bucket (counts only); `limit` + repeat.
Tests: all (500 docs in the normal run), selective (and exclude list), skips (purged, already owner), Recycle Bin included and flagged, 5,000 limit message and `limit` loop, scope for manager and hidden count, batch endpoint 404 for another tenant/creator, chunk boundaries (499/500/501/1,000), history row count == transferred, no per-document audit explosion (1 batch audit).

### O5 – Holding owner (size M, risk low)
`HoldingOwnerResolver`; `DepartmentServiceImpl.update` accepts `holdingOwnerUserId` (validated: active tenant user; clearing supported with an explicit `clearHoldingOwner` flag or null-vs-absent semantics decided from the current `UpdateDepartmentRequest` shape – preserves existing behaviour for callers that omit it); `DepartmentResponse` gains `holdingOwnerUserId`, `holdingOwnerName`; audit `DEPARTMENT_HOLDING_OWNER_CHANGED` (previous/new). `GET /api/v1/ownership/pending?departmentId&q&page&size` (select-documents) = documents with `ownership_state='holding'` **or** owner is a deactivated/deleted user (D13, `exists users where deleted=true or status<>'active'`), each row with `pendingReason: holding|owner_deactivated` and holding department. Reassigning out of the list is an ordinary transfer (state → `assigned`).
Tests: chain order (holding → head → tenant admin), skipping the source user, inactive/deleted candidates skipped, none available → 422, state `holding` set and cleared on a real transfer, D13 list (both reasons, purged excluded, hidden rule), department update keeps existing fields and rejects inactive/other-tenant users, audit.

### O6 – Offboarding (size L, risk high)
`OffboardingService`, `OpenTaskQuery` (new `WorkflowTaskRepository`/`WorkflowInstanceRepository` methods, index-friendly: `ix_workflow_tasks_claimed_by/delegated_to/escalated_to/open_assignee`, `ix_workflow_instances_initiator`); endpoints in §5.3 (`UserController` stays untouched – new `OffboardingController` under `/api/v1/users/{id}/…`). Preview body: `{user{id,name,status,department}, ownedTotal, visibleCount, hiddenCount, inRecycleBinCount, suggestedTarget{userId,name,source}, holdingDepartment, flags (shape above), canOffboard, blockers[]}`.
Rules: alternate flow (a) flag only, never auto-reassign; (b) `mode:"holding"` uses §5.4; deactivation proceeds only with explicit acknowledgement of the stuck-task number; tenant-admin account blocked; the whole action rolls back if Keycloak fails; started runs listed with cancel deep link only; double offboarding serialised on the user row.
Tests: preview numbers (owned, Recycle Bin, hidden, tasks by every route incl. delegated and escalated, claimed, awaiting_info; closed/decided tasks excluded; pool-only tasks not counted), offboard transfer + holding, confirmation path (no ack → 409 with numbers; wrong number → 409; right number → proceeds), account disabled + `USER_DEACTIVATED` and `USER_OFFBOARDED` audits, transfer history reason `offboarding`, started runs listed, Keycloak failure rolls back the transfers (mock), tenant-admin account refused, manager lacking deactivate → 403, >5,000 → 422 and the loop completes it, tasks still reassignable afterwards through the existing endpoint.

### O7 – History and export (size M, risk low)
`GET /api/v1/documents/{id}/ownership-history?page&size` → `{items:[{transferId, transferredAt, reason, batchId, note, accessGranted, previousOwner{userId,name,status}, previousDepartment?, newOwner{…}, newDepartment?, actor{…}}]}` newest first; names via `OwnershipNames` (live row incl. deactivated → else snapshot column → else "Unknown user"). Permission + document access; hidden → 404.
`GET /api/v1/ownership/history/export?from&to&documentId&userId&batchId&reason` → `text/csv` (streamed, cap 100,000 rows with a clear 422 message; columns: transferredAt, documentId, documentTitle, previousOwner, previousDepartment, newOwner, newDepartment, reason, batchId, actor, accessGranted, note; formula-injection safe: cells starting `= + - @` are prefixed; reuse any existing CSV helper under the export package, else the small `OwnershipCsv`). Audit of exports is not added (read-only, mirrors audit export if it exists – check at build).
Tests: names for active, deactivated and **purged** users; newest-first; hidden doc 404; export filters, permission (view vs export), non-admin sees openable rows only, CSV injection, tenant isolation; history **append-only**: `UPDATE`/`DELETE` as the owner role raises.

### O8 – Find by owner (size M, risk medium)
`GET /api/v1/documents` gains `ownerId`, `ownerDepartmentId` (both optional, combinable with every existing filter). `OWNED_BY` JPQL constant ANDed into all eight `DocumentRepository` queries; signature changes through `DocumentService(Impl).listDocuments`, `DocumentController`, `frontend/lib/documents/api.ts fetchDocuments` (grep every caller first). The ownership list (O4) takes the same filters. `DocumentListItem`/`DocumentResponse` gain `ownerName`, `ownerDepartmentId`, `ownerDepartmentName`, `ownershipState` (additive).
Tests: filters combine with `q`/folder/type; hidden documents never returned for either filter; department filter; query plan uses `ix_documents_tenant_owner`/`ix_documents_owner_department`; existing document list tests untouched and green.

### O9 – Notifications (size M, risk medium)
`OwnershipNotificationListener` (`BEFORE_COMMIT`, convention F16) on `OwnershipTransferredEvent`/`OwnershipBatchTransferredEvent`.
- Single: new owner (`document.ownership.received`, title by document only if they can open it – `documentId` + `neutralTitle`, `requireViewPermission(true)`), previous owner (`released`, optional), actor never notified.
- Bulk/offboarding: **one summary per recipient**, never per document: new owner "N documents were transferred to you" + link `/admin/ownership` (or `/documents?ownerId=<me>` for non-admins); previous owner (if still active) "N of your documents were transferred"; holding assignments notify the holding owner **and** tenant admins with `document.ownership.holding` ("N documents are waiting for a new owner", link to the pending list).
- `dedupeKey`: `ownership:<batchId>:<recipient>:<category>`; no email for deactivated recipients (service already drops deleted users); `sourceModule("documents")`.
Tests: single (title shown vs neutral text when the recipient cannot open), bulk = exactly one notification per recipient, holding alert to holder + admins, actor excluded, released is Optional (no email by default), dedupe on retry, category rows exist.

### O10 – Frontend (size XL, risk medium; read `node_modules/next/dist/docs/` first, `frontend/AGENTS.md`)
- `lib/ownership/{api.ts,types.ts,use-ownership.ts}` (react-query; skip retry on 403/404 like `use-relationship-types.ts`); `PermissionCodes` DOT entries.
- `app/admin/ownership/page.tsx` (Suspense wrapper) + `components/ownership/ownership-content.tsx` (pattern of `relationship-types-content.tsx`: `DashboardShell`, `AdminBreadcrumb`, `PermissionGate` any-of the DOT view/select/history codes): tabs **Owned documents** (owner/department pickers via `useUserOptions`/`useDepartmentOptions`, search, state filter, Recycle Bin/template badges, select-all/some, "hidden documents only an admin can transfer: N", Transfer selected / Transfer all), **Pending reassignment** (D13), **History** (filters + Export CSV).
- `transfer-dialog.tsx` (single and bulk; target picker `PersonPicker`, optional department, "Also give the new owner access" default on, note, 5,000 split explainer and automatic "next 5,000" loop with progress), `bulk-results-dialog.tsx` (transferred / skipped by reason / access granted / warnings), `offboard-dialog.tsx` (from `user-detail-view.tsx`, a new "Offboard user…" action next to Deactivate gated by `execute-transfer` **and** `otua.manage-users.deactivate`: counts, target or holding choice, flagged tasks and started runs with deep links, explicit stuck-task acknowledgement checkbox "I understand N tasks will stay stuck", reason textarea reusing the 1000-char limit), `ownership-history-panel.tsx` + `owner-field.tsx` on the document page (owner name, department, `holding` badge, Transfer button gated by initiate-transfer), department form field `holdingOwnerUserId` (`department-form.tsx`/`schema.ts`), row-menu item **Transfer ownership** in `document-browser.tsx` (`documentActions`, beside Link to…; `can.transferOwnership` = initiate-transfer; server enforces scope) and an Owner filter in the browser.
- `nav-config.ts`: "Document Ownership" (icon e.g. `UserRoundCog`) in Administration → Access, `permission: [DOT_BULK_SELECT, DOT_TRANSFER_INITIATE, DOT_HISTORY_VIEW]`.
- Tests: vitest for dialogs (default-on grant checkbox, split explainer, acknowledgement gating), owner field/history panel, nav item, offboard dialog confirmation; `tsc --noEmit`, `eslint` on changed files, `npm test`.

### O11 – Permission matrix and isolation tests (size M)
`OwnershipPermissionMatrixIT` (every endpoint × {no perm, each DOT code alone, admin, manager of own dept, manager of other dept}), `OwnershipTenantIsolationIT` (second tenant admin → 404 everywhere incl. batches, history, preview, transfer targets), manager-scope tests (own dept only, hidden-document count, hidden doc 404 never leaks title/type/id), `approve-transfer` alone grants nothing.

### O12 – Performance and concurrency (size M, risk high)
- Normal run: 500-document single-batch transfer (assert < a generous bound, 1 batch audit, 500 history rows, statements per chunk bounded).
- `@Tag("perf")` opt-in: 10,000 documents via two 5,000 requests.
- `OwnershipConcurrencyIT` (pattern of `RelationshipConcurrencyIT`: ready/go latches, `Executors.newFixedThreadPool`): (a) a transfer racing relationship creation on the same documents (both succeed, no deadlock – both lock ascending; history and relationship both present); (b) two transfers of the same document to different owners (serialised; chain continuity: second row's previous owner = first row's new owner; or one `OWNERSHIP_UNCHANGED`); (c) double offboarding of one user: exactly one succeeds, one 404, exactly one batch, account deactivated once; (d) bulk batches over overlapping sets. Run **5×** after any change to locking/transfer code.

### O13 – Docs and traceability (size S)
`docs/ownership-user-guide.md` (style of `docs/relationships-user-guide.md`: TL;DR, who can do what with permission codes, single/bulk/offboarding/holding walkthroughs), `docs/events/ownership-audit-events.md` (template `relationship-audit-events.md`), `docs/ownership-legal-hold-checklist.md` (D11: when a Legal Hold feature exists – transfer still allowed, history row written, hold flag preserved on the document, audit `legalHoldFlag` carried, bulk summary shows held docs, release of hold unaffected, re-run the O3/O4/O11 suites), `docs/ownership-traceability.md` (table in §10), plan Progress log and Deviations.

## 8. Dependencies
O2 → O3 → O4 → {O5, O7, O8} → O6 (needs O4 + O5) → O9 (hooks on O3/O4/O6) → O10 (needs APIs) → O11 → O12 → O13. Tests are written with each step; O11/O12 add the cross-cutting suites.

## 9. Every place the owner is read today, and behaviour after a transfer
| Reader | Today | After a transfer |
|---|---|---|
| `DocumentRepository.findSharedWith` (`d.ownerUserId <> :userId`) | owner excluded from "shared with me" | new owner stops seeing it there; previous owner may start (if they remain on a restricted list). Intended. |
| `DocumentServiceImpl.toItems` l.340, `DocumentResponse` l.402 | `ownerUserId` in list/detail | new owner id (+ new name/department/state fields). |
| `DashboardService` l.214 ("mine" count) and l.392-500 (owner names) | counts/ names by owner | counts move to the new owner; names resolve for deactivated owners, null for purged (we do not change this). |
| `WorkflowNotificationListener.notifyOwnerOfStatusChange` l.119-130 | live `findById` owner at event time | alerts go to the **new** owner immediately, including for in-flight runs; deactivated previous owner no longer mailed. |
| `RelationshipNotificationListener` l.45-60 | "Document linked" to owner | new owner. |
| `WorkflowSupportingDocumentServiceImpl` l.176-181 | actor must be `createdBy` or owner | new owner gains the right, previous owner loses it unless `createdBy`. |
| `DocumentAccessService`/`FolderAccessService` | owner ignored | unchanged; D4 grants/conversion handle the new owner's reach. |
| `V37…` / tests / scripts | fixtures only | n/a |
| Frontend `lib/documents/api.ts`, `app/page.tsx`, `recent-documents.tsx` | owner id/name | same fields, new values; deactivated names still resolve. |
DOT-NFR-003: because no permission is derived from ownership and the access cache is bumped by the V61 triggers, owner-dependent behaviour is re-evaluated on the next request; D4 closes the one gap (new owner's reach).

## 10. Traceability
| Requirement | Step(s) |
|---|---|
| DOT-FR-001 designated owner (user and/or department) on every document | O2 (columns, backfill, promoter), O8 (exposed in API), O10 (Owner field) |
| DOT-FR-002 admin transfers one document | O3, O10 |
| DOT-FR-003 bulk transfer for departing employee / reorganised department | O4, O6, O10 |
| DOT-FR-004 prior owner preserved in history, never overwritten | O2 (append-only table), O3, O7 |
| DOT-FR-005 manager/admin reviews owned documents and reassigns selectively | O4 (list, selective), O3 scope, O10 |
| DOT-FR-006 new owner notified | O9 |
| DOT-NFR-001 every change logged (prior, new, actor, time) | O2, O3, O4, O7 |
| DOT-NFR-002 bulk needs no per-document effort | O4 (all mode), O6 (offboard), O10 (auto "next 5,000") |
| DOT-NFR-003 owner-tied permissions re-evaluated | §9, O3 D4 (`ensureCanManage`), O11 |
| DOT-NFR-004 filter/search by current owner | O4 list, O8 browse filters, O10 |
| OTUA-FR-006 offboarding consistent with transfer | O6 |
| Alternate (a) pending approvals flagged | O6 (D6) |
| Alternate (b) no successor → holding owner | O5, O6 |
| Alternate (c) legal hold | O4/O3 never block + O13 checklist |
| Workflow (single outside offboarding) | O3, O10 row menu / document page |

## 11. Tests required (mapped)
Single transfer (O3); target validation (O3); D4 on/off and restricted-folder openability, previous owner keeps access (O3); bulk all/selective, skips, Recycle Bin, 5,000 limit (O4); holding + D5 chain, D13 list (O5); offboarding preview/action, flagged tasks and runs, confirmation, deactivation (O6); manager scope + hidden count (O4/O11); permission matrix + isolation 404 (O11); history append-only, names for deleted users (O2/O7); owner filters keep access filtering (O8); notifications one-per-recipient, access-aware (O9); concurrency ×5 (O12); migration up/down/re-apply (O2); 500-doc in normal run, 10,000 opt-in (O12). Existing workflow, notification, relationship, document and user tests stay green (full run with `-Dmaven.test.failure.ignore=true`, compare to the 7 known failures).

## 12. Verification (execution time)
- Disposable DB: `dms-workflow-pg` on 5435, database `dms_phase2_it`, password `workflow` (create fresh if needed). `export DB_URL=jdbc:postgresql://localhost:5435/dms_phase2_it DB_USERNAME=postgres DB_PASSWORD=workflow` – migration tests otherwise hit the dev DB on 5433. Docker/Maven outside the sandbox with permission; stop and tell you if Docker or disk is unavailable. Never touch the dev DB.
- Backend per step: new + affected tests; final `mvn verify -Dmaven.test.failure.ignore=true`; concurrency tests 5× after any lock change. Frontend: `npx tsc --noEmit`, eslint on changed files, `npm test`.
- Rebuild only after tests pass: `docker compose build dms-core frontend` then `docker compose up -d --no-build` (never `--build` for the whole stack, no prune, keep `local-ocr-worker`).
- Browser walkthrough (final report): transfer one document; bulk transfer; restricted-folder document; offboard a user with open tasks (confirmation, deep links); holding owner; history panel; owner filter; notifications.
- Git: nothing but read-only git; everything stays uncommitted.

## 13. Risks and open questions

Risks
- R1 D4 mechanism (F11): converting an `inherit` document to `restricted` freezes its access list at transfer time (later folder-rule changes no longer reach that document). Mitigated by copying the effective entries and recording `accessConvertedToRestricted` in history/audit.
- R2 Lock contention on large batches (5,000 locks, 5s timeout): all-or-nothing 409 with retry advice; ascending order prevents deadlock with relationship creation.
- R3 Keycloak call inside the offboarding transaction (existing `deactivateUser` behaviour): a slow Keycloak holds document locks; acceptable at 5,000 docs but covered by the lock timeout on others.
- R4 Migration numbering collides at merge (OCR branch V108–V112, plus the existing V108/V109 clash); renumber then.
- R5 Fixtures that delete documents are blocked by the append-only history FK; tests use scratch tenants/DBs.
- R6 `users.department_id` is a single "primary" department – manager scope and defaults depend on it being maintained.
- R7 Frontend audit-filter mirror is already out of date vs the backend; we only add what DOT needs.

Open questions (defaults in bold apply if you do not object)
- Q1 D4 for documents that follow a restricted folder: **convert to `restricted` copying the folder's effective entries + new owner manage** (needed for "openable afterwards"). Alternative: only grant on documents that already have their own list and report the rest as "new owner cannot open".
- Q2 Add `previous_owner_name`, `new_owner_name`, `actor_name` snapshot columns (and `skipped_summary`, `access_granted_count` on batches) to the §4 tables so history survives purged users: **yes**.
- Q3 Nav placement: there is no Governance group; **Administration → Access**. Or create a new "Governance" group.
- Q4 Runs started by a departing user cannot be reassigned (immutable `initiated_by`, no endpoint): **flag with a cancel link only**. Alternative: out of scope to add a "change initiator" feature.
- Q5 Non-admin DOT holders who head no department: **403**. Alternative: allow transfers of documents they can manage.
- Q6 Offboarding with more than 5,000 documents: **the UI runs the bulk loop first, then offboards**; the API itself refuses (A7).

## 14. Answers recorded on "start execution"
Recorded at "start execution":
- Q1 yes: convert `inherit` documents to `restricted` with the copied entries. Q2 yes: keep snapshot columns. Q3 Administration -> Access. Q4 flag only, with a cancel link. Q5 403 for DOT holders who head no department. Q6 yes: the UI splits sets above 5,000.
- Addition 1: the transfer dialog shows, before confirming, how many documents will be switched to Restricted (new owner could not otherwise open them) with a short explanation; the bulk results show it too. Implemented as a dry-run endpoint `POST /api/v1/ownership/transfers/preview` (same body as the transfer, writes nothing, returns `willConvertToRestrictedCount`, `transferableCount`, skip counts) for the bulk dialog, and `POST /api/v1/documents/{id}/ownership/transfer/preview` for the single dialog; results carry `convertedToRestrictedCount`.
- Addition 2: concurrency test (e): a transfer racing a soft delete and a permanent delete (purge) of the same document. A purged document is skipped without error (single transfer: 404; bulk: skip reason `purged`), a soft-deleted one is still transferred.

## 15. Deviations (numbered; execution keeps this table up to date)
| # | Deviation from brief | Reason |
|---|---|---|
| D-1 | Notification listener runs BEFORE_COMMIT, not after commit | Project convention; same transaction as the transfer (F16) |
| D-2 | D4 mechanism: convert-to-restricted with copied entries for `inherit` documents | `document_access` is ignored unless `access_mode='restricted'` (F11) |
| D-3 | Extra columns: name snapshots on history, `skipped_summary`/`access_granted_count` on batches | History must survive purged users; batch status endpoint needs skip reasons (F6) |
| D-4 | Audit is one event per batch for bulk (per-document detail in `ownership_transfers`) | Global audit lock and 5,000-row batches (A5) |
| D-5 | Nav item in Administration → Access | No Governance group exists (F17) |
| D-6 | Single transfers audit with object type DOCUMENT; only `OWNERSHIP_BATCH` was added as a new object type (no `OWNERSHIP_TRANSFER`) | The audit trail of a document should filter by the document |
| D-7 | The D4 access logic lives in `OwnershipAccessService` (ownership package), not as a new method of `DocumentAccessService`; one `OwnershipTransferredEvent` serves single and batch | Leaves the access service untouched; same behaviour |
| D-9 | `ownership_transfer_batches.converted_to_restricted_count` added (V113 edited before release) | Bulk results and the batch endpoint must show how many documents were switched to Restricted (addition 1) |
| D-10 | `UpdateDepartmentRequest` gained `holdingOwnerUserId` + `clearHoldingOwner` (omitted = unchanged); a 4-arg constructor keeps old callers | A full-replacement update would otherwise wipe the holding owner for callers that do not know the field |
| D-11 | Owner **names** are not added to browse list rows (the `DocumentServiceImpl` constructor is called positionally by two existing tests); list rows/detail get `ownerDepartmentId` + `ownershipState`, and the document page reads names from `GET /documents/{id}/ownership` | Avoid changing an existing constructor and its tests |
| D-12 | The History tab has no tenant-wide list (the API offers per-document history only): pick a document to see its panel, plus the CSV export form; the export form has no batch-id field | Per-document history is what the plan's API defines (`GET /documents/{id}/ownership-history`) |
| D-13 | Single transfer and preview resolve the **document first** (404 for missing/foreign/hidden/purged), then the target (422) | Keeps cross-tenant answers 404 whatever the target is (D14) |
| D-14 | `@org.hibernate.annotations.DynamicUpdate` on `Document` | A stale entity (loaded before a transfer committed) would otherwise write the old owner back on save; found by the soft-delete race test |
| D-15 | `OwnershipRetry` re-runs a transfer/offboarding that was a **deadlock victim** (max 4 attempts), called from the controllers | Entity-based writers (soft delete, rename, …) take the audit chain lock before their UPDATE flushes, the transfer takes document locks first; Postgres resolves the clash by killing one side |
| D-16 | Dynamic SQL in `OwnershipBatchService`/`OwnershipQueryService` uses constant `%s` templates (`CANDIDATE_IDS`, `COUNT`) filled with `OwnedDocumentsSql#where()` | `SqlConcatenationGuardTest` forbids joining SQL to a non-constant; the where-clause is built only from fixed fragments with every value bound |
| D-8 | `OwnershipLocks` converts JDBC lock timeouts (SQLState 55P03/40P01) to `CannotAcquireLockException` | JdbcTemplate does not translate them like JPA, which would have produced a 500 instead of 409 CONCURRENT_UPDATE |

## 16. Progress log
| Step | Files changed | Tests added | Result | Deviation |
|---|---|---|---|---|
| O2 | V113-V115 + 3 rollbacks; `Document`, `Department` fields; `OwnershipTransfer(+Batch)` entities/repos; `PermissionCodes`/`permission-codes.ts`; `AuditActionCatalog`, `AuditObjectTypes`, frontend audit mirrors; `DocumentVersionPromoter` | `V113ToV115OwnershipMigrationTest` (2), `OwnershipSchemaIT` (5) | pass | D-6 |
| O3 | `ownership/helper/*` (locks, names, scope, exception), `OwnershipAccessService`, `OwnershipTargets`, `OwnershipTransferEngine`, `OwnershipTransferService`, `OwnershipTransferController`, preview endpoint, event | `OwnershipTransferIT` (13) | pass | D-7, D-8 |
| O4 | `OwnedDocumentsSql`, `OwnershipQueryService`, `OwnershipBatchService`, `OwnershipBatchController`, bulk DTOs, `converted_to_restricted_count` column | `OwnershipBulkIT` (12) | pass | D-9 |
| O5 | `HoldingOwnerResolver`, department holding owner (request/response/audit), `GET /ownership/pending` | `OwnershipHoldingIT` (6) | pass | D-10 |
| O6 | `OpenWorkItems`, `OffboardingService`, `OffboardingController`, flag DTOs | `OwnershipOffboardingIT` (10) | pass | |
| O7 | `OwnershipHistoryService/Controller`, `OwnershipCsv`, history DTOs | `OwnershipHistoryIT` (6) | pass | |
| O8 | `OwnerFilter`, `NARROWED` + 8 repository queries, `DocumentService/Impl/Controller`, list/detail DTO fields, `OwnershipInfoService` (`GET /documents/{id}/ownership`) | `OwnershipFilterIT` (5) | pass | D-11 |
| O9 | `OwnershipNotificationListener` | `OwnershipNotificationIT` (6) | pass | |
| O10 | `lib/ownership/*`, `app/admin/ownership`, `components/ownership/*`, changes to document-browser, document page, user-detail-view, department form, nav-config, `lib/documents/api.ts`; `?ownerId=` read by the browser | 33 vitest tests (transfer dialog, offboard dialog, owner field/history panel, bulk results, nav item) | pass (107 total) | D-12 |
| O11 | `OwnershipSecurityIT` | 7 (matrix, scope, isolation, hidden-document rules) | pass | D-13 |
| O12 | `OwnershipConcurrencyIT`, `OwnershipPerformanceIT`, `OwnershipScaleIT`, `OwnershipRetry`, `@DynamicUpdate` on `Document` | 8 + 2 + 1 (opt-in) | pass; concurrency IT run 5× green | D-14, D-15 |
| O13 | `docs/ownership-user-guide.md`, `docs/events/ownership-audit-events.md`, `docs/ownership-legal-hold-checklist.md`, `docs/ownership-traceability.md` | – | written | |
| Full run | `./mvnw verify -Dmaven.test.failure.ignore=true` against `dms_phase2_it` (port 5435): 846 unit + 534 integration tests | – | One new failure found and fixed (`SqlConcatenationGuardTest`: two dynamic-SQL joins, now constant templates). Remaining failures are the known ones: `BulkImportServiceImplTest` (1), `DocumentMetadataServiceApplyOcrFieldsTest` (2), `DocumentUploadOcrQueueingIT`, `OcrEventsConsumerLiveIT`, `OcrJobTokenHealthGroupIT`, `WatermarkWorkerFailureIT` (4) = 7. `WatermarkServiceIT.twoSimultaneousRequests…` failed once and passed on re-run (a flaky race in code from another branch) | D-16 |
| Docker | `docker compose build dms-core frontend`, then `up -d --no-build` | – | see final report | |
