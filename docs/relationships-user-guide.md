# Document relationships guide (2.8)

How documents are linked to each other in Oak DMS: what a relationship is, who may create one, what happens to links when documents move, are deleted or are purged, and how to administer the types. Design and decisions: `docs/relationship-plan.md`. Audit events: `docs/events/relationship-audit-events.md`.

## TL;DR

- A **relationship** links two *different* documents through a **type** ("Supersedes", "Has attachment", "Duplicate of", …). It is independent of content type and of folders.
- It is stored **once**. The other document sees the **inverse** automatically: if A *Supersedes* B, then B shows *Superseded by* A.
- Open a document: the **Related Documents** panel lists every link, grouped by type, with the other document's title, type, status and a link to open it.
- Create links from the panel (**Add relationship**), from a row in My Documents (**Link to…**), or for several documents at once (select rows, **Link to…**).
- Nothing leaks: a document you can't open appears only as **"A document you can't open"** - no title, type, status or id.

## The ten built-in types

Every tenant starts with these. An administrator can rename them, change their labels and switch them off - never delete them, and their direction and loop rule are fixed.

| Type | Reads from the source | Reads from the target | Direction | Prevents loops |
|---|---|---|---|---|
| Parent → Child | Parent of | Child of | one-way | yes |
| Main Document → Attachment | Has attachment | Attachment of | one-way | |
| Contract → Amendment | Has amendment | Amends | one-way | |
| Supersedes | Supersedes | Superseded by | one-way | yes |
| Replaces | Replaces | Replaced by | one-way | yes |
| Invoice → Supporting Documents | Has supporting document | Supports | one-way | |
| Report → Evidence Files | Has evidence | Evidence for | one-way | |
| Original → Translation | Has translation | Translation of | one-way | |
| Duplicate of | Duplicate of | Has duplicate | one-way | |
| Related Document | Related document | Related document | two-way | |

A **two-way** type has no direction. It is stored once with the lower document id first, so linking A→B and B→A is the same link (the second attempt is refused as a duplicate).

## Who can do what

| Action | Needs |
|---|---|
| See the panel | normal view access to the document (`docm.documents.view`) - no relationship permission |
| Create or edit a link, bulk-link | `drm.manage-document-relationships.create` **and** edit access on the source document **and** view access on the target |
| Remove a link | `drm.manage-document-relationships.remove` **and** edit access on the source document |
| Add / rename / disable / delete types | `drm.configure-relationship-types.create-type` / `.update-type` / `.delete-type` |

Tenant admins hold every permission. A document you can't open is reported as *not found* (404), exactly as if it didn't exist - including as the target of a new link. Another tenant's ids are always 404, never 403.

## The panel

Each row shows the label **from this document's side**, the other document (title, document type, status) and who created the link and when. Status is the latest approval's state ("In approval", "Approved", "Rejected") or, if there has been none, the document's own ("Draft", "Published", …).

| What you see | Meaning |
|---|---|
| **In Recycle Bin** badge, no link | the other document was deleted; the link is kept and comes back in full if it is restored |
| **Disabled type** badge | the type has been switched off; the link stays visible and working, no new ones can use the type |
| 🔒 **A document you can't open** | a link exists, you may not open the other document |
| edit / remove buttons | shown only if you may use them (see the table above) |
| read-only panel | on the workflow page, so approvers can see what a document is linked to |

The Related Documents panel and the **Relationships (n)** link in the document preview are the one-click way to see all direct links.

## Creating links

- **One link** - Add relationship: choose a type, whether this document is the source or target (one-way types), pick the other document, optional note.
- **Several at once** - pick several documents and one other, one type: each becomes its own link, recorded with a shared batch id and one batch audit event. All-or-nothing: if any is invalid nothing is saved and the error names each document and why.
- **Automatically** - *Duplicate of*: when duplicate detection matches a file and you choose **Keep both** (upload, or a bulk import that adds a separate copy), the new document is linked to the original ("Duplicate of" / "Has duplicate"). No relationship permission is needed, but only to an original you can open; if the type is off or the original is gone no link is made and the upload is unaffected.
- **Supersedes on publish is not automatic yet** - there is no publish action. Version pins (`source_version_id`, `target_version_id`) are stored (empty) so it can be added without rework.

Refused: linking a document to itself, a pair already linked with that type, a type that is switched off, a document template, and - for Supersedes, Replaces and Parent → Child - a **loop**. The loop error names the chain ("A → B → C → A"); documents in it you can't open are named "A document you can't open".

