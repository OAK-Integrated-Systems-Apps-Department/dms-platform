# Document search guide (DSR)

How to find documents in Oak DMS: what search covers, how to write a query, what the filters do, how results are shown and previewed, how to keep your own saved searches, and what an administrator looks after. Design and decisions: `docs/search-plan.md`. Audit events: `docs/events/search-audit-events.md`. Requirement mapping: `docs/search-traceability.md`. Running and securing the search engine: `docs/search-operations.md`.

## TL;DR

- Open **Search** in the sidebar (or press **Ctrl/Cmd+Enter** in the top-bar box). Type words; results appear as you stop typing and the address bar always holds your search, so you can share the link.
- Search looks in **titles, file names, metadata values and the text inside documents** (PDFs, text files, scanned pages that have been read by OCR). Titles and file names rank above metadata, which ranks above body text.
- You only ever get documents **you are allowed to open**. A document you cannot open is simply not there: not in the results, the counts, the snippets or the suggestions.
- **Saved presets** keep a keyword and filters under a name: one click to open the list, one more to run it. They are private to you.
- A result can be **previewed in a side panel** without leaving the page, under the same print, download and copy rules as everywhere else.
- If the search engine is unavailable, search keeps working as a **title search** and says so.

## Who can do what

| Action | Needs |
|---|---|
| Open Search, search, filter, preview results | `dsr.search-documents` (granted to every role that can already view documents) |
| Include **inactive** or **archived** documents | `dsr.search-documents` **and** `crm.view-archive` (without it the option is not offered; asking anyway is refused, never silently widened) |
| See picture thumbnails of results | also `docm.documents.view` (without it you see the file-type icon) |
| Pin or bookmark a result | `pid.pin-unpin-documents.pin` / `bd.bookmark-unbookmark-documents.bookmark`, as everywhere |
| Save, rename, update a saved preset, replace one by name | `dsr.manage-saved-search-presets.create`, `.update` (replace needs both) |
| Delete a saved preset | `dsr.manage-saved-search-presets.delete` |
| Use (list, run) your saved presets | `dsr.search-documents` only |
| Share a saved preset | not possible: the `.share` permission exists but nothing uses it; saved presets are private |
| Search Index admin screen: status, reindex, check now, saved-search limit | tenant administrators |

Tenant administrators hold every permission. Another organisation's documents and saved searches are never reachable: the answer is *not found*, exactly as for something that does not exist.

## Writing a query

| You type | It finds |
|---|---|
| `invoice acme` | documents containing **both** words (anywhere: title, file name, metadata, text) |
| `invoice \| receipt` | documents containing **either** word |
| `"payment terms"` | the exact phrase |
| `contr*` | words that start with `contr` (the **last** word you type also matches as a prefix while you are still typing it) |
| `(invoice \| receipt) acme` | grouping |
| `Invoice_2024.final.pdf` | file names are split at `_` and `.`, so `invoice 2024` finds it too |

Search is not case- or accent-sensitive (`Café` finds `cafe`) and handles Sinhala and Tamil text. English words are matched by stem: `contracts` finds `contract`. An empty keyword box with filters (or nothing at all) lists documents newest-modified first.

## Filters

All filters combine with the keywords and with each other (AND).

- **Document type, department, owner**: pick from the lists (names only, from your own organisation).
- **Created / modified**: a from and a to date.
- **Status**: *Active* (default). *Inactive* and *Archived* need the archive permission.
- **Folder**, with **Include subfolders**.
- **Metadata**: pick a searchable metadata field and a value. Text fields use *equals* or *contains*; number and date fields also have *at least* / *at most*.

If a filter value no longer exists (a department was deleted, a metadata field was switched off) the rest of your search still runs, and a notice names what was left out.

**Sort**: *Relevance* (default), *Title*, *Created*, *Modified*, ascending or descending.

## Reading the results

