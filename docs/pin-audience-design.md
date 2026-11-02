# Pinned documents with an audience — design

Status: **implemented (revision 3): backend and frontend are written and tested; not yet deployed to the
local stack.** Where the build differs from the earlier draft, section 13 says so.
Module: PID (4.3 Pin Important Documents). Replaces the personal-pin model of
`V101__create_document_pins.sql` / `PinService` / `PinController`.

## 1. What changes

Today a pin is personal: one row per (user, document), only that user sees it.

After this change there is **one kind of pin**. When someone with the pin permission pins a
document, they choose **who sees it**: everyone, everyone except some people, or specific people.
There is no separate "pin for me". **Only the person who pinned it can unpin it or change who
sees it.**

The registry's module description already says this ("an authoritative, organization-level
shortcut that the whole audience sees the same way"). PID-FR-006 and the current UI copy
("Pins are personal — nobody sees anyone else's") say otherwise and must be amended (section 9).

## 2. Rules

| # | Rule |
|---|------|
| R1 | The admin controls who can pin through the **existing** permission `pid.pin-unpin-documents.pin`, assigned via roles. No new permission. |
| R2 | When pinning, the pinner chooses the audience: **All users**, **All users except …** (select all, then untick), or **Specific users**. |
| R3 | A pin never grants access (PID-NFR-003). A recipient who cannot open the document does not see the pin. |
| R4 | **Only the pinner can unpin / change the audience** (`pid.pin-unpin-documents.unpin` plus ownership). Recipients cannot remove it. |
| R5 | The pinner always sees their own pin, even if they excluded themselves from the audience. |
| R6 | The pin limit (`pid.configure-pin-limits`, per organisation) now caps **how many documents one person can pin**. It does not limit how many pins other people place on someone. |

## 3. Data model

New migration — next free number, **check at implementation time** (this repo has had
migration number collisions after merges, see commit `a3d2924`).

```sql
-- One row per (document, pinner): "this person pinned this document for an audience".
CREATE TABLE document_pin_assignments (
    assignment_id  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    document_id    UUID        NOT NULL,
    pinned_by      UUID        NOT NULL REFERENCES users (user_id),
    audience_mode  TEXT        NOT NULL CHECK (audience_mode IN ('ALL', 'USERS')),
    pinned_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, document_id, pinned_by),
    FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id) ON DELETE CASCADE
);

-- Meaning depends on audience_mode:
--   USERS -> these users SEE the pin      (inclusions)
--   ALL   -> these users do NOT see it    (exclusions: "select all, then untick")
CREATE TABLE document_pin_audience_members (
    assignment_id  UUID NOT NULL REFERENCES document_pin_assignments ON DELETE CASCADE,
    user_id        UUID NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    PRIMARY KEY (assignment_id, user_id)
);
```

**Why rules, not one row per recipient.** "Select all, untick a few" is stored as `ALL` plus the
few exclusions, so a user who joins later sees the pin automatically, and an organisation of
5,000 does not mean 5,000 rows per pin. A per-recipient snapshot would silently miss new users.

**Migrating existing personal pins.** Each `document_pins` row becomes an assignment with
`audience_mode = 'USERS'` and the pinner as the only member, so nobody's pins disappear and
nobody sees more than before. Keep `document_pins` for one release, then drop it in a later
migration (so a rollback is possible).

## 4. Visibility (the hard part)

A user sees a pin when **any** of these holds, and the document passes the access check:

1. They are the pinner (R5), or
2. `mode = ALL` and they are **not** in the members, or
3. `mode = USERS` and they **are** in the members.

...and they are an active user in the tenant, and they can see the document through
`DocumentService.visibleItems` — the same access rule as everywhere else, so no access is
widened (R3).

**One deliberate difference from today.** `PinService.list()` currently *deletes* a pin the
user can no longer see (PID-FR-008). For a pin owned by someone else we **hide, never
delete**: a recipient losing access for a week must not destroy the pinner's pin. (A pinner
losing access to their own document is the one case where it is removed, as now.)

**Warning for the pinner.** The audience sheet should say how many chosen users cannot open
the document ("12 of 57 selected can't open this — they won't see it"). That needs a "can
user X see document D" check for many users, which `visibleItems` (caller-only) does not
offer. **Open technical risk**: confirm how access resolves (`document_access` V54,
access-generation V61, folder inheritance V53) and whether a batch check is cheap, before
promising this in the UI. It is not needed for version 1.

## 5. API

Same base path and verbs as today, so callers change as little as possible.

