# Task: build module 4.4 "Bookmark Documents" (BD) in the existing DMS

You are working inside the `dms-platform` repo (Spring Boot backend in `backend/dms-core`, Next.js frontend in `frontend`). Read `CLAUDE.md` first: **never run `git commit`, `git push` or anything that changes git history. Leave all changes uncommitted.**

Build the feature to match this repo's conventions. The closest existing feature is **Pin Important Documents (PID)**. Study these before writing code, and copy their patterns instead of inventing new ones:

- Backend: `com.oak.dms.pin.PinService`, `PinController`, `src/test/.../pin/PinServiceTest.java` and `PinAudienceIT.java`
- Migration style: `V101__create_document_pins.sql` (and its comments), plus the `db/rollback/` folder
- Visibility: `DocumentService#visibleItems` and `DocumentAccessService.DocumentAccessView#canSee`
- Purge hook pattern: `RecycleBinService` and `RelationshipPurgeHandler`
- Audit: `AuditActionCatalog` (the `PIN_*` entries) and `docs/audit_events_schema_spec.md`
- Frontend: `lib/pins/*`, `components/pins/*`, `app/pinned/page.tsx`, `components/dashboard/nav-config.ts`, `components/documents/browser-items.tsx`, `components/documents/document-preview.tsx`, `app/documents/[documentId]/page.tsx`

Do not modify Pin behaviour. Bookmarks are a separate, private feature.

## 1. What a bookmark is (and is not)

A **bookmark** is a private shortcut that only its owner ever sees. A **pin** is a shared shortcut whose audience the pinner chooses. A document can be both, independently. Bookmarks never grant access, never change a document's location, metadata or permissions, and are never visible to anyone else (including admins, in the UI).

## 2. Decisions already made (implement these; do not re-litigate)

| Topic | Decision |
|---|---|
| Permission to add | Gate on the already-seeded `bd.bookmark-unbookmark-documents.bookmark` (add `BD_BOOKMARK` to `PermissionCodes.java`). A new migration must **grant it by default to every existing role that holds document view**, so "any user who can view a document can bookmark it" stays true. New tenants/roles must get the same default. |
| Permission to remove | Removing your **own** bookmark needs only authentication. Never block a user from cleaning up. Keep the seeded `...remove-bookmark` code in the catalog for role display, but do not make the endpoint depend on it. Say so in a code comment. |
| Permission to list | `DOCM_DOCUMENTS_VIEW`, like `PinController#list`. |
| "View access" | Exactly `DocumentAccessView.canSee` via `documentService.visibleItems`. Templates are excluded by that call. Do not re-implement access rules. |
| Same "not found" | Bookmarking a document that does not exist and one the caller cannot see return the identical 404 (`ResourceNotFoundException("Document", id)`), so existence is never leaked. |
| Duplicates | Idempotent. `PUT` on an existing bookmark does not create a second row and returns `created=false`; the UI shows "Already in your bookmarks". Enforced by the primary key `(user_id, document_id)` and `INSERT ... ON CONFLICT DO NOTHING`. |
| Cap | Per-user cap from config `dms.bookmarks.max-per-user` (env `DMS_BOOKMARKS_MAX_PER_USER`, default 500). No tenant settings table, no new admin screen. Over the cap: 409 `BOOKMARK_LIMIT_REACHED`. Serialise adds per user with the same `pg_advisory_xact_lock` pattern as `PinService#pin`. |
| Reads never delete | `GET` is `@Transactional(readOnly = true)` and never mutates. (Do not copy `PinService#list`, which deletes on read.) |
| Archived | Kept and **listed with an `archived: true` flag**; still openable if the caller can see it. |
| In recycle bin | **Hidden** from the list, not deleted. Reappears if the document is restored. Counted in `unavailableCount`. |
| Access revoked / lost | **Hidden**, not deleted, and never shown with a title or any metadata. Reappears if access returns. Counted in `unavailableCount`. |
| Purged | Hard-deleted by a purge hook called from `RecycleBinService` when a document is purged (follow the `RelationshipPurgeHandler` pattern). Note: purge keeps tombstone rows (`purged_at`), so `ON DELETE CASCADE` does **not** fire on purge; the hook is required. Also delete rows for documents with `purged_at is not null` in a safety sweep if that fits an existing scheduled job. |
| Prune on write | When the caller is at the cap and adds a bookmark, first delete that caller's bookmarks whose documents are not visible/purged, then re-check the cap. This is the only place hidden rows are cleaned up. |
| Note | Optional private `note` per bookmark, max 500 chars, editable. No reminders, due dates, tags, folders, sharing or bulk-bookmark (out of scope). |
| Audit | Emit `BOOKMARK_ADDED` and `BOOKMARK_REMOVED` (module `BD`, severity LOW, like the `PIN_*` entries). **Never put the note in the audit payload.** Do not audit idempotent no-ops. |
| User/tenant lifecycle | Rows cascade-delete with the user and the tenant. Deactivated users keep their rows. |
| Which version opens | The document's detail page (`/documents/{id}`), i.e. the current version. |
| Ordering and paging | Newest first by default. Params: `page`, `size` (default 25, max 100), `sort=bookmarkedAt|title`, `dir`, `q` (title contains, escape with `LikePatterns`). Because rows are capped (500), read the caller's rows (max cap), run `visibleItems` once, then filter, sort and page in memory. |