- Each result shows the title, file name, type, owner, dates, status, a **Legal hold** mark where it applies, and **snippets** with your words highlighted (from the title, the file name and the text).
- The count reads **"12"** when every possible match was checked and **"12 or more"** when it was not. It never includes documents you cannot open.
- **Previous / Next** step through pages; the link you copy keeps your place.
- A short line says new or changed documents can take about a minute to appear. Titles and metadata normally appear within seconds.
- *There are a lot of possible matches*: the search stopped looking early. Add a keyword or a filter.
- *No results*: the page suggests checking spelling, removing filters or broadening the words, and may offer **Did you mean**, built only from documents you can see.
- *Full search isn't available right now, so only document titles were searched*: the engine is down or still being built for your organisation. Titles, type, owner, department, folder and active/archive still work; text, metadata, dates and subfolders are named as not applied.

## Preview, thumbnails, pins and bookmarks

- A picture **thumbnail** replaces the file-type icon when the document has one.
- **Preview** opens the document in a panel beside the results (the address gets `?preview=<id>`, so Back closes it and your page of results stays put). **Open document** goes to the full page.
- The panel obeys your organisation's **preview policy**: print, download and copy may be allowed, watermarked or blocked. When a file type cannot be shown, or rendering fails or takes too long, the panel offers a **download** instead (unless downloads are blocked, which it says).
- **Pinned** and **bookmarked** documents are marked in the results, and the pin and bookmark buttons work in place. The marks are yours: someone else's bookmark never shows on your results.

## Saved presets

Next to the keyword box, **Saved presets** lists yours; click one to run it: the keyword and filters fill in and the results appear.

| You want to | Do this |
|---|---|
| Save what you have | **Save as preset**, give it a name (up to 100 characters). It needs a keyword or at least one filter; sorting alone does not count |
| Use an existing name | the name is yours once, ignoring capitals and spaces. You are asked whether to **replace** this preset's criteria |
| Rename, update or remove | **Manage**: rename in place, **Update to current criteria**, or delete (with confirmation) |
| Know why it did not run as before | a notice names filters that no longer exist and were dropped; the saved preset itself is left as it was |

Limits: **50 saved presets per person** by default; an administrator can change the figure for the organisation. At the limit you are told to delete one first (replacing one by name is still allowed). Lowering the limit never deletes anyone's presets.

A saved preset holds only the criteria (the keyword and filter values you chose). It never holds documents, titles or results, and nothing about it is written to logs; the audit trail records that you created, changed or deleted one, naming the criteria used but not their values.

## How to enter metadata today

Metadata search and metadata filters only find documents whose fields have values. At the moment:

- A normal **My Documents upload has no metadata fields.**
- Values are entered on **Scan & OCR → Scan documents** (`/documents/scan`; PDF and image files only): fill the fields and press **Save fields**.
- Or by **bulk import**, which carries metadata with the files.

This is a known product gap outside the search work. A document without values is still found by its title, file name and text.

## Administering search

**Administration → Search Index** (tenant administrators):

- **Engine** state, whether search is on for your organisation, whether the index is ready, the backfill state, the current generation, jobs **waiting** and **failed**, and the **lag** (how old the oldest waiting change is; 0 when everything is indexed).
- **Run check now** (or **Run now** the first time) compares the database with the index and repairs gaps (the same check also runs every night at 03:30); the page then shows the **last check** and what it found (missing and orphan documents). One check at a time, and not more than one every 5 minutes.
- **Reindex** re-reads every document of your organisation. Search keeps working meanwhile and results update gradually. Confirmation required; one at a time and not more than one every 5 minutes.
- A hint appears when **searchable metadata fields changed** since the last reindex: switching a field's *searchable* flag or its type only affects documents indexed afterwards, so run a reindex to apply it to existing documents.
- **Saved presets per person**: the organisation's limit.
- The system health dashboard lists **Search (OpenSearch)** among the integrations.

## API summary

All paths under `/api/v1`. JSON. Errors use the standard body `{code, message, details}`.

