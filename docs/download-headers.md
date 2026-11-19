# File download headers - `Content-Disposition` (DOC-15)

Every endpoint that streams a stored file builds its `Content-Disposition` through one class,
`com.oak.dms.document.ContentDispositionHeader`. Nothing builds the header by hand any more.

| Endpoint | Disposition type chosen by |
|---|---|
| `GET /api/v1/documents/{id}/content` and `.../versions/{versionId}/content` | `ContentServingPolicy.dispositionType(inline, contentType)` |
| `GET /api/v1/documents/{id}/preview.pdf` and `.../versions/{versionId}/preview.pdf` | always `inline` (a PDF) |
| `GET /api/v1/workflow/instances/{id}/supporting-documents/{sid}/content` | `ContentServingPolicy.dispositionType(true, contentType)` |
| `GET /api/v1/watermark-jobs/{jobId}/result` | always `attachment` |

Already correct and unchanged: the audit-log and export downloads (`AuditEventController`, `ExportJobController`, Spring's
`ContentDisposition` with UTF-8); fixed server-chosen ASCII names (bulk-import templates, ownership history); the external-sharing
download (`SharedFileResponse`, a separate piece of work).

## Why there is a utility

Tomcat silently **drops** a response header whose value contains a character above U+00FF - every Sinhala and Tamil letter, and
emoji. Before DOC-15 a header built as `filename="<name>"` therefore reached the browser as no header at all: no file name and,
for types that must not be shown in the page, no `attachment`. (Spring's own `ContentDisposition` writes `filename*` but leaves
`%` and `;` in its ASCII fallback, which the web client's parser mis-reads, so it is not used.)

## What is sent

```
attachment; filename="document.pdf"; filename*=UTF-8''%E0%B6%9C%E0%B7%9C%E0%B6%B1%E0%B7%94%E0%B7%80.pdf
```

* `filename` - ASCII fallback for clients that ignore `filename*`: letters, digits and ` . _ - ( ) [ ] + , & @ # ! ~` are kept;
  every run of anything else becomes one `_`; it never holds `% ; " \` or a control character; if nothing readable is left of the
  base name it is `document`, and the extension is kept (`document.pdf`).
* `filename*` (RFC 5987/8187) - the real name, UTF-8 percent-encoded. It is sent only when the name is not already plain safe
  ASCII, so `report.pdf` stays `filename="report.pdf"`.
* The whole value is ASCII (nothing for Tomcat to drop) and cannot contain CR, LF or any control character, so a name cannot
  inject a header.

## How the name is normalised (header only; the stored name never changes)

1. The name is untrusted text, not a path: `/` and `\` are separators and only the last segment is kept (`../x.pdf` -> `x.pdf`).
2. Removed: control characters (C0, DEL, C1), line/paragraph separators, unpaired surrogates, and invisible format characters
   (bidirectional overrides, zero-width space, word joiner, byte-order mark, soft hyphen, tag characters, ...).
   **Kept:** the zero-width joiner and non-joiner (U+200D/U+200C), which Sinhala, Tamil and emoji sequences need to be spelled.
3. Quotes, semicolons and percent signs are valid name content: they are kept and percent-encoded in `filename*`.
4. Spaces and leading/trailing joiners are trimmed; a name that is empty, `.` or `..` becomes `document`; `.pdf` alone becomes
   `document.pdf` (no extension is ever invented).
5. At most **255 UTF-8 bytes** (the usual file-system limit; at most about 765 header characters when encoded), cut between code
   points, keeping an extension of up to 20 bytes.

## What the utility does not do

It never decides inline versus attachment. The caller passes the disposition it already chose; the DOC-04 rules stay in
`ContentServingPolicy` (only PDF and raster images may be inline; XML, JSON, SVG, text and everything else are `attachment`),
and the `Content-Type`, `X-Content-Type-Options: nosniff` and sandbox `Content-Security-Policy` headers are not touched.

## Web client compatibility and the remaining limitation

The web client (`frontend/lib/documents/api.ts` twice, `preview/api.ts`, `watermarks/api.ts`) reads the first
`filename=`/`filename*=` match with `/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i` and calls `decodeURIComponent` on it. That first match is
the ASCII fallback, which is built so that this cannot throw, truncate or decode anything (no `%`, `;`, `"` or `\`). The effect:
the UI saves a Sinhala or Tamil file as `document.pdf` (with its extension) instead of the real name; names with `%`, `;` or a quote
download with `_` in their place. The real name is available to any client that reads `filename*` (browsers following the
standard, API clients). **Showing the real Unicode name in the UI needs a small frontend change** (prefer `filename*`, decode it
without throwing) - tracked as a follow-up, not part of this backend change.

## Not verified

Real-browser behaviour (how each browser names the file, and inline rendering of the types that are not allowed inline);
a reverse proxy that rewrites or rejects headers (nginx passes upstream headers as they are, but this was not tested); the
database-backed endpoint integration tests (the environment was unavailable when this was written).

## Tests

`ContentDispositionHeaderTest` (table of international and hostile names; shape, ASCII, encoding, round trip, bounds),
`DownloadHeadersWireTest` (the real `DocumentController`, `WorkflowInstanceController` and `WatermarkController` served by a real
embedded Tomcat, raw response bytes: header present once, ASCII, right disposition, body intact; DOC-04 matrix of XML, JSON, SVG,
text, HTML, PDF, PNG and JPEG with Sinhala, Tamil, emoji and hostile names).
