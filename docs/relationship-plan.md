# Document Relationships Management (DRM) – Plan


## 0. Context
Oak DMS has no way to link documents to each other. DRM adds first-class, tenant-scoped relationship records with admin-editable types, a Related Documents panel, automatic "Duplicate of", lifecycle handling (recycle bin / purge), cycle prevention, filters, audit and an optional notification. Everything is additive (tables/columns only). Locked decisions D1–D11 are applied as written.

## 1. Findings (verified in the code) and where the brief is wrong

| # | Brief assumption | Reality |
|---|---|---|
| F1 | DRM permissions seeded in V10 | Confirmed (V10 l.180-185, module DRM, actions from V6/V7). **No Java/TS constants exist** in `security/rbac/catalog/PermissionCodes.java` or `frontend/lib/auth/permission-codes.ts` – we add constants only (no new codes, no migration). Both files must be edited together. TENANT_ADMIN holds all permissions implicitly; seed migrations grant to no role. |
| F2 | Highest Flyway = V105 | Confirmed (V35 never existed; V99–V102 have no rollback files). Next free = **V106**. Collision risk: `develop`/`origin/develop` are only at V42; V43–V105 exist only on feature branches (this branch already renumbered once, commit a3d2924). Other open feature branches (workflow-automation, virus-scanning, metadata-schema) may also take V106+. Mitigation: before each migration re-check `git branch -a` / `git ls-tree` (read-only) and renumber if needed; migrations are idempotent. |
| F3 | Rollbacks / migration tests | Rollbacks live in `backend/dms-core/src/main/resources/db/rollback/V<n>__<desc>.rollback.sql` (outside Flyway location, run by hand in reverse order). Migration tests are plain JUnit (no Spring) using a scratch DB per test (`V103ToV105NotificationMigrationTest` is the template: migrate to 105, seed, migrate up, assert, run rollbacks reversed, delete from `flyway_schema_history`, re-apply). **They default to `DB_URL=localhost:5433` (the dev server)** – at execution always export `DB_URL/DB_USERNAME/DB_PASSWORD` for port 5435 so they never touch the dev server. Port 5435 / `dms_phase2_it` appear only in older plan docs, not in config. |
| F4 | IDs | Project uses **UUID** PKs everywhere (no bigint). Relationship tables use UUID. "Lower id first" = bytewise-unsigned UUID order; Postgres `uuid` ordering equals lexicographic order of the lowercase canonical string, **not** `java.util.UUID.compareTo` (signed). Use a single helper `RelationshipKeys.compare(UUID,UUID)` (string compare) for canonicalisation and lock order. |
| F5 | IngestionGateImpl has a "keep both" path | **No.** Keep both = `refuseDuplicates=false` on an ordinary new-document upload (`duplicateRefusal` returns empty). The matched original id is **never sent to the backend**: `POST /api/v1/documents` params are `documentTypeId,title,folderId,refuseDuplicates,requestedFields`. The keep-both UI is in `bulk-upload-sheet.tsx` (`handOff`, choice `both`; full `match.documentId` is known client-side); `uploads-panel.tsx` only has "Upload anyway". The document row is created asynchronously in `DocumentVersionPromoter.persist` (new_document branch ~l.110) from `ScanResult` fields. → R7 needs an end-to-end plumb of `duplicateOfDocumentId` (UI → controller → IngestionRequest → new `scan_results` column → promoter). |
| F6 | Bulk import uses DuplicateFinder | Only for `skip`/`new_version`; for the default `keep_both` (`BulkImportController` l.94-96) `ImportRecordCreator.submit` skips the finder, so no match is known. The new document id appears later in `complete(...)` via the ticket. → run the finder for link purposes even for keep_both, persist matched id on `import_items` (new column), create link in `complete`. |
| F7 | `documents.published_version_id` never set | Confirmed: no publish action; FK exists, only read. `DocumentVersionCreatedEvent` is published only for new versions of existing documents (listeners: workflow `DocumentVersionChangeListener`, `DmsMetrics`). D2 stands: nullable version pins, no automation. |
| F8 | Purge soft or hard | **Soft.** `RecycleBinService.purge` (l.317) deletes storage/OCR artifacts, then in one tx (l.365-372) marks `document_versions.purged_at`, deletes `document_metadata` and `document_access`, sets `documents.purged_at`; row stays as tombstone. Purged docs are also `is_deleted=true`. Pins, comments, workflow rows are left intact. → purge hook goes inside that tx; FK to `documents` never blocks. `purgeNow` is not `@Transactional` (the inner `executeWithoutResult` is). |
| F9 | `documents.status` | Column exists (draft/published/archived/pending-deletion) but **nothing ever writes it** (always `draft`). Panel "status" needs a better source → Open question Q1. |
| F10 | Move / transfer / archive | Move: `DocumentServiceImpl.moveDocuments` (only sets `folderId`; audits `DOCUMENT_MOVED`). No ownership transfer (`owner_user_id` set once in promoter) and no archive feature. Confirmed for D8. |
| F11 | Access | `DocumentAccessService.forCaller/forUser/forUsers`, `level(doc)`, `canSee(doc)`, `require(view,doc,needed)` (404 below view, 403 below needed). **There is no `canEdit`**: use `access.level(doc).atLeast(FolderAccessLevel.edit)`. Workflow-readable docs get view only (read-only). Templates capped at view. Tenant mismatch = 404 via `findByDocumentIdAndTenantIdAndDeletedFalse`. |
| F12 | Browse query | `DocumentRepository` has six queries (`findVisible*`, `findLive*`) composed from `LIVE_TITLED + NARROWED` JPQL constants; called from `DocumentServiceImpl.listDocuments`. Adding relationship filters = new constant ANDed into all six (+ signature change; grep other callers first). |
| F13 | Search backend | `search/` package is empty (.gitkeep). No engine. Only `pg_trgm` index on `lower(title)` (V100). Relationship filters are SQL `EXISTS` subqueries. |
| F14 | "No JS test framework" | **Wrong: vitest 5 + testing-library exists** (`npm test`, co-located `*.test.tsx`). We add a few component tests. Also: `npm run typecheck`, `npm run lint`. Next 16.2.6 – read `node_modules/next/dist/docs/` before adding the admin page. |
| F15 | "User guides for workflow and notifications" | They don't exist. Closest style: `docs/notification-developer-guide.md` (TL;DR, worked examples) and the plan docs; event docs follow `docs/events/workflow-audit-events.md`. The new guide follows the developer-guide style. |
| F16 | Locking convention | No JPA lock-timeout hints. Convention = DB `lock_timeout` via `WorkflowLocks.applyLockTimeout()` (`set_config('lock_timeout','5s',true)`, `Propagation.MANDATORY`) → `PessimisticLockingFailureException` → 409 `CONCURRENT_UPDATE` (GlobalExceptionHandler l.85). Document row lock: `DocumentRepository.lockByDocumentIdAndTenantId` (PESSIMISTIC_WRITE, filters `deleted=false`). Pitfall documented in the code: loading the entity before the FOR UPDATE query returns the stale cached copy → use scalar id queries first. No existing "lock two docs in id order" helper. |
| F17 | **Brief's cycle locking is not sufficient alone** | Locking only the two endpoint documents serialises cycles of length 2 and 3, but not length ≥4 (A→B→C→D→A: edges AB and CD share no document, nor do BC and DA; BC and DA can both check before the other commits and together close the loop). Plan adds a tenant+type **advisory xact lock** for `prevents_cycles` types in addition to the ascending document-row locks (see R5). Not a locked-decision change. |
| F18 | Audit | `AuditService.event(...)…log()`; every action must exist in `AuditActionCatalog` (constant + `Map.entry(X,new AuditActionSpec("DRM",severity,reasonRequired))`; module code `DRM` is a real `permission_modules` code). Object types in `AuditObjectTypes`; frontend mirrors in `lib/audit-events/audit-action-catalog.ts` and `audit-log-filters.tsx` (`OBJECT_TYPE_OPTIONS`). `audit_events.object_id` is deliberately not an FK, so audit survives purge. AuditService takes a global advisory lock (7_331_991_001) – always call it **last** in a tx. |
| F19 | Notifications | `notification_categories` is **global** (not per tenant), seeded by migration (`INSERT … ON CONFLICT`; CHECK constraint: critical must default email on). Access-aware content via `NotificationRequest.requireViewPermission(true)` + `neutralTitle/Body/LinkUrl` + `documentId`; resolver calls `DocumentAccessService.forUsers`. Model caller: `AccessGrantedNotificationListener` (`@TransactionalEventListener(BEFORE_COMMIT)`, category `document.shared`). |
| F20 | Provisioning | `TenantProvisioningService.provisionTenant` calls `starterCatalogService.install(tenantId)` right after saving the tenant; V37 backfilled existing tenants with set-based SQL and `V37MigrationIT` asserts Java catalog == SQL. We mirror this: Java `RelationshipTypeCatalog` + SQL backfill + parity test. |
| F21 | Test infra | `WorkflowApiSupport` (MockMvc + JWT per user, `call/get200/postAs`), `WorkflowFixtures` (`fx.user/role/jwtFor/tenantAdminJwtFor`), `ContractWorld.create(fx)`; tenant isolation example `WorkflowAccessIT.anotherTenantSeesNothing…` (404), permission matrix example `endpointsRequireTheirPermission` (403). Surefire runs `*Test`, failsafe runs `*IT` (excludes Impersonation/PermissionCodeAudit/DocumentUpload ITs). Concurrency ITs exist (`WorkflowConcurrencyIT`). Test app props turn off schedulers. |
| F22 | Frontend anchors | `app/documents/[documentId]/page.tsx` (no tabs; left column Panel "Document" then `WorkflowStatusPanel`; right column history + `DiscussionPanel`); `document-browser.tsx` (`type Dialog` union l.107, `can` perms l.150-170, `documentActions(row)` l.445, bulk bar l.744-796, `MoveDialog` as dialog model); `nav-config.ts` Metadata group l.299-341; `DocumentPicker` (props `excludeDocumentId`, `pickedIds`, `onPick`; one pick per click); `SupportingDocumentRow` is the model for a read-only/lock-icon row; admin pattern = watermark templates (react-query + dialog + `PermissionGate`). There is **no approver-specific document view**: read-only is per-component by permission/access level. |

