# 2.10 Check-in / Check-out and online editing

Delivered: **exclusive check-out / check-in** (CICO-FR-001..004, 009, 011) and **online editing through
Collabora Online over WOPI**, in exclusive or simultaneous mode (CICO-FR-005..008, CICO-NFR-002, 003, 006).
Online editing is **off for every organisation until its own administrator turns it on in the app** (Policies >
Online Editing) - and it also needs the Collabora container on the server. When it is off or unavailable everything
degrades to plain check-out / download / check-in.

## Check-out / check-in (always available)

1. **Check out** - takes an exclusive lock. Everyone who can open the document sees who holds it
   and since when (`GET /api/v1/documents/{id}/checkout`, the document page, and a pen/gavel icon in the
   document list). The page re-checks every 2 s (polling, not push).
2. The holder downloads, edits in their own Word/Excel/PowerPoint, then
3. **Check in** - uploads the edited file (`POST .../checkin`). It goes through the same malware
   scan as every upload and becomes the new current version; the lock is released as soon as the
   upload is accepted. If the scanner refuses the file outright the lock **stays**, so the holder
   can fix it and retry.
4. **Discard** gives the lock up with no new version. An administrator with
   `cico.force-release-lock` can **force-release** someone else's lock; a reason is mandatory and
   the audit entry is HIGH severity.

### Rules that never get around the lock

| Action | Checked out by someone else | Legal hold | Signature-locked |
|---|---|---|---|
| Check out / open the online editor | refused (409 `DOCUMENT_CHECKED_OUT`, names the holder) | refused (`DOCUMENT_LEGAL_HOLD`) | refused (`DOCUMENT_SIGNATURE_LOCKED`) |
| New version / restore | refused | refused | refused (already, DSI) |
| Move / delete | refused | refused | refused (already, DSI) |
| Manual metadata edit (`PUT .../metadata`) | refused | refused | refused (already, DSI) |
| Standalone signing: add page / place / remove a mark | refused | refused | refused (already, DSI) |
| Permanent delete / the hourly purge sweep | - | refused (skipped by the sweep) | - |

Enforced by `DocumentEditGuard`, called beside `DocumentSignatureLockService.guard`, so a user cannot
bypass the lock by uploading through the normal route instead of checking in. OCR and review-decision
writes use a different path on purpose and are not blocked by a check-out, so OCR results still land.

### Signatures and watermarks (why users cannot alter them by editing) - every file type

Whatever edits a document - check-out, the online editor, a save from the editor, a check-in - is refused while
the version it would change carries a signature (`SignedContentGuard`, error `DOCUMENT_HAS_SIGNATURE`). A
version counts as signed when ANY of these holds, **whatever its file type**:

1. the DMS holds a **valid signature** bound to it (workflow approval or standalone signing);
2. it was made by placing a **visual signature mark** (`created_reason = signed_copy`) - the mark is burned into the page;
3. the **file itself contains a signature** made elsewhere (`EmbeddedSignatureDetector`): a PDF with a `/ByteRange`
   signature dictionary, a Word/Excel/PowerPoint package with an `_xmlsignatures/` part, or an OpenDocument file
   with `META-INF/documentsignatures.xml`.

(1) and (2) are cheap database checks asked again on every save, so a document signed in the middle of an editing
session cannot be overwritten. (3) reads the file, so it is asked once when editing starts. If the file cannot be
read the answer is "refuse" (`503 DOCUMENT_FILE_UNAVAILABLE`), never "assume unsigned". This sits on top of the
signature lock a workflow approval sets (which also refuses new versions, moves and deletes). A STANDALONE signing
does not set that lock, which is why rules 1-2 are needed.

* A signature is bound to one version forever and a valid one locks the document, which cannot then
  be checked out or opened for editing at all. A check-in or an online save only ever **adds** a version;
  it never rewrites a signed one.
