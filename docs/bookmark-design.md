# Bookmark Documents (BD) — design

Module 4.4. A bookmark is a **private** shortcut: only its owner ever sees it, including in the admin UI.
Contrast with Pin Important Documents (4.3, `docs/pin-audience-design.md` if present / `PinService`'s own
doc comment), a **shared** shortcut whose audience the pinner chooses. A document can be both, independently;
the two features never interact.

## 1. Decisions

| Topic | Decision |
|---|---|
| Permission to add | `bd.bookmark-unbookmark-documents.bookmark` (`PermissionCodes.BD_BOOKMARK`). V134 grants it by default to every role already holding `docm.documents.view`, so "can view ⇒ can bookmark" holds from day one. |
| Permission to remove | None beyond authentication — `BookmarkController#remove` never checks a permission. Cleaning up your own bookmark must never be blocked by a role change. The seeded `...remove-bookmark` permission code stays in the catalog for role-display purposes only. |
| Permission to list/status | `docm.documents.view`. |
| Access | Every read and write goes through `DocumentService#visibleItems` — the same rules the document browser uses. A bookmark never grants access and is never a way to read a document's title you couldn't otherwise see. |
| Not found | Bookmarking a document that doesn't exist and one the caller can't see return the identical 404, so existence is never leaked. |
| Duplicates | Idempotent: `PUT` on an existing bookmark never creates a second row (enforced by the `(user_id, document_id)` primary key + `ON CONFLICT DO NOTHING`), and reports `created: false`. If a note was supplied, it's applied as an update instead. |
| Cap | `dms.bookmarks.max-per-user` (env `DMS_BOOKMARKS_MAX_PER_USER`, default 500) — plain deployment config, no per-tenant override and no admin screen (unlike Pin's `pin_settings` table). Over the cap: `409 BOOKMARK_LIMIT_REACHED`. |
| Prune on write | Hitting the cap first deletes the caller's own bookmarks whose documents are no longer visible (recycle bin, access revoked, or gone), then re-checks. This is the *only* place a hidden row is ever cleaned up — there is no background sweep. |
| Hidden vs deleted — the key difference from Pin | Pin deletes the caller's own pin the moment its document becomes unreachable (cheap to re-pin). A bookmark is **kept**: hidden from the list and counted in `unavailableCount` (never shown with a title) while in the recycle bin or access-revoked, reappearing the instant the document is visible again. Only a hard purge deletes it, via `BookmarkPurgeHandler`. |
| Archived | Stays listed, with `archived: true` — still openable. |
| Note | Optional, private, ≤500 characters, editable independently of add/remove. Never appears in an audit payload. |
| Audit | `BOOKMARK_ADDED` / `BOOKMARK_REMOVED` (module BD, LOW severity) — on real changes only, never on an idempotent no-op. |
| Lifecycle | Rows cascade-delete with the user or the tenant. A deactivated user keeps their rows. |

## 2. Data model

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
```

`V134__create_document_bookmarks.sql` also grants `BD_BOOKMARK` to every role already holding
`docm.documents.view` at migration time (mirrors `V87`'s pattern exactly — `NOT EXISTS`, not
`ON CONFLICT`, because `resource_scope` can be `NULL`). A tenant/role created **after** V134 gets the
grant from `TenantStarterCatalogService`/`StarterCatalog` instead — see that class's own `FieldDef`
analogue, `StarterCatalog.FIELDS`' sibling concept for roles is out of scope here since roles aren't
seeded from a catalogue the way metadata fields are; new roles are built by a tenant admin choosing
permissions explicitly, the same as every other permission in this system.

## 3. API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/bookmarks` | Paged, sorted (`bookmarkedAt`\|`title`), searched (`q`, title-contains). `{ total, page, size, unavailableCount, items }`. |
| `PUT` | `/api/v1/bookmarks/{documentId}` | Body `{ note?: string }`. `{ created: boolean }`. |
| `PATCH` | `/api/v1/bookmarks/{documentId}/note` | Update note on an existing bookmark; 404 if none. |
| `DELETE` | `/api/v1/bookmarks/{documentId}` | 204 always. |
| `GET` | `/api/v1/bookmarks/status?ids=a,b,c` | Up to 200 ids. `{ bookmarkedDocumentIds: [...] }` — lets a list page render filled icons for its visible rows in one call, instead of loading the whole (up to 500-row) bookmarks list. |

## 4. Frontend

- `lib/bookmarks/api.ts` / `use-bookmarks.ts` — `useBookmarkStatus(ids)` (batched per page, merges
  `stores/bookmark-status-store.ts`'s optimistic overrides so every instance stays in sync the instant
  any one toggles), `useBookmarkToggle()`, `useBookmarksList(params)` (the My Bookmarks page).
- `components/bookmarks/bookmark-button.tsx` — `BookmarkButton`/`BookmarkedIndicator` both take an
  optional `bookmarked` prop: a list batches one status call for the page and passes it down per row;
  a standalone placement (preview, the document's own page) omits it and fetches its own.
- Placed wherever `PinButton`/`PinnedIndicator` are (preview, the document's own page, the document
  browser's rows), plus a one-click menu action in the document browser (My Documents and search
  results, which share that component), Shared with me, and the My Bookmarks page itself.

## 5. What this brief could not fully satisfy

- **New-tenant/new-role defaults beyond the migration backfill.** The brief asks that "new
  tenants/roles must get the same default." This repo has no mechanism that automatically grants one
  permission whenever another is granted to a role — `V87`'s own precedent for exactly this kind of
  pairing (`mdtm.manage-document-templates.*` following `mdtm.manage-content-types.update`) is also a
  one-time migration backfill only, with no ongoing coupling. Building a new "implied permission"
  system would be new architecture well beyond this feature's scope, so V134 matches the existing
  precedent instead: new tenants get it right from `TenantStarterCatalogService` (fixed in the same
  change), and any role an admin builds afterward is their own explicit choice, as every other
  permission in this system already works.