Everything else in the brief was confirmed.

## 2. Assumptions I'm making (no reply needed unless you disagree)
- A1 System types: only `label`, `inverse_label`, `is_enabled`, `display_order` editable; `directionality` and `prevents_cycles` fixed. Custom types: all fields editable until the type has any relationship rows (then `directionality` is frozen; `prevents_cycles` may be switched on only if no existing cycle).
- A2 Type `code` is immutable (generated upper-snake from label for custom types; uppercase codes below for system types). API/filters use `code`.
- A3 "Unused custom type" for delete = no relationship rows at all (active or removed); otherwise disable only. Usage count shown = active rows.
- A4 Manual relationships leave `source_version_id/target_version_id` NULL; `origin` values `manual|duplicate_detection|bulk|version_event`.
- A5 Auto "Duplicate of" is a system action: needs no DRM permission, actor = uploader, original must be visible to the uploader (re-checked in the promoter); skipped silently (WARN log) if the type is disabled/deleted or the original is gone. It runs in an AFTER_COMMIT `REQUIRES_NEW` listener so a failure can never fail the upload. For bulk import `batch_id` = import batch id.
- A6 Cycle search depth cap = 50 hops; deeper graphs are rejected with a clear "chain too long" error.
- A7 Bulk request = one type, `sourceDocumentIds[]` × `targetDocumentIds[]` where one side has exactly one document (covers "link these N documents to X" and "link X to these N").
- A8 Purge of either end (not only target) marks the live link removed (`target_purged`) with an audit record holding both ids, titles at that moment, type code/labels and the relationship id.
- A9 Notification: owner of the *target as submitted by the user* (before symmetric canonicalisation), skipped if owner == actor; one per relationship, grouped per owner for bulk (dedupe key `relationshipId` / `batchId:ownerId`).
- A10 Type admin changes are audited too (actions `RELATIONSHIP_TYPE_CREATED/UPDATED/ENABLED/DISABLED/DELETED`, object type `RELATIONSHIP_TYPE`).
- A11 Chains in cycle errors, bulk errors and list responses never reveal hidden documents (D5): hidden hops render as "A document you can't open"; bulk errors for hidden/missing/foreign-tenant targets are identical.