* **Watermarks** need no rule of their own: a watermark is drawn onto a **derived copy** (a watermark job's output), never into the stored
  file, so the file a user checks out never contains one to remove or change.

### Never an ambiguous lock (CICO-NFR-004)

The lock is three columns on `documents` (V127) set or cleared by single atomic `UPDATE`
statements, so two simultaneous check-outs have exactly one winner. Every lock also expires
(`dms.checkout.idle-timeout`, default `PT72H`). Past the expiry the lock blocks nobody, the next
check-out takes over (audited as `DOCUMENT_CHECKOUT_EXPIRED`), and the previous holder's late
check-in is refused with `CHECKOUT_EXPIRED` rather than silently overwriting.

### Legal hold

CICO-FR-009 needs a document-level legal hold and none existed (only a flag on audit events). V127
adds the minimal one: `documents.legal_hold` + who/when/why, placed and lifted through
`/legal-hold` and `/legal-hold/release` with `crm.place-legal-hold` / `crm.lift-legal-hold`.

## Online editing (Collabora, over WOPI)

`com.oak.dms.editing`. The editor is Collabora in its own container; the DMS never renders an editor
of its own (CICO-FR-006).

1. **Start**: `POST /api/v1/documents/{id}/edit-session` (needs `cico...check-out`). The backend checks access,
   then the frozen states, then the engine (on? answering? edits this file type?), and - in
   **exclusive** mode - takes the check-out. It returns the editor URL and a signed access token.
   The page posts the token into an iframe form, never in a URL.
2. **Engine calls back** to `/internal/v1/wopi/files/{id}` (CheckFileInfo, GetFile, PutFile) with that
   token. `/internal/**` is **not proxied by nginx** (same rule as the OCR internal endpoint). The token
   (`WopiTokenCodec`, HMAC-SHA256) names one document, one user, one tenant and an expiry.
3. **Every call re-checks the live document**, not just the token: same tenant, not deleted, and a
   **save** also needs no signature lock, no legal hold, and (exclusive) still checked out by that user -
   so a force-release, an expiry, or a signature made mid-session stops further saves.
4. **A save is a normal upload**: scanned by the ingestion gate, then it becomes the new current version.
   **Autosaves are ignored** (acknowledged, not stored) or every few minutes would be a version; Ctrl+S and
   closing the editor are kept. Saving is not checking in - the lock stays until "Done editing".

* **The switch is a permission-gated setting, not an environment variable (CICO-FR-010).** Each organisation has its
  own row in `tenant_editing_settings` (V128): `enabled` (default **off**) and `mode`. It is changed on the Online
  Editing page, or `PUT /api/v1/editing/settings`, by someone holding
  `cico.configure-editing-engine.select-provider` (`GET` needs either `cico.configure-editing-engine.*`); every change
  is audited (`EDITING_SETTINGS_CHANGED`). One organisation turning it on never affects another. The server-side
  config (`EDITING_COLLABORA_URL` etc.) only says WHERE the engine is; with no URL the server has no engine and
  turning it on is refused (`409 EDITING_ENGINE_NOT_CONFIGURED`; turning it off is always allowed).
* **Modes** (per organisation): `exclusive` (default; a session is a check-out) or `simultaneous` (no lock;
  everyone with edit access opens it together, and the engine shows who is present and reconciles edits).
* **Fallbacks (CICO-NFR-002)**: the organisation has it off -> 503 `EDITING_DISABLED`; engine down -> 503
  `EDITING_ENGINE_UNAVAILABLE`; a file type the engine cannot edit (e.g. PDF) -> 409 `EDITING_UNSUPPORTED_TYPE`. All
  say to use Check out, download, Check in.
* **Another engine (CICO-NFR-006)**: add a connector next to `EditingEngine` (discovery -> editor URL) and
  reuse the WOPI host if it speaks WOPI. No change to check-out, versions or audit.
* **Audit**: `DOCUMENT_EDIT_SESSION_STARTED` / `_SAVED`, plus the check-out and version events.

* **Together mode also needs the document type's say-so.** `simultaneous` applies to a document only when its
  document type has **Co-editing enabled** (Document Types admin page, `document_types.coediting_enabled`);
  otherwise that document stays `exclusive` - a session takes the check-out - whatever the organisation chose
  (`EditingSessionService.modeFor(tenant, document)`, also used by the save check in `WopiController`).

### Starting from a template

Admins add templates under Document Types > Templates. A user picks **New from template** in My Documents, names the
copy and presses Create (`POST /api/v1/document-templates/{id}/copy`): a scanned copy lands in the open folder and the
template is untouched. The copy's **file is named after the document** (`Client agreement - ACME.docx`), keeping the
template's extension. Once the virus scan finishes, a toast offers **Edit now** (opens the online editor, Ctrl+S saves
the filled-in file as the next version) - or **Open** when the file can't be edited in the browser (PDF) or online
editing is off/not permitted.

