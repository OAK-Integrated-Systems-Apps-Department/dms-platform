# Folder tree - structure rules and how they are enforced (DOC-14)

One shared folder tree per tenant (`folders`, V51). Code: `folder/FolderService`, `folder/FolderRepository`.

## The rules

| Rule | Enforced by |
|---|---|
| Sibling names are unique among live folders, ignoring case | `uq_folders_live_sibling_name` (database); `FolderService.save` turns a violation into 409 `FOLDER_NAME_TAKEN` |
| A folder is not its own parent; a parent and a document's folder belong to the same tenant | `ck_folders_not_own_parent`; composite `(tenant_id, ...)` foreign keys |
| **No cycles**: a folder can't be moved into itself or below itself | `FolderService.move` (`FOLDER_MOVE_INTO_ITSELF`), under the tenant lock |
| **At most 20 levels** (`FolderService.MAX_DEPTH`) | `create` and `move` (`FOLDER_TOO_DEEP`), under the tenant lock |
| **No live child under a deleted parent**; only an empty folder (no subfolders, documents or pending uploads) is deleted | `create` and `delete`, under the tenant lock |

The cycle, depth and parent rules read the tree and write afterwards, in READ COMMITTED transactions. Before DOC-14 nothing
stopped two concurrent changes from each passing its check against the old tree and together breaking a rule (two opposite
moves made a cycle; two moves each within the depth limit exceeded it; a child created while its parent was deleted stayed
live under it). The database has no constraint for these rules - they are tree-shaped, not row-shaped - so they are made safe
by running structure changes one at a time per tenant.

## The tenant lock

`create`, `move` and `delete` call `FolderService.lockTree(tenantId)` as the **first statement** of their transaction:

```sql
select pg_advisory_xact_lock(hashtextextended('folder-tree:' || <tenantId>, 0))
```

(`FolderRepository.lockHierarchy`, the same `hashtextextended` convention as `BookmarkService`.)

- **Transaction-scoped.** `pg_advisory_xact_lock` is released by the commit or rollback of the transaction that took it; there is
  no unlock and no session-level lock to leak. It is taken through the repository inside the service's `@Transactional`
  method, so it is on the same connection as the validation and the writes (the service is called through its Spring proxy
  from the controller; nothing in the path opens a new or `REQUIRES_NEW` transaction). Taken outside a transaction it would be
  released at once, which is why it must stay the first statement of a transactional method.
- **Before any read.** Nothing in the method has loaded a folder before the lock, so a change that waited reads the committed
  result of the one before it (no stale entity in the persistence context, no stale parent).
- **Identity.** One lock per tenant: the key is the fixed namespace `folder-tree:` plus the tenant id, hashed to 64 bits **by
  PostgreSQL**, so every application instance sharing the database derives the same lock regardless of JVM. Tenants lock
  different keys and never wait for each other. Two tenants whose keys collided would share a lock (a needless wait, never a
  missed one); a collision with another advisory lock of the application (`AuditService` uses a fixed constant, `BookmarkService`
  a different text key) would need an identical 64-bit value.
- **Short.** The protected section is a few queries (checks, one `UPDATE`/`INSERT`, the audit row). No storage, antivirus,
  network or messaging I/O happens under it; keep it that way.
- **Lock order.** The folder lock is the first lock of the transaction. `AuditService` takes its own chain lock when it writes the
  audit row, after it; nothing takes the audit lock and then a folder lock (the only callers of `create`/`move`/`delete` are the
  `FolderController` endpoints), so the two cannot deadlock.
- **Not locked:** `rename` (names are index-backed; it does not change the structure), `listAll`, `path`, `require*`, and the
  access-mode and confidential-flag writers (`FolderAccessService`, `ConfidentialService`) - none changes parents or deletion.
- **Operations.** Concurrent structure changes of one tenant queue; there is no `lock_timeout`, so a transaction stuck while
  holding the lock would block that tenant's folder changes (reads are unaffected). Waiters show in
  `pg_locks where locktype = 'advisory' and not granted`.

## Cycle-safe subtree query

`FolderRepository.findSubtreeIds` (the move cycle check, and bulk export of a folder's subtree) is a `WITH RECURSIVE` over
folder ids using `UNION`, not `UNION ALL`: a folder already in the result is not expanded again, so the query ends - and lists
every folder once - even if the data holds a cycle. Do not add a depth or path column to it without a cycle guard: with one the
rows stop being duplicates and `UNION` no longer ends the recursion. Every other ancestor walk in the code (`treePath`,
`depthBelow`, `FolderAccessView.governingOf`, `RetentionResolver.folderChain`, `ExportArchiveBuilder`, `ShareTargets`) already
carries a visited set or a depth cap.

## Checking an existing database

`docs/folder-integrity-check.sql` (read-only, identifiers only) lists cycles, folders deeper than 20, live folders under
deleted parents, cross-tenant or missing parents, and - for the follow-up below - live documents filed in a deleted folder.
Cycle members also appear under "too deep" (their chain never ends). Nothing repairs a corrupted tree automatically; a repair is
a separate, reviewed step.

## Known gap (separate follow-up, not part of DOC-14)

A **document** can still be filed into a folder that is being deleted: `DocumentVersionPromoter` writes the scan result's
`folder_id` without re-checking the folder, and `DocumentServiceImpl.moveDocuments` reads the folder and then updates the
document. `delete` refuses while a pending or infected upload targets the folder, which closes the long window; what remains is
the short window before that pending row exists and `moveDocuments` against `delete`. The fix belongs on the document side
(lock the folder row `FOR SHARE` and check `NOT is_deleted`; have `delete` lock the row `FOR UPDATE` before it counts) and must
**not** use the tenant lock, which would serialise every document promotion of the tenant.

## Tests

`FolderServiceTest`, `FolderServiceLockTest` (lock first, once, tenant key, rules unchanged), and `FolderTreeConcurrencyIT`
(real PostgreSQL; the first operation is held inside its critical section by a row lock, and the second is observed waiting on
the advisory lock before the row is released): opposite moves, depth, create against delete in both orders, an existing
cycle, tenant isolation, rollback and failed validation releasing the lock, a lock held by another database session, and no
connection retained.