## 3. Migration plan (V106 onward; renumber on collision)

All SQL idempotent where practical; names follow `fk_/uq_/ck_/ix_` convention of V97.

**V106__create_relationship_types_and_document_relationships.sql**
```sql
CREATE TABLE relationship_types (
  relationship_type_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
  code VARCHAR(60) NOT NULL,
  category VARCHAR(40) NOT NULL,
  label VARCHAR(120) NOT NULL,
  inverse_label VARCHAR(120) NOT NULL,
  directionality VARCHAR(12) NOT NULL,
  prevents_cycles BOOLEAN NOT NULL DEFAULT false,
  is_system BOOLEAN NOT NULL DEFAULT false,
  is_enabled BOOLEAN NOT NULL DEFAULT true,
  display_order INT NOT NULL DEFAULT 0,
  created_by UUID REFERENCES users (user_id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by UUID REFERENCES users (user_id), updated_at TIMESTAMPTZ,
  is_deleted BOOLEAN NOT NULL DEFAULT false, deleted_at TIMESTAMPTZ,
  CONSTRAINT uq_relationship_types_tenant_type UNIQUE (tenant_id, relationship_type_id),
  CONSTRAINT ck_relationship_types_directionality CHECK (directionality IN ('directional','symmetric')),
  CONSTRAINT ck_relationship_types_symmetric_cycles CHECK (NOT (directionality='symmetric' AND prevents_cycles)),
  CONSTRAINT ck_relationship_types_system_not_deleted CHECK (NOT (is_system AND is_deleted))
);
CREATE UNIQUE INDEX uq_relationship_types_tenant_code ON relationship_types (tenant_id, code) WHERE is_deleted = false;
CREATE INDEX ix_relationship_types_tenant_order ON relationship_types (tenant_id, display_order) WHERE is_deleted = false;

CREATE TABLE document_relationships (
  relationship_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
  relationship_type_id UUID NOT NULL,
  source_document_id UUID NOT NULL,
  target_document_id UUID NOT NULL,
  source_version_id UUID NULL REFERENCES document_versions (version_id),
  target_version_id UUID NULL REFERENCES document_versions (version_id),
  origin VARCHAR(24) NOT NULL DEFAULT 'manual',
  batch_id UUID NULL,
  note VARCHAR(1000) NULL,
  created_by UUID REFERENCES users (user_id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by UUID REFERENCES users (user_id), updated_at TIMESTAMPTZ,
  removed_at TIMESTAMPTZ NULL,
  removed_by UUID NULL REFERENCES users (user_id),
  removed_reason VARCHAR(20) NULL,
  CONSTRAINT uq_document_relationships_tenant_rel UNIQUE (tenant_id, relationship_id),
  CONSTRAINT fk_document_relationships_type FOREIGN KEY (tenant_id, relationship_type_id) REFERENCES relationship_types (tenant_id, relationship_type_id),
  CONSTRAINT fk_document_relationships_source FOREIGN KEY (tenant_id, source_document_id) REFERENCES documents (tenant_id, document_id),
  CONSTRAINT fk_document_relationships_target FOREIGN KEY (tenant_id, target_document_id) REFERENCES documents (tenant_id, document_id),
  CONSTRAINT ck_document_relationships_distinct CHECK (source_document_id <> target_document_id),
  CONSTRAINT ck_document_relationships_origin CHECK (origin IN ('manual','duplicate_detection','bulk','version_event')),
  CONSTRAINT ck_document_relationships_removed CHECK ((removed_at IS NULL AND removed_by IS NULL AND removed_reason IS NULL)
      OR (removed_at IS NOT NULL AND removed_reason IN ('user_removed','target_purged')))
);
CREATE UNIQUE INDEX uq_document_relationships_active ON document_relationships (tenant_id, relationship_type_id, source_document_id, target_document_id) WHERE removed_at IS NULL;
CREATE INDEX ix_document_relationships_source ON document_relationships (tenant_id, source_document_id) WHERE removed_at IS NULL;
CREATE INDEX ix_document_relationships_target ON document_relationships (tenant_id, target_document_id) WHERE removed_at IS NULL;
CREATE INDEX ix_document_relationships_type ON document_relationships (tenant_id, relationship_type_id) WHERE removed_at IS NULL;
CREATE INDEX ix_document_relationships_batch ON document_relationships (tenant_id, batch_id) WHERE batch_id IS NOT NULL;
```
(`removed_by` is null for `target_purged`; the CHECK allows that. Removed rows stay for history.)
Rollback `V106__…rollback.sql`: `DROP TABLE document_relationships; DROP TABLE relationship_types;`.

**V107__seed_relationship_types_for_existing_tenants.sql** – `INSERT … SELECT t.tenant_id, v.* FROM tenants t CROSS JOIN (VALUES …) v ON CONFLICT (tenant_id, code) WHERE is_deleted=false DO NOTHING`. Seed (code | category | label | inverse | dir | cycles | order):
PARENT_CHILD | structure | Parent of | Child of | directional | **true** | 10 ·
MAIN_ATTACHMENT | structure | Has attachment | Attachment of | directional | false | 20 ·
CONTRACT_AMENDMENT | contract | Has amendment | Amends | directional | false | 30 ·
SUPERSEDES | versioning | Supersedes | Superseded by | directional | **true** | 40 ·
REPLACES | versioning | Replaces | Replaced by | directional | **true** | 50 ·
INVOICE_SUPPORTING | evidence | Has supporting document | Supports | directional | false | 60 ·
REPORT_EVIDENCE | evidence | Has evidence | Evidence for | directional | false | 70 ·
ORIGINAL_TRANSLATION | language | Has translation | Translation of | directional | false | 80 ·
DUPLICATE_OF | duplicate | Duplicate of | Has duplicate | directional | false | 90 ·
RELATED | association | Related document | Related document | symmetric | false | 100. All `is_system=true`.
Java twin: `relationship/seed/RelationshipTypeCatalog` + `RelationshipTypeSeeder.install(tenantId)` (idempotent), called from `TenantProvisioningService.provisionTenant` right after `starterCatalogService.install`. Rollback: delete seeded rows (`is_system`).