## 3. Requirements to satisfy (amended spec)

Functional
- **BD-FR-001** Bookmark any document the caller can view; stores a reference only; never touches location or permissions.
- **BD-FR-002** Remove own bookmark at any time; underlying document untouched.
- **BD-FR-003** The "My Bookmarks" page (sidebar: My Work > My Bookmarks) lists all of the caller's available bookmarks (with paging, sort and title search).
- **BD-FR-004** Selecting a bookmark opens the document directly.
- **BD-FR-005** No duplicates; idempotent add with `created=false` feedback.
- **BD-FR-006** Per the table above: archived is flagged; in-bin and access-lost are hidden and counted; purged is deleted.
- **BD-FR-007** Bookmarked state is visibly indicated wherever the bookmark control appears (filled icon), as with `PinButton`.
- **BD-FR-008** Optional private note, editable from the Bookmarks page.
- **BD-FR-009** Per-user cap with a clear 409 message.
- **BD-FR-010** The list shows `unavailableCount` ("N bookmarks are currently unavailable") with no titles.

Non-functional
- **BD-NFR-001** One-click bookmark/unbookmark from the document list, preview, detail page, search results and Shared with me.
- **BD-NFR-002** List p95 under 1 s for 500 bookmarks; toggle under 500 ms. Index `(tenant_id, user_id, bookmarked_at DESC)`.
- **BD-NFR-003** Private per user, persisted server-side (cross-session, cross-device), tenant-scoped.
- **BD-NFR-004** Removal never changes the document in any way.
- **BD-NFR-005** Security: every endpoint derives user and tenant from `TenantContext`; no user id in any request. One user can never read or delete another user's bookmark.
- **BD-NFR-006** Accessibility: `aria-pressed`, labelled buttons, keyboard operable, works on narrow screens.

## 4. Data model

Create the next migration after the current highest (check `db/migration`; the latest seen was `V129`, and this repo has had number collisions after merges, so re-check). Add a matching file in `db/rollback/`. Mirror the style and comments of `V101`.

```sql
CREATE TABLE document_bookmarks (
    tenant_id      UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    user_id        UUID        NOT NULL REFERENCES users (user_id)     ON DELETE CASCADE,
    document_id    UUID        NOT NULL,
    note           VARCHAR(500),
    bookmarked_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, document_id),
    CONSTRAINT fk_document_bookmarks_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id) ON DELETE CASCADE
);
CREATE INDEX ix_document_bookmarks_user     ON document_bookmarks (tenant_id, user_id, bookmarked_at DESC);
CREATE INDEX ix_document_bookmarks_document ON document_bookmarks (tenant_id, document_id);
```

The same migration (or a second one) backfills the default role grants described in section 2. Look at how `V10` and later permission seeds attach permissions to roles, and follow that exactly. See `bookmark-erd.mermaid` for the diagram.

## 5. Backend

Package `com.oak.dms.bookmark` with `BookmarkController`, `BookmarkService` and a `BookmarkPurgeHandler` (called from `RecycleBinService` purge).

Endpoints under `/api/v1/bookmarks`:

