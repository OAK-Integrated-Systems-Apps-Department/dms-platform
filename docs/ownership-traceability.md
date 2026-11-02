# Ownership and transfer: traceability

Where each requirement of the Document Ownership and Transfer module (registry section 1.6) is built and tested.

| Requirement | Built in | Tested by |
|---|---|---|
| DOT-FR-001 designated owner (person and/or department) on every document | V123 (`owner_department_id`, `ownership_state`), V124 backfill, `DocumentVersionPromoter`, owner fields in the document API, Owner field on the document page | `V123ToV125OwnershipMigrationTest`, `OwnershipSchemaIT`, `OwnershipFilterIT` |
| DOT-FR-002 an admin transfers one document | `OwnershipTransferService`, `POST /documents/{id}/ownership/transfer`, transfer dialog | `OwnershipTransferIT` |
| DOT-FR-003 bulk transfer for a departing employee or reorganised department | `OwnershipBatchService`, `OffboardingService`, `POST /ownership/transfers`, Offboard dialog | `OwnershipBulkIT`, `OwnershipOffboardingIT`, `OwnershipPerformanceIT`, `OwnershipScaleIT` (opt-in) |
| DOT-FR-004 prior owner preserved in the history, never overwritten | append-only `ownership_transfers` (trigger + REVOKE), `OwnershipHistoryService`, history panel | `V123ToV125OwnershipMigrationTest`, `OwnershipHistoryIT` |
| DOT-FR-005 the owner's manager or an admin reviews the owned documents and reassigns selectively | `GET /ownership/documents`, selective transfer, manager scope (`OwnershipScope`), Owned documents tab | `OwnershipBulkIT`, `OwnershipSecurityIT` |
| DOT-FR-006 the new owner is notified | `OwnershipNotificationListener`, categories (V125) | `OwnershipNotificationIT` |
| DOT-NFR-001 every change logged with prior owner, new owner, actor, timestamp | `ownership_transfers`, audit events (`DOT`) | `OwnershipTransferIT`, `OwnershipHistoryIT` |
| DOT-NFR-002 a bulk transfer needs no per-document effort | "all" mode, chunked set-based updates, automatic "next 5,000", offboarding | `OwnershipBulkIT`, `OwnershipPerformanceIT` |
| DOT-NFR-003 owner-tied permissions re-evaluated after a transfer | no permission derives from ownership; the access cache is invalidated by the V61 triggers; `OwnershipAccessService` gives the new owner access (Restricted conversion with copied entries) | `OwnershipTransferIT` (restricted folder), `OwnershipBulkIT` (preview/convert) |
| DOT-NFR-004 filter and search documents by current owner | `ownerId` / `ownerDepartmentId` on the browse endpoint and the owned-documents list; Owner filter | `OwnershipFilterIT`, `OwnershipBulkIT` |
| OTUA-FR-006 offboarding consistent with ownership transfer | `OffboardingService` (transfer, then `UserService.deactivateUser`, one transaction) | `OwnershipOffboardingIT` |
| Alternate flow (a): pending approvals flagged for manual reassignment | `OpenWorkItems`, confirmation (`OFFBOARD_CONFIRMATION_REQUIRED`) | `OwnershipOffboardingIT` |
| Alternate flow (b): no successor, holding owner | `HoldingOwnerResolver`, `ownership_state = 'holding'`, Pending list | `OwnershipHoldingIT`, `OwnershipOffboardingIT` |
| Alternate flow (c): legal hold | nothing blocks a transfer; history always written; checklist | `docs/ownership-legal-hold-checklist.md` |
| Workflow: a single document can be reassigned outside offboarding | transfer dialog on the document page and row menu | `OwnershipTransferIT` |
| Relationships, versions, comments, workflow runs, audit records untouched (D12) | the transfer writes only owner columns, the history and access entries | `OwnershipTransferIT` (row snapshot), `OwnershipConcurrencyIT` |
| Concurrency (relationship race, double transfer, double offboarding, soft delete and purge races) | `OwnershipLocks` (ascending ids, 5 s timeout), `OwnershipRetry`, `@DynamicUpdate` on `Document` | `OwnershipConcurrencyIT` (run 5×) |
| Permission matrix and tenant isolation | `@PreAuthorize` per endpoint, `OwnershipScope`, 404 for other tenants | `OwnershipSecurityIT` |
