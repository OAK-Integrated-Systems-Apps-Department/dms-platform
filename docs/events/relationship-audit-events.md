# Relationship audit events (2.8 Document Relationships)

Every create, change and removal of a relationship, and every change to a relationship type, writes one row to `audit_events` in the same transaction as the change, so the audit trail is the record of what was related to what and how - including after a document is permanently deleted. Retained per the audit retention policy.

Registered in `AuditActionCatalog` (module `DRM`) and emitted through `AuditService.event(...)`.

## Common fields

| Column | Value |
|---|---|
| `module` | `DRM` |
| `object_type` | `DOCUMENT_RELATIONSHIP` (link events) or `RELATIONSHIP_TYPE` (type events) |
| `object_id` | The relationship id; the batch id for `RELATIONSHIP_BATCH_CREATED`; the type id for type events |
| `resource_name` | The type's label (links), `"<label> xN"` for a batch, the type label (types) |
| `actor_*` | The acting user. For the automatic "Duplicate of" the uploader; for a purge triggered by the retention sweep, none (system) |
| `tenant_id` | Always set explicitly |
| `reason` | Only `RELATIONSHIP_REMOVED_TARGET_PURGED`: `Document "<title>" was permanently deleted` |
| `previous_value` / `new_value` | JSON, shapes below |

Link `new_value`:

```json
{
  "relationshipId": "…", "typeId": "…", "typeCode": "SUPERSEDES", "typeLabel": "Supersedes", "inverseLabel": "Superseded by",
  "sourceDocumentId": "…", "sourceTitle": "Policy v2", "targetDocumentId": "…", "targetTitle": "Policy v1",
  "origin": "manual", "batchId": null, "note": null
}
```

Titles are as of the moment of the event. A symmetric type is recorded in stored order (lower id first).

## Events

| Action | Object | Severity | When | Fields |
|---|---|---|---|---|
| `RELATIONSHIP_CREATED` | relationship | LOW | a link is created (single, one per row of a bulk request, or automatic) | link `new_value`; `origin` is `manual`, `bulk` or `duplicate_detection`; `batchId` for bulk / bulk-import links |
| `RELATIONSHIP_UPDATED` | relationship | LOW | type or note changed | `previous_value` and `new_value` in the link shape |
| `RELATIONSHIP_REMOVED` | relationship | MEDIUM | a user removed it | `previous_value` in the link shape |
| `RELATIONSHIP_BATCH_CREATED` | batch (batch id) | LOW | after a bulk request | `batchId, typeCode, typeLabel, anchorDocumentId, anchorRole (source/target), count, relationshipIds[]` |
| `RELATIONSHIP_REMOVED_TARGET_PURGED` | relationship | MEDIUM | a document in the link was permanently deleted (emptied from the Recycle Bin or retention ended); one event per live link | link `new_value` plus `purgedDocumentId`, `purgedDocumentRole` (`source`/`target`), `createdBy`, `createdAt` |
| `RELATIONSHIP_TYPE_CREATED` | type | MEDIUM | custom type created | `code, label, inverseLabel, directionality, preventsCycles, enabled, displayOrder, system` |
| `RELATIONSHIP_TYPE_UPDATED` | type | MEDIUM | label, inverse label, order, direction or loop rule changed | previous and new type shape |
| `RELATIONSHIP_TYPE_ENABLED` / `RELATIONSHIP_TYPE_DISABLED` | type | MEDIUM | enabled flag changed | previous and new type shape |
| `RELATIONSHIP_TYPE_DELETED` | type | MEDIUM | unused custom type deleted | previous type shape |

## Not audited here, on purpose

- Moving, renaming, soft-deleting or restoring a document: links are unchanged, and those actions have their own events (`DOCUMENT_MOVED`, `DOCUMENT_DELETED`, `DOCUMENT_RESTORED`).
- Reads (opening the panel, filtering).
- The notification sent to the owner (see the notification audit events).