| Method | Path | Notes |
|---|---|---|
| GET | `/` | Paged list. Response: `{ total, page, size, unavailableCount, items: [{ document: DocumentListItem, bookmarkedAt, note, archived }] }`. |
| PUT | `/{documentId}` | Body `{ note?: string }`. Idempotent. Response `{ created: boolean }` (200). If the bookmark exists and a note is supplied, update the note. |
| PATCH | `/{documentId}/note` | Update note; 404 if the caller has no bookmark on it. |
| DELETE | `/{documentId}` | 204 always (idempotent). Deletes only the caller's own row. |
| GET | `/status?ids=a,b,c` | Which of these document ids the caller has bookmarked (max 200 ids). Lets list pages render filled icons in one call without loading the whole list. |

Add `@Operation` descriptions in the same style as `PinController`. Add `BOOKMARK_ADDED` and `BOOKMARK_REMOVED` to `AuditActionCatalog` (constants and spec map entries) and document them (the audit docs under `docs/`). Add `dms.bookmarks.max-per-user` to `application.yml` next to `dms.pins`. Use `FolderException` with codes `BOOKMARK_LIMIT_REACHED`, and `BOOKMARK_NOTE_TOO_LONG` as the pins do.

## 6. Frontend

Mirror `lib/pins` and `components/pins`:

- `lib/bookmarks/api.ts`, `lib/bookmarks/use-bookmarks.ts` (React Query, optimistic toggle with rollback, shared cache key so every list/detail instance stays in sync).
- `components/bookmarks/bookmark-button.tsx` with a lucide `Bookmark` icon, filled when bookmarked. Visually distinct from the pin icon, placed next to it. Add it wherever `PinButton` is used (document browser list, preview, document detail page) and also to search results and Shared with me where documents are listed. Use the `status` endpoint to render state for visible rows.
- Toasts: "Added to your bookmarks", "Already in your bookmarks", "Removed from bookmarks", and the cap message from `BOOKMARK_LIMIT_REACHED`.
- `app/bookmarks/page.tsx` + `components/bookmarks/bookmarks-view.tsx` (page title, breadcrumb, heading and `DashboardShell initialActiveLabel` all say **"My Bookmarks"**, as `app/pinned/page.tsx` does for its label): table with title, type, folder, bookmarked date, archived badge, inline editable note, open action, remove action, search box, sort, paging, and the `unavailableCount` notice. Copy to explain the difference: "Bookmarks are private to you. To share a shortcut with others, pin the document."
- Sidebar: in `components/dashboard/nav-config.ts`, add **"My Bookmarks"** to the **My Work** section, directly after "Mentions" (My Work currently holds My Tasks, Inbox, Mentions). Use the lucide `Bookmark` icon (import it alongside the existing icons), `href: "/bookmarks"`, and `permission: PermissionCodes.DOCM_DOCUMENTS_VIEW` (My Work items without a `permission` show to everyone, but bookmarks need document view). Do not add a badge. Do not add this link until `app/bookmarks/page.tsx` exists; that file's header says a link that does nothing is worse than no link. Leave "Pinned Documents" in the Documents section unchanged.
- Add `BD_BOOKMARK` to `lib/auth/permission-codes.ts` to match the backend. Hide the add control when the user lacks it; never hide the remove control for an existing bookmark.
- Update the roles UI seed copy only if it is wrong (Viewer already lists the BD permissions).

## 7. Tests

- `BookmarkServiceTest` (unit): idempotent add, cap and prune-on-write, hidden vs archived rules, note length, in-memory paging/sort/search, `unavailableCount`.
- `BookmarkIT` (integration, like `PinAudienceIT`): cross-user isolation (user B cannot see, update or delete user A's bookmark), same 404 for missing vs invisible document, access revoked then restored (reappears), delete to bin then restore (reappears), purge (row gone), template excluded, tenant isolation, role-backfill migration grants.
- Frontend tests in the style of the existing `*.test.tsx` files: button states, optimistic toggle rollback, cap toast, unavailable notice.

## 8. Docs and wrap-up

- Add `docs/bookmark-design.md` (short: decisions table, API, data model, state rules).
- Update `docs/requirement-registry.md` section 4.4 with the amended requirements above. Keep wording consistent: the section is called **"My Bookmarks"** everywhere (sidebar, page title, requirements), and the registry's mixed use of "Bookmarks" should be changed to match.
- Run the backend tests and frontend lint/tests; fix what you break. Report what you ran and the results.
- Do not commit. Summarise changed files at the end and list anything in this brief you could not satisfy and why.

Work in this order: migration and rollback, service and controller, purge hook, audit entries, backend tests, frontend API and hook, button and placements, Bookmarks page, nav, frontend tests, docs. Ask before deviating from section 2.