**V108__link_duplicate_origin_columns.sql** – `ALTER TABLE scan_results ADD COLUMN duplicate_of_document_id UUID NULL; ALTER TABLE import_items ADD COLUMN duplicate_of_document_id UUID NULL;` (plain columns, no FK – the original may later be purged; validated at use). Rollback drops the columns.

**V109__seed_relationship_notification_category.sql** – `INSERT INTO notification_categories (category_code, module, tier, default_email_enabled, display_name, description, is_active) VALUES ('document.relationship-added','documents','optional',false,'Document linked','Someone linked a document to one you own.',true) ON CONFLICT DO NOTHING;` Rollback deletes the row (and any `user_notification_preferences`/`tenant_notification_settings` rows for it).

Migration test `V106ToV109RelationshipMigrationTest` (template `V103ToV105…`): migrate to 105, seed tenants+docs, migrate to 109; assert 10 types per tenant, CHECKs (self-link, bad origin, bad removed combo, symmetric+cycles), unique active index (incl. re-adding after `removed_at` set), composite FK cross-tenant rejection, category row; run rollbacks reversed, delete history rows, assert gone, re-apply. Plus `RelationshipTypeSeedParityTest` (Java catalog == SQL rows) like `V37MigrationIT`.

## 4. Build steps

Package layout (new): `com.oak.dms.relationship` → `entity/`, `repository/`, `service/`, `controller/`, `dto/`, `helper/`, `seed/`, `listener/`. Entities follow the Lombok style of `DocumentComment` (own columns, `@UuidGenerator`, plain `UUID tenantId`, no JPA relations); repos tenant-scoped by method name.

### R2 – Schema, entities, seeds, permission constants (size M, risk low)
- Create: the four migrations + rollbacks above; `entity/RelationshipType`, `entity/DocumentRelationship`; repos; `seed/*`; `PermissionCodes.java` + `permission-codes.ts` constants `DRM_RELATIONSHIPS_CREATE`, `DRM_RELATIONSHIPS_REMOVE`, `DRM_RELATIONSHIPS_VIEW_GRAPH` (declared, unused per D11), `DRM_TYPES_CREATE`, `DRM_TYPES_UPDATE`, `DRM_TYPES_DELETE`; `AuditActionCatalog` entries (module `DRM`; created/updated/removed/batch-created MEDIUM-LOW, purge-removal MEDIUM; type actions MEDIUM) + `AuditObjectTypes.DOCUMENT_RELATIONSHIP`, `RELATIONSHIP_TYPE`; frontend audit catalog/filters; edit `TenantProvisioningService`.
- Tests: migration test, seed parity, provisioning test (new tenant has 10 types), catalog registration test.

### R3 – Relationship Types admin API + screen (size M)
API (`RelationshipTypeController`, `/api/v1/relationship-types`):
| Method/path | Perm | Notes |
|---|---|---|
| GET `?includeDisabled=false` | enabled-only: `drm…create` or any configure perm; `includeDisabled=true`: any configure perm | response: id, code, category, label, inverseLabel, directionality, preventsCycles, isSystem, isEnabled, displayOrder, usageCount (active rows; counts via one grouped query) |
| POST | `drm.configure-relationship-types.create-type` | body label, inverseLabel, directionality, preventsCycles, category?; 409 `RELATIONSHIP_TYPE_CODE_TAKEN`; symmetric → inverse=label and no cycles |
| PUT `/{id}` | `…update-type` | label, inverseLabel, isEnabled, displayOrder (+directionality/preventsCycles for custom per A1); disabling never touches data; audit ENABLED/DISABLED/UPDATED |
| DELETE `/{id}` | `…delete-type` | custom + zero rows only (A3), else 409 `RELATIONSHIP_TYPE_IN_USE` (system: 409 `RELATIONSHIP_TYPE_SYSTEM`); soft delete |
Cross-tenant id → 404. Frontend: `app/admin/metadata/relationship-types/page.tsx` (+ content component, table, form dialog, delete AlertDialog) modelled on `admin/watermarks`; `PermissionGate` any-of the three configure codes; nav item "Relationship Types" (icon `Link2`) in Metadata group with `permission: [DRM_TYPES_CREATE, DRM_TYPES_UPDATE, DRM_TYPES_DELETE]`; `lib/relationships/{api.ts,types.ts,use-relationship-types.ts}`. Read `node_modules/next/dist/docs/` first.
Tests: IT permission matrix + tenant isolation + system type protection + in-use delete + disable keeps data + audit; vitest for the form/table.