### Where the editor opens

"Edit online" opens the editor **inside the DMS page**, under the usual menus and breadcrumb. **Open full screen** (top
right) first asks the editor to save any unsaved changes (the same as Ctrl+S: a scanned new version), then opens
`/documents/{id}/edit?layout=full` in a **new tab** where the editor fills the whole window under a slim bar (title,
who last edited and when, Back, Done editing). The original tab shows a note ("open full screen in another tab", with
"Edit here instead") rather than a second editor, because two editors on one document would overwrite each other; the
lock stays with the same person, so the new tab's session just continues it.

A user can instead choose on **My Profile > Document editor** to have it open full screen straight away
(`user_preferences`, V129; `GET/PUT /api/v1/users/me/preferences`; default `inline`). It is cosmetic, so it needs no
permission beyond being signed in. The layout is read once when the editor opens and kept for that visit, so changing
it elsewhere can't reload the frame and lose unsaved edits.

Known limit: the save before expanding becomes a version only after the virus scan, so if the scan is slow the new tab
can open the previous version for a moment; it shows the new one on reload.

### Turning it on (local)

No `.env` change: `infra/local/docker-compose.yml` already points `dms-core` at the engine and has a development
token secret.

```
docker compose --profile collabora up -d collabora
```

Then, as an administrator, open **Policies > Online Editing**, switch it on and Save.

### Turning it on (production) - not done, needs decisions first

The `collabora` service and `EDITING_*` variables exist in `infra/production/docker-compose.yml` but nothing is
configured (no `EDITING_COLLABORA_URL`, so no organisation can turn it on). To enable: enough RAM (~1-2 GB; the server is already short), a public HTTPS address for the editor
proxied by the host nginx **with WebSocket upgrade** (set `EDITING_COLLABORA_URL`), a strong
`EDITING_TOKEN_SECRET`, `COLLABORA_FRAME_ANCESTORS` = `PUBLIC_URL`, and `COLLABORA_IMAGE` pinned to a tested tag.
Do not proxy `/internal/**`.

## Which file types can be edited in the browser

It is exactly what the editing engine says it can edit - read live from the engine's discovery document, not a
list kept here. Checked against Collabora Online 26.04: **Word** (docx, doc), **Excel** (xlsx, xls, ods, csv),
**PowerPoint** (pptx, ppt, odp), **text** (txt, rtf) and **OpenDocument** (odt, ods, odp) are editable.
**PDF is not**: Collabora offers it as view-and-comment only, so there is no browser editor for PDF content, and a
PDF can only be changed by Check out, edit the download in a PDF program, Check in. The page says so for any type
the engine cannot edit, instead of hiding the button silently. The signature rules above apply to a PDF checked out
that way exactly as to any other type.

## FOLLOW-UP (known gaps, deliberately not fixed here)

* **Embedded signatures are only detected in PDF, OOXML and ODF.** A legacy binary .doc/.xls/.ppt, or a signature
  format we do not recognise, looks unsigned to the detector. The DMS's own signatures (rules 1-2) are protected
  for every type.
* **A watermark someone bakes into a file themselves is not detected.** The DMS never stores a watermarked version,
  but a user could download a watermarked copy and upload it as a new version; nothing can tell it from any file.

* **The engine itself is still one per server.** On/off and mode are per organisation; WHICH engine (Collabora
  today) and its address are server settings. `cico.configure-editing-engine.configure-connection` currently only
  lets someone SEE the setting; choosing between engines per organisation needs a second connector first.
* **Not push.** The page polls every 2 s while the tab is visible; true push needs a server channel.
* **Save-back conflict handling is minimal.** Two saves are two versions; there is no merge screen when the
  engine's file is based on an older version than the current one (CICO "save-back conflict" alternate flow).
* **Edits since the last Ctrl+S are lost if the browser dies mid-session**, because autosaves are not stored.
* **A document can still be signed (workflow) while it is checked out.** Signing locks it, the holder's
  next check-in/save is then refused, and they must discard.
* **No expiry notification** to the previous holder when a lock lapses; only the audit entry exists.
* **Version purge** (retention, not the recycle bin) is not yet blocked by a legal hold - it belongs to the
  lifecycle module (DLM).