| Method | Path | Permission | Behaviour |
|---|---|---|---|
| `GET` | `/api/v1/pins` | `docm.documents.view` | Every pin the caller can see (section 4), newest first. Each item adds `pinnedBy`, `mine` (boolean) and an audience summary (`ALL`, `ALL except 3`, `7 people`). |
| `PUT` | `/api/v1/pins/{documentId}` | `pid.pin-unpin-documents.pin` | Pin, or change the audience of the caller's pin. **Body required:** `{ "mode": "ALL" \| "USERS", "userIds": [...] }`. Caller must be able to see the document. `409 PIN_LIMIT_REACHED` at the limit (only when it creates a new pin). |
| `DELETE` | `/api/v1/pins/{documentId}` | `pid.pin-unpin-documents.unpin` | Removes **the caller's own** pin on that document, for every recipient. Nobody else's. A caller with no pin of their own gets `404`. |
| `GET` | `/api/v1/pins/{documentId}/audience` | `pid.pin-unpin-documents.pin` | The caller's own audience, to pre-fill the sheet when editing. |
| `GET` | `/api/v1/pins/{documentId}/audience-preview` | `pid.pin-unpin-documents.pin` | The no-access counts for the warning. Optional; see section 4. |

Because the pin is keyed by (document, pinner), "only the pinner can unpin" is enforced by
the query itself — a recipient's `DELETE` has nothing of theirs to delete. Worth an explicit
test so it cannot regress.

The `PUT` body is **required** (no body is rejected with `400`), so a stale client can't quietly
broadcast a document to everyone.

New audit actions (module `PID`, beside `PIN_LIMIT_CHANGED`): `PIN_ADDED`,
`PIN_AUDIENCE_CHANGED`, `PIN_REMOVED` — carrying the mode and the member count, not the
full user list.

## 6. UI

- **Pin button** (`components/pins/pin-button.tsx`):
  - Not pinned by me → click opens the **audience sheet**.
  - Pinned by me → click opens the sheet to **edit the audience** or **Unpin**.
  - Pinned by someone else → shows the pin mark with "Pinned by {name}", **no unpin action**.
    If the person holds the pin permission they may still pin it themselves.
  - Hidden for people without the permission, as now.
- **Audience sheet** (new; reuse the `Sheet` and principal-search pattern in
  `components/folders/access-sheet.tsx`):
  - Radio: **All users** · **Specific users**
  - A searchable user list with checkboxes, **Select all**, **Clear**, and a live count
    ("42 of 57 selected"). In *All users* mode the ticks are the people who *see* it, and
    unticking someone records an exclusion.
  - The no-access warning (section 4), if available.
  - Save / Cancel; Unpin when editing.
- **Pinned Documents page** (`pinned-documents-view.tsx`): one list, with filters
  **All · Pinned by me · Pinned by others**. Each row shows who pinned it and who it is for
  ("Everyone", "Everyone except 3", "7 people"). Rows pinned by others have no unpin button.
- `PinnedMark` gets a "pinned by someone else" variant.
- The admin **Pin Limit** page: update the intro text (it currently says pins are personal)
  and the field label to "documents one person may pin".

## 7. Edge cases

| Case | Behaviour |
|---|---|
| Document deleted for good | Assignment cascades away (FK). |
| Document in recycle bin / recipient lost access | Hidden for that recipient, **not** deleted. |
| Document archived | Shown, flagged, as today. |
| Recipient deactivated | Ignored. |
| Pinner deactivated or deleted | Their pins stay (the organisation's shortcut now) but nobody can remove them. **Decision needed** — section 10. |
| Same person pins the same document again with a different audience | Replaces their audience (one row per document and pinner). |
| Two people pin the same document | Allowed; a recipient sees it once, with the earliest pinner shown. |
| Pinner's pin permission revoked later | Their existing pins stay and they can still unpin them (ownership + unpin permission). They cannot create new ones. |
| Audience of nobody | Rejected (`400 PIN_AUDIENCE_EMPTY`): `USERS` with no members, or `ALL` that excludes everyone. |
| Lowering the limit | Existing pins stay, as today. |

## 8. Tests

Backend — this is a visibility feature, so the access tests matter most:
- A recipient without access to the document does **not** see the pin, and it is **not**
  deleted; once access is granted it appears.
- `ALL` with exclusions: an excluded user doesn't see it; a user created afterwards does.
- `USERS`: only the listed users see it; the pinner always does (R5).
- Only the pinner can remove a pin: a recipient's `DELETE` removes nothing and returns `404`.
- Another user's pin on the same document is untouched by my `DELETE`.
- The limit counts only the pins a person placed; a recipient at the limit still sees others' pins.
- Cross-tenant: nothing leaks into another tenant.
- `PUT` without the pin permission is refused; `PUT` with no body is `400`.
- Migration: an existing personal pin becomes a `USERS` pin visible only to its owner.

Frontend (vitest): the audience sheet's select-all / clear / exclusion logic as pure
functions, like `nav-utils`.

## 9. Requirements amendment

Add to the registry's PID section and reconcile PID-FR-006:

- **PID-FR-010** A user holding the pin permission shall choose, when pinning, which users see
  the pin: all users, all users except selected ones, or selected users.
- **PID-FR-011** A user sees a pin only if they can access the document; the pin never grants access.
- **PID-FR-012** Only the person who pinned a document may unpin it or change its audience.
- **PID-FR-006** → "show each user the pins whose audience includes them, and the ones they placed".
- **PID-FR-007** → the maximum applies to the pins a person places.

## 10. Decisions needed before building

1. **Who holds the pin permission today.** Because it now also lets someone show a document to
   everyone, every role that has `pid.pin-unpin-documents.pin` gains that power the moment this
   ships. The migrations don't grant it to any role by default (they only create the
   permission), so it depends on what your admins assigned. **Review the role assignments
   before release.** If you want "pin for myself only" to stay available to most people, that
   is a second permission — you said you don't want it, so I haven't added it.