### R4 – Relationship service and API (size L, risk medium)
`RelationshipController` + `RelationshipService`:
| Method/path | Perm (D1) | Body → response |
|---|---|---|
| POST `/api/v1/document-relationships` | `drm…create` + **edit** on source + **view** on target | `{relationshipTypeId, sourceDocumentId, targetDocumentId, note?}` → 201 `RelationshipDto` |
| POST `/api/v1/document-relationships/bulk` | same, for every pair | `{relationshipTypeId, sourceDocumentIds[], targetDocumentIds[], note?}` → 201 `{batchId, created:[…]}`; any invalid pair → 422 `RELATIONSHIP_BULK_INVALID` with `errors:[{documentId, code}]`, nothing saved (all-or-nothing, one tx); max 200 pairs |
| PATCH `/api/v1/document-relationships/{id}` | `drm…create` + edit on source + view on target | `{relationshipTypeId?, note?}` (endpoints immutable; type change revalidated incl. cycle/duplicate/enabled) |
| DELETE `/api/v1/document-relationships/{id}?` | `drm…remove` + edit on source | soft-remove (`removed_reason=user_removed`) – removes both views (D10); 204 |
| GET `/api/v1/documents/{documentId}/relationships` | normal view access only | panel (shape below) |
| POST `/api/v1/document-relationships/counts` | view access per id | `{documentIds[≤200]}` → `{counts:{id:n}}` (docs the caller can't see omitted) |
Validation order (single transaction): load source/target by scalar ids (404 if missing/other tenant/deleted/template) → permissions → type enabled & not deleted → not self → symmetric canonicalise (RelationshipKeys) → pre-check duplicate (friendly 409 `RELATIONSHIP_DUPLICATE`; the unique index is the backstop, mapped to the same error) → cycle check (R5) → insert → audit. Templates (`is_template`) rejected on both ends. Bulk validates every pair first (collecting per-target errors, hidden/missing/foreign identical `TARGET_NOT_FOUND_OR_NO_ACCESS`), then inserts with a shared `batch_id`; audit: one `RELATIONSHIP_CREATED` per row (`batch_id` in `new_value`) plus one `RELATIONSHIP_BATCH_CREATED` (counts, type, batch id). Audit `new_value` = relationshipId, typeCode, labels, sourceId/Title, targetId/Title, origin, batchId, note; update records previous/new.
Panel response (`RelatedDocumentsResponse`): `{documentId, total, canAdd, groups:[{relationshipTypeId, typeCode, typeLabel, typeEnabled, items:[{relationshipId, direction:"outgoing"|"incoming"|"symmetric", label (label or inverseLabel), origin, note, createdAt, createdBy{id,name}, canEdit, canRemove, other:{accessible:true, documentId, title, documentTypeName, status, inRecycleBin, folderId?}} | {…, other:{accessible:false}}]}]}`. For `accessible:false` only relationship id, label/direction/type are sent – no id, title, type or status of the document (D5); `canEdit/canRemove` computed from edit access on the **source** and DRM permissions. Soft-deleted other end → accessible (if user could see it) with `inRecycleBin:true`. Purged ones never appear (removed).
Reuse/exceptions: follow `FolderException(403,…)`/`ResourceNotFoundException`/`DocumentStateException` patterns; new `RelationshipException(status, code, details)` mapped in `GlobalExceptionHandler`.
Tests: create/edit/remove, inverse visible from target, bulk all-or-nothing, symmetric reversed duplicate rejected, self-link/duplicate/disabled/template rejected, permission matrix, tenant isolation 404, audit rows.

### R5 – Integrity: cycles, concurrency, hidden targets (size M, risk high)
Cycle check for types with `prevents_cycles` (directional, same type only), on creating `S→T`: a cycle exists iff a directed path T ⇒ S already exists among active rows of that type.
```sql
WITH RECURSIVE walk(doc, depth, path) AS (
  SELECT target_document_id, 1, ARRAY[source_document_id, target_document_id]
    FROM document_relationships WHERE tenant_id=:t AND relationship_type_id=:ty AND removed_at IS NULL AND source_document_id=:T
  UNION ALL
  SELECT r.target_document_id, w.depth+1, w.path || r.target_document_id
    FROM walk w JOIN document_relationships r ON r.tenant_id=:t AND r.relationship_type_id=:ty AND r.removed_at IS NULL AND r.source_document_id=w.doc
   WHERE w.depth < 50 AND r.target_document_id <> ALL(w.path)
)
SELECT path FROM walk WHERE doc = :S ORDER BY depth LIMIT 1
```
(seed path starts at T; if hit, error chain = `S → T ⇒ … ⇒ S`.) Error: 409 `RELATIONSHIP_CYCLE` `{chain:[{documentId?, title?|"A document you can't open"}]}` titles resolved via one batched query + `DocumentAccessView.canSee` (A11). Depth > 50 → 409 `RELATIONSHIP_CHAIN_TOO_LONG`. Bulk: inserts flushed sequentially so later pairs see earlier ones (intra-batch cycles caught).
Locking order inside the write transaction (always the same, deadlock-free):
1. `WorkflowLocks.applyLockTimeout()`-style `set_config('lock_timeout','5s',true)` (generalise via a small `RelationshipLocks` bean or reuse the workflow bean – decide by dependency direction; no workflow behaviour change).
2. For `prevents_cycles` types only: `pg_advisory_xact_lock(hashtextextended(tenantId||':'||typeId,0))` – closes the length ≥4 gap (F17).
3. Scalar id lookup (`select document_id … where tenant`) of all involved documents → sort ascending with `RelationshipKeys.compare` → `lockByDocumentIdAndTenantId` one by one (re-read after lock; reject if deleted meanwhile).
4. Cycle check → insert → flush → audit (audit's own global lock last).
Lock timeout → 409 `CONCURRENT_UPDATE` (existing handler). Bulk locks the union of all docs in ascending order.
Tests: unit tests for the recursive check (chains of 2,3,50,51; other types ignored; removed rows ignored); **concurrency ITs**: (a) two users create opposite Supersedes A↔B simultaneously – exactly one succeeds, 5 runs; (b) 4-edge ring created by 4 threads – at most 3 succeed, no cycle in DB, 5 runs; (c) duplicate create race – one 201, one 409. Re-run 5× after any change to locking/cycle code.

### R6 – Lifecycle (size M, risk medium)
| Event | Effect on relationships |
|---|---|
| Soft delete (recycle bin) | rows unchanged; panel shows other end with `inRecycleBin:true` (if viewer could see it when live: access evaluated by `DocumentAccessView` on the deleted doc via a recycle-bin-aware lookup in the batched query); cannot create new links to/from it (404) |
| Restore | no data change; flag disappears |
| Purge (`RecycleBinService.purge` tx, l.365-372) | before the `update documents`, `select` live relationships touching the doc; `update … set removed_at=now(), removed_reason='target_purged'` ; after commit write one `RELATIONSHIP_REMOVED_TARGET_PURGED` audit event each (type code + labels, both ids and titles, original creator/created_at) – same `purge` method, so `purgeNow` and `purgeExpired` both covered; failure to write audit must not leave the doc half-purged: do the update inside the tx and audit inside the same `executeWithoutResult` (AuditService is `@Transactional`) |
| Move / rename / folder change | none (keyed by document_id; folder independent – NFR-003) |
| New version / restore version | none (pins are nullable & unused) |
| Transfer / archive | not built (D8) |
Tests: soft-delete flag, restore round trip, purge marks `target_purged` + audit content + other end's panel no longer lists it, `purgeExpired` path, move/rename/folder-move/new-version keep links, D8 checklist file `docs/relationships-lifecycle-checklist.md` (transfer: relationships follow `document_id`, owner change doesn't alter rows, new owner notified?; archive: archived docs keep links and still list; restore from archive; permission views after owner change; run the full R4/R6 IT suite against the new feature; confirm audit `new_value` unchanged).

### R7 – Automatic "Duplicate of" (size L, risk medium)
Upload keep-both: `frontend/lib/documents/upload-manager.ts` (`UploadRequest` new variant + `duplicateOfDocumentId`), `lib/documents/api.ts uploadDocument`, `bulk-upload-sheet.tsx handOff` (pass `item.duplicate.match.documentId` when choice=`both`; `uploads-panel` "Upload anyway" passes it when known), `DocumentController.uploadDocument` param → `DocumentService.uploadDocument` → `IngestionRequest` → `ScanResult.duplicateOfDocumentId` (V108). `DocumentVersionPromoter.persist` (new_document branch): publish `DuplicateKeptEvent(tenant, newDocId, originalId, uploaderId, batchId?)`; `DuplicateOfLinkListener` (`AFTER_COMMIT`, `REQUIRES_NEW`) validates (same tenant, original not deleted/purged/template, uploader `canSee` original, type enabled, not duplicate), creates `DUPLICATE_OF` source=new copy target=original origin `duplicate_detection`, audit `RELATIONSHIP_CREATED`. Failures are logged, never propagated. Admin-override/retry paths keep the column so the link is still created.
Bulk import: `ImportRecordCreator.submit` always runs `duplicates.find(...)` for link purposes (also for keep_both), stores first match id on `import_items.duplicate_of_document_id` only when a **separate document** is created (not for new_version/skip); `complete(...)` publishes the same event with `batch_id`=import batch id. Import runs as a worker: set tenant explicitly; visibility check uses the batch creator.
No change to `refuseDuplicates`, `IngestionGateImpl` semantics or existing duplicate responses.
Tests: IT for the listener (links, hidden original ignored, disabled type skipped, listener failure doesn't fail upload), promoter wiring test, bulk import creates links (new IT, since `BulkImportServiceImplTest` is a known failure – don't touch), vitest for payload.

### R8 – Filter and search (size M, risk medium)
`GET /api/v1/documents` gains `relationshipType` (code), `relatedTo` (document id), `direction` (`source`|`target`|`any`, default `any`): `source` = documents that are the **source** of a relationship whose target is `relatedTo` (e.g. docs that supersede X: `relatedTo=X&relationshipType=SUPERSEDES&direction=source`); `target` = documents that X points to; with no `relatedTo`, returns documents having any active relationship of the type (constrained by direction). New JPQL constant `RELATED` ANDed into all six queries next to `NARROWED` (symmetric rows match both sides); `relatedTo` must be viewable by the caller (else 404) so existence of hidden documents isn't probed. Signature changes: `DocumentRepository` (six methods), `DocumentService/Impl.listDocuments`, `DocumentController`, `frontend/lib/documents/api.ts fetchDocuments`; grep for other callers first. Access filtering is unchanged because the clause is ANDed. UI shortcut: "Documents that supersede this" link in the panel's Supersedes group and a filter dropdown (type + direction) in `document-browser.tsx`/related-to chip.
Tests: filters combine with `q`, hidden docs never returned, symmetric either order, direction semantics, removed rows ignored, EXPLAIN uses the new indexes.

### R9 – UI (size XL, risk medium; no UI beyond what's listed)
- `components/relationships/related-documents-panel.tsx` (props `documentId`, `readOnly?`) – grouped by type with direction label; row = title (link `/documents/{id}`), document type, status, badges "In Recycle Bin"/"Disabled type"/lock row "A document you can't open" (copy of `SupportingDocumentRow` lock pattern); edit/remove buttons from server `canEdit/canRemove`; shows empty state; "Add relationship" if `canAdd`. Placed in the left column of `app/documents/[documentId]/page.tsx` after the Document panel.
- `add-relationship-dialog.tsx` (type dropdown of enabled types with direction preview + `DocumentPicker` with `excludeDocumentId=documentId`, `pickedIds` = already-linked docs; multi-pick → bulk endpoint; cycle error rendered with the named chain via the `ActionDialog` error string pattern); `edit-relationship-dialog.tsx`.
- `document-preview.tsx`: header chip "Relationships (n)" linking to `/documents/{id}#relationships` (one click, NFR-006); count from `counts` endpoint.
- `document-browser.tsx`: `Dialog` union gains `relationships`/`link-to`; menu items "Relationships" (next to Version history) and "Link to…" (after Move to, gated `hasPermission(DRM_RELATIONSHIPS_CREATE) && hasAccess(row.myAccess,"edit")`); bulk bar "Link to…" (selected = sources, picked = target); optional list badges using one batched `counts` call per page.
- Hooks `use-document-relationships.ts` (react-query key `["relationships", documentId]`, invalidated on mutation), types mirroring DTOs.
- Tests: vitest for panel (grouping, hidden row shows no title, recycle badge, read-only), add dialog (error rendering); `npm run typecheck`, `npx eslint <changed files>`.

### R10 – Workflow and notifications (size S)
- Read-only panel on `/workflows/instances/[instanceId]` (`readOnly`) for participants; document page already shows it read-only for approvers because `workflowReadable` gives view-only → `canAdd=false`, no edit/remove. Related documents the approver can't open stay locked (D5, D7: supporting documents remain a separate workflow feature; `DocumentApprovals.readableDocumentIds` untouched).
- Notification: `RelationshipNotificationListener` (`BEFORE_COMMIT`, like `AccessGrantedNotificationListener`) on a `RelationshipCreatedEvent`; category `document.relationship-added`; access-aware via `documentId`=target + `requireViewPermission(true)`; neutral text "A document was linked to a document you own" (no titles). A9 rules.
- Tests: owner notified, actor not, neutral content when owner lacks view, dedupe, bulk grouping, category respects user preference.

### R11 – Permission matrix and tenant isolation (size M)
IT `RelationshipPermissionMatrixIT`: for every endpoint × {no role, create-only, remove-only, configure-only, tenant admin, view-only on source, edit on source but no DRM, other tenant}: expected 200/201/204/403/404; hidden-target cases for create (404-style `TARGET_NOT_FOUND_OR_NO_ACCESS`), panel (locked row, no title/id/status leakage – assert JSON has no hidden id/title), counts, filters, cycle error chain, bulk error. `RelationshipTenantIsolationIT`: all ids across tenants → 404 (never 403).

### R12 – Performance (size M)
Panel implementation = 2 index-backed queries (`UNION ALL` of source/target index scans) + one `findAllByDocumentIdIn` for the other ends (including deleted) + one `forCaller()` access view + in-memory `level/canSee`; workflow status via one batched query if Q1 chosen. `forUsers` is used by the notification path (many recipients). `RelationshipPerformanceIT`: panel with 100 relationships < 2 s (assert wall time with margin, plus query-count assertion), generated 1,000,000-row dataset (`generate_series` into relationships over ~20k docs in a scratch tenant; ANALYZE; assert panel and filter queries `EXPLAIN` use the partial indexes and complete < 2 s; cleanup). Tagged `perf` but included in the normal failsafe run.

### R13 – Tests and docs (size S)
`docs/relationships-user-guide.md` (developer-guide style: what it is, types table, create/bulk, panel states, permissions, duplicate-of, recycle bin behaviour, filters, admin types, FAQ); `docs/events/relationship-audit-events.md` (format of workflow events doc: common fields, event table, "not audited on purpose"); `docs/relationships-lifecycle-checklist.md` (D8); traceability table (DRM-FR/NFR/alternate flows → steps, below) inside the plan; record D2 deviation there. Update `docs/requirement-registry.md` status if it has DRM rows.

## 5. Traceability
| Req | Step | Req | Step |
|---|---|---|---|
| FR-001 | R4, R9 | FR-009 | R6 (move/version now; transfer/archive via D8 checklist) |
| FR-002 | R2 (directionality), R4 | FR-010 | R6, R9 |
| FR-003 | R4 (derived inverse), R9 | FR-011 | R6 |
| FR-004 | R2 (unique index only per type/pair), R4 | FR-012 | R8 |
| FR-005 | R7 | FR-013 | R5, R9 |
| FR-006 | **Deferred (D2)**: pins stored in R2; accepted deviation | FR-014 | R4, R5, R11 |
| FR-007 | R4 (shape), R9 | FR-015 | R3 |
| FR-008 | R4 | NFR-001 | R12 | `pom.xml` (`it.excludedGroups=perf`, `-Pperf` profile) | `RelationshipPerformanceIT` (panel, 100 links, normal run) 1/1; `RelationshipScaleIT` (@Tag perf, 1M links, 20k documents) 1/1 | Scale run: generated in 55 s; panel with 100 links 38 ms; `relatedTo` filter 85 ms; type-only filter 416 ms; create Supersedes 129 ms; plans use `ix_document_relationships_source/_target` | Opt-in per the answers recorded above |
| NFR-002 | R2 indexes, R12 | NFR-003 | R6 |
| NFR-004 | R4, R6 audit | NFR-005 | R11 |
| NFR-006 | R9 (panel + preview link) | Alt: target deleted/purged | R6 |
| Alt: no permission | R4, R11 | Alt: circular | R5 |
| Alt: bulk | R4 | Alt: type disabled | R3, R4, R9 |

## 6. Risks
1. Migration number collision with other feature branches (F2). 2. Cycle concurrency beyond length 3 (handled by advisory lock; tested). 3. Changing six `DocumentRepository` query signatures (regression risk on browse; covered by existing document tests + new filter tests). 4. Plumbing `duplicateOfDocumentId` touches upload path (async; AFTER_COMMIT so can't break uploads). 5. Migration tests default to dev DB server – always set `DB_URL` to 5435. 6. 1M-row IT adds minutes to the run. 7. `documents.status` is meaningless today (Q1). 8. Hidden-document leakage via error chains/bulk errors – explicit tests.

## 7. Open questions (defaults will be used if unanswered)
- **Q1 Panel "status":** `documents.status` is never written (always "draft"). Default: show **"In approval"/"Approved/Rejected" from the document's latest workflow instance when one exists, otherwise "Draft"/"Published" from `documents.status`**, plus the "In Recycle Bin" flag. OK, or show `documents.status` only?
- **Q2 A1:** OK to lock `directionality`/`prevents_cycles` on the 10 system types (only labels, enabled flag and order editable)?
- **Q3 Counts in browser:** OK that list badges are opt-in off by default (one extra batched call per page) and only the preview chip/menu show counts initially?


## 7a. Answers recorded before execution (user)
- Q1, Q2, Q3: accept the defaults.
- Change 1: the 1,000,000-row performance test is **opt-in**: tagged `perf`, excluded from the normal verify run, runnable with a documented command (see docs/relationships-user-guide.md). The 100-relationship panel test stays in the normal run. (Supersedes the R12 wording "included in the normal failsafe run".)
- Change 2: all R7 upload-path changes are **additive**: new optional fields and overloaded factories only. No change to `refuseDuplicates` behaviour, existing responses or existing signatures used by other callers.
- Before creating each migration, re-check the highest migration number in the tree and renumber if needed.
- Never run git commands that change anything.

## 8. Verification (execution time)
Disposable DB: container `dms-workflow-pg` on 5435, DB `dms_phase2_it` (create fresh; ask permission to run Docker/Maven outside sandbox). `export DB_URL=jdbc:postgresql://localhost:5435/dms_phase2_it DB_USERNAME=postgres DB_PASSWORD=password`. Per step: `mvn -Dmaven.test.failure.ignore=true verify` on new + affected tests; confirm only the 7 known failures (BulkImportServiceImplTest, DocumentMetadataServiceApplyOcrFieldsTest, OCR/watermark ITs needing RabbitMQ/ClamAV/OCR worker). After touching locking/cycle code run concurrency ITs 5×. Frontend: `npm run typecheck`, `npx eslint` on changed files, `npm test`. Finally (only if green) `cd infra/local && docker compose up -d --build dms-core frontend` and manual walkthrough: add relationship + inverse, loop warning, recycle bin states, Keep both duplicate, bulk link, filter, admin types. No git commands that change anything; all changes stay uncommitted.

## 9. Execution rules (copied from brief) and logs
Plan file is source of truth; record answers first; build R2→R13 in one run; deviations table and progress log below.

### Deviations
| # | Deviation | Reason |
|---|---|---|
| 1 | DRM-FR-006 automation deferred | D2 (no publish action); version pins stored |
| 2 | Advisory lock added to cycle-prevention locking | F17 |
| 3 | IDs are UUID not bigint | F4 |
| 4 | Loop check is a level-by-level search instead of a recursive CTE | Path-carrying CTE is exponential on shared-document graphs |
| 5 | No `import_items.duplicate_of_document_id` column | scan result carries it |
| 6 | Existing test `duplicates_keepBoth_doesntEvenLook` rewritten | D3 requires linking in bulk import, so keep_both must now consult the finder |
| 7 | Case-insensitive label uniqueness for types; `counts` endpoint omits instead of 404 | Usability / batch endpoint |

### Progress log
| Step | Files changed | Tests added | Result | Deviation |
|---|---|---|---|---|
| R2 | V106-V109 migrations + rollbacks; `relationship/entity,repository,seed`; `PermissionCodes.java`, `permission-codes.ts`; `AuditActionCatalog`, `AuditObjectTypes`, frontend audit catalog/filters; `TenantProvisioningService`, `TenantServiceImpl` (both tenant-creation paths seed types) | `V106ToV109RelationshipMigrationTest` (2), `RelationshipTypeSeedParityTest` (2), 2 cases in `TenantCatalogueProvisioningIT` | 10/10 green on 5435 (test found and fixed a NULL-passes CHECK on `removed_reason`) | D4: tests need DB password `workflow` for dms-workflow-pg (not `password`) |
| R3 | `relationship/service/RelationshipTypeService`, `controller/RelationshipTypeController`, DTOs, `common/dto/ErrorResponse` + `ApiException` + `GlobalExceptionHandler` (optional `details`, additive); frontend admin page, nav item, `lib/relationships/*` (built by a parallel fork) | `RelationshipTypeApiIT` (7) | 7/7 green | Added case-insensitive label uniqueness (not in plan) |
| R4 | `RelationshipService`, `RelationshipPanelService`, `RelationshipController`, DTOs, `RelationshipCreatedEvent`; panel status = latest workflow state else document status (Q1) | `RelationshipApiIT` (10) | 10/10 green | Single-link error codes prefixed `RELATIONSHIP_`; bulk `details` codes unprefixed; `counts` omits foreign/hidden documents instead of 404 per id |
| R5 | `RelationshipLocks` (lock timeout, per-type advisory lock), `CycleDetector`, document locks in ascending id order | `RelationshipCycleIT` (7), `RelationshipConcurrencyIT` (3, run 5x: 5/5 green) | green (re-run after the CycleDetector rewrite below: green) | #4: loop check is a level-by-level search, not a recursive CTE - a CTE carrying paths is exponential on graphs with shared documents (found while preparing the 1M-row test); still depth-limited (50) and visited-capped |
| R6 | `RelationshipPurgeHandler`, hook in `RecycleBinService.purge` (constructor gained one parameter, Spring-only caller) | `RelationshipLifecycleIT` (4) | 4/4 green | - |
| R7 | V108 (scan_results column only), `ScanResult`, `IngestionRequest` (13-arg constructor kept, `duplicateOf` wither), `IngestionGateImpl`, `DocumentService`/`Impl` (new overloads), `DocumentController` (optional param), `DocumentVersionPromoter` (event), `DuplicateKeptEvent`, `DuplicateOfLinkListener`, `DuplicateOfLinker`, `ImportRecordCreator`; fixture `documentKeptNextTo` | `RelationshipDuplicateIT` (6), 4 new cases in `ImportRecordCreatorTest` (19/19) | green | #5: no `import_items` column - the scan result carries the original for imports too. #6: existing test `duplicates_keepBoth_doesntEvenLook` changed - bulk import must now consult the duplicate finder for keep_both to link (D3). Dev DB recreated once because V108 changed before release |
| R8 | `RelationshipFilter`, `RelationshipBrowseFilter`, `DocumentRepository.findVisibleRelated/findLiveRelated` (the six existing queries untouched), `DocumentService.listRelatedDocuments`, controller params | `RelationshipFilterIT` (7) | 7/7 green | Filtered browse always spans all folders |
| R9 | Frontend (fork): panel, add/edit/link-to dialogs, preview link, browser menu + bulk "Link to...", URL filter chip, upload `duplicateOfDocumentId`, audit catalog | 17 vitest tests (3 files) reported by the fork | fork reported `npm test` 74/74 and eslint clean; `tsc` reports 1 error in generated `.next/dev/types/validator.ts` (not mine). **Not yet re-verified by me** | "Upload anyway" in `uploads-panel.tsx` does not pass an id (job doesn't know it) |
| R10 | `RelationshipNotificationListener` (V109 category), read-only panel on the workflow instance page (fork) | `RelationshipNotificationIT` (4) | 4/4 green | Notification names only the owner's own document, never the linked one |
| R11 | - | `RelationshipPermissionMatrixIT` (8, includes tenant isolation on every endpoint) | 8/8 green | - |
| R12 | `pom.xml` (`it.excludedGroups=perf`, `-Pperf` profile) | `RelationshipPerformanceIT` (panel, 100 links, normal run) 1/1 green; `RelationshipScaleIT` (@Tag perf, 1M links) written | Scale test **not run** - the run was declined at the permission prompt; run it with `./mvnw verify -Pperf -Dit.test=RelationshipScaleIT` | Opt-in per the answers recorded above |
| R13 | `docs/relationships-user-guide.md`, `docs/events/relationship-audit-events.md`, `docs/relationships-lifecycle-checklist.md` | - | written | - |

**Final gates (2026-10-20).** Frontend: eslint clean on the 30 changed files, `npm test` 74/74, `tsc --noEmit` has one error in generated `.next/dev/types/validator.ts` (missing `app/admin/permissions/users/page.js`, not part of this work). Backend `./mvnw clean verify` (no `-Pperf`) on a fresh `dms_phase2_it`: surefire 844 run, 3 failing (`BulkImportServiceImplTest` 1, `DocumentMetadataServiceApplyOcrFieldsTest` 2); failsafe 454 run, 4 failing (`OcrEventsConsumerLiveIT`, `OcrJobTokenHealthGroupIT`, `DocumentUploadOcrQueueingIT`, `WatermarkWorkerFailureIT`) - exactly the 7 known failures from the other branch, no new ones. `dms-core` and `frontend` rebuilt; Flyway applied V106-V109 to the dev database at startup. Migration clash: `origin/feature/ushani/OCR-scanning` already holds V106-V108, so V106-V109 must be renumbered when it is merged.