## What happens to links

| Event | Links |
|---|---|
| Move to another folder, rename a folder, change folders | unchanged - links belong to the document, not the folder |
| New version, restore an old version | unchanged |
| Moved to the Recycle Bin | kept; shown as "In Recycle Bin"; no new links to or from it |
| Restored from the Recycle Bin | back in full, nothing was changed |
| **Purged** (emptied, or retention ended) | live links to and from it are removed (`removed_reason = target_purged`); the audit trail permanently records what it was related to and how |
| Ownership transfer, archive | not built yet - see `docs/relationships-lifecycle-checklist.md` |

Removing a link removes it from both documents; the row and the audit record stay.

## Finding documents by relationship

The document list accepts `relationshipType` (a type code), `relatedTo` (a document) and `direction`:

`GET /api/v1/documents?relatedTo={policyId}&relationshipType=SUPERSEDES&direction=source` - the documents that supersede the policy.

`direction=source`: the returned documents are the **source** of a link to `relatedTo`; `target`: the ones it points at; `any` (default). A two-way type always means `any`. Results keep all the usual access filtering, and `relatedTo` must be a document you can open. The list page reads the same parameters from its URL, and the panel offers *Find documents that supersede this*.

## Administering types

*Admin → Metadata → Relationship Types.*

- **Create** a custom type: label, inverse label, one-way or two-way, whether it prevents loops.
- **Rename / relabel / reorder / switch off** any type. Switching off never deletes data - existing links stay visible and working; it only blocks new ones.
- **Delete** a custom type only if nothing has ever used it; otherwise disable it. Built-in types can't be deleted.
- A custom type's direction can't change once it has been used; loop prevention can be turned on only if no loop already exists.
- Labels must be unique (ignoring case).

## API summary

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/documents/{id}/relationships` | the panel |
| `POST /api/v1/document-relationships` | create `{relationshipTypeId, sourceDocumentId, targetDocumentId, note?}` |
| `POST /api/v1/document-relationships/bulk` | `{relationshipTypeId, sourceDocumentIds[], targetDocumentIds[], note?}` - one side has one document, max 200 links |
| `PATCH /api/v1/document-relationships/{id}` | change type (same direction) or note |
| `DELETE /api/v1/document-relationships/{id}` | remove |
| `POST /api/v1/document-relationships/counts` | link counts for up to 200 documents (those you can't open are left out) |
| `GET/POST/PUT/DELETE /api/v1/relationship-types` | types (`?includeDisabled=true` needs a configure permission) |

Error codes: `RELATIONSHIP_DUPLICATE`, `RELATIONSHIP_SELF_LINK`, `RELATIONSHIP_TEMPLATE`, `RELATIONSHIP_CYCLE` (`details.chain`), `RELATIONSHIP_CHAIN_TOO_LONG`, `RELATIONSHIP_TYPE_DISABLED`, `RELATIONSHIP_TYPE_DIRECTION_MISMATCH`, `RELATIONSHIP_BULK_INVALID` (`details` = `[{documentId, code, message}]`), `CONCURRENT_UPDATE` (409, retry).

## Notifications

Category `document.relationship-added` (optional tier: in-app on, email off by default). The owner of the document linked **to** is told; the person who linked is not. The text names only the owner's own document, never the other one; an owner who can't open their document gets a text without any title or link. A bulk request sends one notification per document linked to.

## Performance and limits

- The panel is a fixed number of queries however many links there are (links of the document, the documents at the other ends, types, people, workflow state, one access view): ~40 ms with 100 links on a 1,000,000-link tenant.
- Loop checks look at one type at a time, up to 50 hops; a longer chain is refused as `RELATIONSHIP_CHAIN_TOO_LONG`. Writes of a loop-preventing type are serialised per tenant and type, and documents are locked in ascending id order; a wait over 5 s answers 409 `CONCURRENT_UPDATE`.

## Running the large-data test (opt-in)

The 1,000,000-link test is tagged `perf` and excluded from the normal `verify`:

```
cd backend/dms-core
export DB_URL=jdbc:postgresql://localhost:5435/dms_phase2_it DB_USERNAME=postgres DB_PASSWORD=workflow
./mvnw verify -Pperf -Dit.test=RelationshipScaleIT
```

It generates 20,000 documents and a million links in a throwaway tenant (about a minute) and asserts the panel, the filters and a Supersedes create stay under 2 s and use the indexes. Use a disposable database - the data is left behind.