2. **Orphaned pins.** If the pinner leaves, can an administrator remove or take over their
   pins? Recommendation: add a separate `pid.manage-others-pins` permission for that, and keep
   "only the pinner can unpin" for everyone else. Or accept orphaned pins in version 1.
3. **Existing personal pins.** Migrate them as private (pinner-only) pins, as section 3
   proposes, or drop them? I recommend migrating.
4. **Audience types.** Individual users only (this design), or also departments and roles?
   The access sheet already searches all three, so adding them is cheap.
5. **Notify recipients** when something is pinned for them (the notification module exists)?
   Left out of version 1.
6. **A cap on pins per organisation**, so the read path stays bounded (PID-NFR-005) and the
   shared list doesn't fill up when many people can broadcast? Suggest a default in
   `application.yml` beside `dms.pins.default-max`.

## 11. Phasing

1. **Backend:** migration with data move, `PinService` rewrite, endpoints, audit actions, tests.
2. **Frontend:** audience sheet, pin button states, pinned-documents list and filters, copy updates.
3. **Later:** audience-preview warning, notifications, departments/roles, the
   manage-others-pins permission.

## 12. Impact

Backend and database change. No new permission, but the meaning of an existing one changes
(decision 1). The personal-pin table is migrated, then retired. The Pin Limit setting stays,
with a new meaning.

## 13. As built — differences from the draft above

Decisions taken where the design left them open (follow the recommendations in section 10):

| Decision | Taken |
|---|---|
| 1. Who holds the pin permission | **Still to do before release:** review which roles hold `pid.pin-unpin-documents.pin`. It now also lets a person show a document to everyone. |
| 2. Orphaned pins (pinner leaves) | **Deferred.** Nobody but the pinner can remove them. A `pid.manage-others-pins` permission is the follow-up. |
| 3. Existing personal pins | **Migrated** by `V122` as pinner-only pins (`USERS`, one member). `document_pins` is kept, unread, and marked superseded; drop it in a later migration. |
| 4. Audience types | **Individual users only.** Departments and roles are a follow-up. |
| 5. Notify recipients | **Left out.** |
| 6. Cap on pins per organisation | **Deferred**, recorded as a FOLLOW-UP comment on `PinService.MAX_READ`: the pinned section reads at most the newest 500 assignments. |

Differences from the sections above:

- **`DELETE /api/v1/pins/{documentId}`** quietly does nothing (204) when the caller has no pin of their own on
  that document, as it always did, instead of returning 404. A recipient's request still removes nothing.
- **Someone else's audience is not shown.** `audience` on a pin is only filled for the caller's own pins, so a
  recipient can't see how many people were left out. The pinned list says "Pinned by {name}" for those.
- **The sheet is a mode plus a people picker, not a full user list with checkboxes.** "All users" shows who is
  *left out*; "Specific users" shows who is *let in*; people are found by searching and shown as removable chips.
  A full checkbox list does not scale to a large organisation. Switching mode clears the chips, because the two
  lists mean opposite things.
- **`GET /api/v1/pins/audience-candidates`** searches active people in the caller's organisation and needs only
  the pin permission, not the user-administration ones.
- **`GET /api/v1/pins/{documentId}/audience`** returns the caller's own audience, with names, to pre-fill the
  sheet when editing. The no-access warning (`audience-preview`) is **not built**.
- **Audit:** `PIN_ADDED`, `PIN_AUDIENCE_CHANGED` and `PIN_REMOVED`, carrying the mode and the number of people.
- **Tests:** `PinAudienceIT` runs the rules against a real database (visibility for `ALL` and `USERS`, people who
  join later, no-access recipients hidden but not deleted, only the pinner can unpin, the limit counts only your
  own pins, invalid audiences, permissions, tenant isolation). `PinServiceTest` covers request validation and the
  merge rule. `lib/pins/audience.test.ts` covers the sheet's logic. The migration's carry-over of old pins has no
  test; it ran on a blank database in the integration run.
