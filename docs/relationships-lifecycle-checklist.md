# Relationships: regression checklist for ownership transfer and archive (DRM-FR-009)

Ownership transfer and archiving do not exist yet (decision D8): no feature was added for them. When either is built, DRM-FR-009 ("relationships are preserved on move, ownership transfer, archive and restore") must be verified with this list. Moves, folder changes, renames, new versions, restore from the Recycle Bin and purge are already covered by `RelationshipLifecycleIT`.

Relationships are keyed by `document_id` only. The rule for any new feature: **never change or recreate a document id, and never write to `document_relationships` as a side effect.**

## Ownership transfer

- [ ] Transfer a document that has links as source, as target, and in a bulk batch: every row in `document_relationships` is byte-for-byte unchanged (same ids, `created_by`, `removed_at`).
- [ ] The panel of the transferred document and of each document linked to it shows the same links before and after.
- [ ] `canEdit` / `canRemove` follow the **new** access: the new owner gets what edit access on the source gives; the previous owner loses it if their access went away. Hidden documents stay "A document you can't open" for anyone without access.
- [ ] The "Document linked" notification goes to the **new** owner for links made after the transfer (it reads `documents.owner_user_id`).
- [ ] Browsing by `relatedTo` / `relationshipType` returns the same documents for a user whose access did not change.
- [ ] Audit: no `RELATIONSHIP_*` event is written by the transfer; the transfer has its own event.
- [ ] A transferred document in the Recycle Bin, restored, still shows its links.

## Archive

- [ ] Archive a document that has links: links stay active, panels on both sides still list it, with the document's status shown as the archived state (panel status comes from `documents.status` once nothing in approval).
- [ ] Decide and test whether new links may be created to or from an archived document (today `documents.status` is not consulted; if archived documents become read-only, the link create/edit/remove rules must say so explicitly).
- [ ] Restore (unarchive): links unchanged.
- [ ] Archive then Recycle Bin then purge: purge marks links `target_purged` and writes the audit records exactly as for any document.
- [ ] Browse filters still find archived documents unless archived documents are deliberately excluded from browsing.
- [ ] Bulk archive of many linked documents does not touch link rows and does not hold the per-type advisory lock.

## Both

- [ ] Run `RelationshipApiIT`, `RelationshipLifecycleIT`, `RelationshipPermissionMatrixIT`, `RelationshipFilterIT` and `RelationshipCycleIT` unchanged.
- [ ] Add one IT per feature to `RelationshipLifecycleIT` following the existing tests (create links, perform the action, compare the panel and the rows before and after).
