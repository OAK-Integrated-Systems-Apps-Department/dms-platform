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
   scan as every upload and becomes the new current version. The lock is released **once that version
   exists** (the scan came back clean, or an administrator released the file from quarantine) - not
   merely because the upload was accepted. If the scan is still **pending** (the scanner is down and
   retrying, a very large file, a cloud scanner) the lock **stays** and the page says so; if the scanner
   refuses the file outright the lock stays too, so the holder can fix it and retry. After a pending
   check-in is promoted later, the check-out is **not** released automatically - nothing recorded says that
   version came from a check-in rather than a plain upload or an editor save by the same holder - so the
   holder discards it (or checks in again), or it expires (`dms.checkout.idle-timeout`).
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
bypass the lock by uploading through the normal route instead of checking in.

### The check-out is checked again when the version is promoted (DOC-07)

A file can be accepted long before it becomes a version, and the check-out can change in between. So
`DocumentVersionPromoter` checks it again, under the document's row lock, in the transaction that adds the
version (all scan-result reasons except the signing-generated `signed_copy`). A lease is one acquisition of the
check-out - who holds it and when it was taken (`checked_out_by`, `checked_out_at`; the expiry is fixed at that
moment and an expired lease stays on the row until it is taken over or released). The upload is identified by what
the gate recorded and no client can set: `uploaded_by` and `started_at`. Promotion is then:

| State of the check-out at promotion | Result |
|---|---|
| nobody holds an unexpired check-out | allowed, like any upload |
| someone **else** holds it (a takeover after expiry, or a check-out taken while the file was scanned) | refused, `DOCUMENT_CHECKED_OUT` |
| the uploader holds one taken **after** the upload was submitted (a new lease) | refused, `DOCUMENT_CHECKED_OUT` |
| the uploader holds one taken at or before the submission (the check-in's own lease) | allowed |
| the uploader's lease from before the submission has **expired** and nobody took over | refused, `CHECKOUT_EXPIRED` |

A refusal is permanent for that attempt: the ingestion ends `failed` with the reason (it is not retried and not
rescanned - see the scanning design), and nothing is changed, including the other user's check-out. Never decided
by user name alone and never by anything the client sends.

**Known limitation.** A force-release clears every check-out column, so a force-released check-out leaves nothing
to tell it from "never checked out": the pending check-in then promotes like any upload. Telling a check-in from an
ordinary upload - needed to release the lock automatically after a late promotion, or to refuse a check-in whose
lease was force-released - or detecting that the document changed since check-out, would need a persisted marker or
a recorded base version, i.e. a migration; that was not done. OCR and review-decision
writes use a different path on purpose and are not blocked by a check-out, so OCR results still land.

### Signatures and watermarks (why users cannot alter them by editing) - every file type

Whatever edits a document - check-out, the online editor, a save from the editor, a check-in, and also a plain
**version upload and a restore of an older version** (`POST /documents/{id}/versions` and
`.../versions/{versionId}/restore`, including a bulk import that adds a new version to an existing document) - is
refused while the version it would change carries a signature (`SignedContentGuard`, error `DOCUMENT_HAS_SIGNATURE`).
A version counts as signed when ANY of these holds, **whatever its file type**:

1. the DMS holds a **valid signature** bound to it (workflow approval or standalone signing);
2. it was made by placing a **visual signature mark** (`created_reason = signed_copy`) - the mark is burned into the page;
3. the **file itself contains a signature** made elsewhere (`EmbeddedSignatureDetector`): a PDF with a `/ByteRange`
   signature dictionary, a Word/Excel/PowerPoint package with an `_xmlsignatures/` part, or an OpenDocument file
   with `META-INF/documentsignatures.xml`.

(1) and (2) are cheap database checks asked again on every save, so a document signed in the middle of an editing
session cannot be overwritten. (3) reads the file, so it is asked once when editing starts - and, because they open
the current file the same way, on every version upload and restore (before anything is scanned, stored or versioned). If the file cannot be
read the answer is "refuse" (`503 DOCUMENT_FILE_UNAVAILABLE`), never "assume unsigned". This sits on top of the
signature lock a workflow approval sets (which also refuses new versions, moves and deletes). A STANDALONE signing
does not set that lock, which is why rules 1-2 are needed - and why upload and restore call `SignedContentGuard` as
well as the lock. Metadata edits, moves and deletes of a standalone-signed document are not covered by it: they do not
change the signed bytes.

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

## Recycle bin: purge, restore and legal hold must not overlap (DOC-09)

A permanent deletion destroys files, which no transaction can undo, so what it is allowed to destroy is decided under
a lock, and a restore or a legal hold cannot slip in while the files are going.

**Locking protocol** (`RecycleBinService`; one PostgreSQL row lock, so it holds across application instances):

* `purge` - the one place a document is deleted for good; manual purge, "empty the bin" and the hourly sweep all end here.
  It runs in **one transaction** (an explicit `TransactionTemplate`; the method is called from inside the class, so
  `@Transactional` on it would not apply) and starts with `select ... from documents where document_id = ? and
  tenant_id = ? for update`. Everything it knew before - the list, an entity, the sweep's query - is only a candidate.
* Under the lock, read from the row (not from an entity), it re-checks: still in the bin, not already purged, **no legal
  hold**, **no protected version** and, for the sweep, still the **same deletion** it selected (a document restored and
  deleted again has a new retention period). If any check fails, nothing is destroyed; "empty the bin" counts it as
  skipped, the sweep moves on, and a manual purge answers 409 (restored / legal hold / protected) or 404 (already gone).
* Only then are the files destroyed and, in the same transaction, the versions, metadata, access list, links and
  bookmarks are marked or removed. The lock is held until commit. The audit event and the notification are written
  **after** the commit, so only a purge that completed is recorded.
* `restore` takes the same lock after its permission check and re-reads the row: a document purged meanwhile is a 404;
  otherwise it is restored while the lock is held, so a purge that was waiting then finds it live and leaves it alone.
* Legal-hold placement is a single atomic `UPDATE ... WHERE deleted = false AND purged_at IS NULL AND legal_hold =
  false`, which also waits for the row lock and re-evaluates against the committed state: a document in the bin cannot
  be held (it is a 404), and neither can a purged one. A hold can therefore only be placed on a live document - after
  a restore - and the purge that follows will see it.

**File deletion is not transactional - failure semantics.** If deleting fails part way, or the commit fails after the
files are gone, or the application dies, the transaction rolls back: nothing is marked purged, no audit event is
written, and the document stays in the bin - but some of its files may already be destroyed. Deleting a file that is
already gone is a no-op, so the next purge (the sweep, or a manual purge) simply runs again and finishes. To stop such a
half-destroyed document being restored as an empty one, **restore also checks, under the lock, that the stored file of
every version still in use can be read, and refuses with `DOCUMENT_CONTENT_MISSING` if not** - the only way forward
for that document is to delete it for good. A failure before anything was deleted leaves the document fully restorable.

**Limits.** The row lock is held while storage is being deleted (a connection and that one document's row; other
documents are not affected, and a restore of that document waits). A slow object store with many versions lengthens
that. There is no lock timeout of its own. The retention-driven *version* purge (`VersionRetentionService.destroyDue`)
still checks a legal hold only when it selects the versions - a hold placed between selection and deletion is not seen;
that is a separate follow-up.

## Online-editor saves are spooled to disk, not held in memory (DOC-12)

The editing engine saves with WOPI `PutFile` (`WopiController.putFile`). The body used to be read into a `byte[]` - up to
200 MiB, twice over while it was read, held until the ingestion gate finished - so a few large saves at once could exhaust the
heap. It is now streamed to a private file (`WopiSaveSpool`) and heap use no longer depends on the size of the document
(measured: a 64 MiB save allocates under 1 MiB instead of 128 MiB; eight concurrent 64 MiB saves under a 900 MiB heap used to
run out of memory and now all succeed). `GetFile` already streams and is unchanged; so is the token protocol.

**Order of a save** (unchanged except where marked): valid token for this document -> live document in the token's tenant ->
`canWriteNow` (signature lock, legal hold, signature record, check-out) -> an autosave is acknowledged and its body is never
read -> **body streamed to the spool file, counted** -> embedded-signature check **from the file** -> the gate receives the
file with the **counted** length -> audit `DOCUMENT_EDIT_SESSION_SAVED` -> the file is deleted. Every refusal before the body
(401, 403, 404, 409, autosave) happens without reading or creating anything.

**The limit.** The existing 200 MiB cap (`MAX_SAVE_BYTES`) is unchanged and is enforced on the bytes that actually arrive:
exactly 200 MiB is accepted, one byte more is refused with 413 and reading stops there. A declared `Content-Length` over the cap
is refused with 413 before any file is created. The declared length is never taken as the size; chunked requests (no
`Content-Length`) are supported and counted the same way. No other size limit was added, and the cap applies to saves only
(documents of any size can still be opened).

**Signatures.** `EmbeddedSignatureDetector` is unchanged and still decides: it is simply given a stream over the spool file
(PDF `/ByteRange`, OOXML `_xmlsignatures/`, ODF `documentsignatures.xml`); a save that carries one is refused with 409 and nothing
reaches the gate.

**Where and who can read it.** One dedicated directory, `dms.editing.spool-dir` (default
`<java.io.tmpdir>/oak-dms-wopi-spool`). It is created private to the service user (0700 on POSIX) and the service refuses to use
a path that is not a plain directory it owns. Files are `wopi-save-<random uuid>.part`, created exclusively with owner-only
permissions; nothing in a name comes from the request. Nothing serves the directory; document contents and tokens are never
logged. The files hold the document **in plaintext** while a save is processed - the same as ordinary uploads, which Spring's
multipart handling already spools to the temp directory, and the encryption wrapper's temporary copy of an upload. A host's
on-access antivirus scans these files (on the Windows development machine it refused to let the EICAR test string be written
to the spool at all); exclude the spool directory from such scanning only if the gate's own scan is relied on.

**Disk.** The per-save cap does not bound the total, so two safeguards apply to each application instance:

| Setting | Default | Meaning |
|---|---|---|
| `dms.editing.spool-max-active` | 32 | most spool files at once; a further save waits `spool-wait`, then gets **503** |
| `dms.editing.spool-wait` | `PT15S` | how long a save waits for a free slot |
| `dms.editing.spool-min-free-bytes` | 512 MiB | a save is refused with **507** unless the volume keeps this much free after counting what the saves in progress may still write (their declared size, or the whole 200 MiB cap for a chunked request) |

Worst case disk use is `spool-max-active x 200 MiB` (6.4 GiB at the defaults); a typical office save is a few MiB. In a container
the temp directory is the writable layer unless a volume or `tmpfs` is mounted: point `dms.editing.spool-dir` at a volume (or a
`tmpfs` sized for the worst case, remembering a `tmpfs` is memory) and make it writable by the service user (`oakdms`).

**Cleanup and crash recovery.** A file is deleted in a `finally` whatever the outcome (success, refusal, gate failure, a body
that breaks off, an error while writing); a delete that fails is logged (file name only) and left for the sweep. A JVM that dies
cannot clean up, so each live spool file holds an operating-system lock on one byte far beyond its data for as long as it
exists. A scheduled sweep (`dms.editing.spool-sweep-interval`, default `PT15M`, first run after `PT2M`) deletes a file only if it
is a regular file (never a link) named like a spool file, older than `dms.editing.spool-abandoned-after` (default `PT6H`) **and**
that lock can be taken - which proves no save, in this process or in another instance sharing the directory, is using it; a
process that has died has had its locks released by the operating system. Other files in the directory are never touched. There
is no database involvement. Limits: OS file locks are not reliable on every network file system, so a spool directory should be
local to each instance; a file left by a crash stays until the next sweep after the age threshold.

**Transactions.** None is open while the body is copied or checked: the controller is not transactional, the spool is a plain
bean, the gate keeps its own short transactions. Verified against PostgreSQL: with eight saves stalled half way through their
bodies there is no Spring transaction, no `idle in transaction` session and no pooled connection in use.