| Method and path | Needs | Notes |
|---|---|---|
| `GET /search` | `dsr.search-documents` | `q, type, department, owner, createdFrom/To, modifiedFrom/To, status, folderId, includeSubfolders, meta=fieldId:op:value, sort, size (≤50), cursor`. Items carry `thumbnailUrl, versionNumber, sizeBytes, pinned, bookmarked, highlights`. `mode` is `search` or `basic` |
| `GET /search/suggest?q=` | `dsr.search-documents` | up to 8 verified titles |
| `GET /search/filters` | `dsr.search-documents` | types, departments, owners, searchable metadata fields (names only) |
| `GET /search/presets` | `dsr.search-documents` | `{items, count, limit}` |
| `GET /search/presets/{id}` | `dsr.search-documents` | 404 unless it is yours |
| `POST /search/presets` | `…presets.create` | `{name, criteria, overwrite?}` → 201 |
| `PUT /search/presets/{id}` | `…presets.update` | `{name?, criteria?}` |
| `DELETE /search/presets/{id}` | `…presets.delete` | 204 |
| `POST /search/presets/{id}/run` | `dsr.search-documents` | `{size?}` → `{preset, criteria, warnings, result}` |
| `POST /search/telemetry/preview` | `dsr.search-documents` | `{reason}` (`unsupported`, `failed`, `timeout`, `policy_blocked`) → 204; about 30 a minute per person |
| `GET /admin/search/status` | tenant admin | engine, readiness, queue, lag, last check and gap, `reindexRecommended`, `reindexRunning`, `reconcileRunning` |
| `POST /admin/search/reindex` | tenant admin | 202 `{generation}` |
| `POST /admin/search/reconcile` | tenant admin | 202 |
| `GET` / `PUT /admin/search/presets/limit` | tenant admin | `{maxPerUser, defaultMax}`; 1 to 500 |

Error codes:

| Code | Status | Meaning |
|---|---|---|
| `PRESET_NAME_EXISTS` | 409 | you already have that name; `details.presetId` is the existing one; repeat with `overwrite: true` to replace it |
| `PRESET_LIMIT_REACHED` | 409 | at your limit; `details.limit` |
| `PRESET_NO_CRITERIA` | 400 | nothing to search for |
| `PRESET_INVALID` | 400 | unknown criterion, bad value, bad name, or a filter that does not exist |
| `PRESET_VERSION_UNSUPPORTED` | 409 | saved by a newer version; listed but cannot be run |
| `PRESET_LIMIT_INVALID` | 400 | limit outside 1 to 500 |
| `REINDEX_IN_PROGRESS` / `RECONCILE_IN_PROGRESS` | 409 | one is already running |
| `REINDEX_RATE_LIMITED` / `RECONCILE_RATE_LIMITED` | 429 | too soon after the last; `details.retryAfterSeconds` |
| `TELEMETRY_INVALID` / `TELEMETRY_RATE_LIMITED` | 400 / 429 | outside the list / over about 30 a minute |
| (no code) | 403 | missing permission; asking for inactive or archived without `crm.view-archive` is refused for that filter only |
| (no code) | 404 | unknown, someone else's, or another organisation's |
| (no code) | 400 | a cursor that was tampered with, or that belongs to another person or another search |

## Performance and limits

Measured on a development machine (one OpenSearch node, 1 shard, 512 MB heap) and recorded in `docs/search-plan.md`:

- Searching 500,000 documents: p95 **23 to 80 ms** for a person who sees everything; p95 **226 to 249 ms** for a person who sees only 5% (the expensive case, because most candidates are dropped by the permission check).
- Results are capped: at most 6 rounds and 2,000 candidates are examined per page; then the page says to refine.
- Page size 20 by default, 50 at most.
- Indexing keeps up at about 800 to 1,300 documents a second on one dms-core instance (real path, stubbed text; see `docs/search-operations.md` for the full figures and how to repeat them).

## Opt-in large-data tests

Never part of a normal run. `SearchScaleIT` (search latency at 500,000 documents) and `SearchRealPathBenchmarkIT` (the real indexing path at 100,000) each need an explicit switch (`-Dsearch.scale.run=true` / `-Dsearch.realpath.run=true`), a disposable PostgreSQL and OpenSearch, and at least 15 GB free on the Docker disk and 5 GB on `/`. Commands and results: `docs/search-operations.md`.
