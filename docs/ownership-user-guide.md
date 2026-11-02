# Document ownership and transfer guide (DOT)

How ownership of a document is recorded in Oak DMS, how it is handed over - one document, many documents, or everything a leaving person owns - and what is kept. Design and decisions: `docs/ownership-plan.md`. Audit events: `docs/events/ownership-audit-events.md`. Legal hold checklist: `docs/ownership-legal-hold-checklist.md`. Requirement mapping: `docs/ownership-traceability.md`.

## TL;DR

- Every document has **one owner** (a person) and, when that person belongs to one, an **owner department**. A document is either **Assigned** or **Holding** (parked with a holding owner until someone takes it over).
- An administrator - or the **head of the owner's department**, for that department's people - can transfer **one document**, a **selection**, or **everything** a user or department owns.
- **Offboarding** a user transfers everything they own and then deactivates the account, in one step.
- The **previous owner is never overwritten**: every change is added to the document's ownership history (previous owner, new owner, who did it, when, why). Nothing else about the document changes - versions, comments, relationships, workflow runs and the audit trail stay exactly as they were.
- Ownership does not by itself decide who can open a document. When someone becomes owner and could not open the document, the dialog can **give them access** (on by default).
- The new owner is told, with **one summary** for a bulk transfer. Nobody is told about a document they cannot open.

## Who can do what

| Action | Needs |
|---|---|
| See who owns a document | `docm.documents.view` and access to the document |
| Transfer one document | `dot.transfer-document-ownership.initiate-transfer` |
| List the documents a user or department owns; the pending list; the offboarding preview | `dot.bulk-transfer-ownership.select-documents` |
| Transfer a selection or everything; assign to a holding owner | `dot.bulk-transfer-ownership.execute-transfer` |
| Offboard a user | `execute-transfer` **and** `otua.manage-users.deactivate` |
| See a document's ownership history | `dot.view-ownership-history.view` and access to the document |
| Export the history (CSV) | `dot.view-ownership-history.export` |
| Set a department's holding owner | `otua.manage-departments.update` |

`dot.transfer-document-ownership.approve-transfer` is seeded but unused: there is no approval step.

**Tenant admins** may act on any document. **Anyone else** with the DOT permissions acts as a **manager**: only on documents owned by people in a department **they head**, and only on documents they can open. A DOT holder who heads no department is refused. Documents a manager cannot open are never named: the list shows only a count ("N more documents only a tenant admin can transfer"), and asking for one answers *not found*. Another tenant's ids are always *not found*, never *forbidden*.

## Transfer one document

Open a document (the **Owner** field shows who owns it and which department) or use **Transfer ownership** on its row in My Documents. Choose the new owner - an active user - and optionally a department (default: the new owner's own), add a note, and confirm.

- **Also give the new owner access** (on by default): if the new owner could not otherwise open the document, they are added as a manager of it. A document that has its own access list just gets one more entry. A document that **follows a restricted folder** is switched to **Restricted** with the folder's current list **copied**, plus the new owner - so nobody who could open it loses access, including the previous owner. The dialog tells you **before you confirm** how many documents will be switched to Restricted and why; the results repeat it.
- A document in the **Recycle Bin** can be transferred. A **permanently deleted** one cannot (it is gone).
- A new owner who lacks the `docm.documents.view` permission is shown as a warning - access to the document cannot give them a permission their role lacks.

## Transfer many

**Admin → Document Ownership → Owned documents.** Pick a user or a department, search, filter (Assigned / Holding / Recycle Bin), tick documents and **Transfer selected**, or **Transfer all**. Templates and Recycle Bin documents are listed and flagged.

- One request moves up to **5,000** documents, all or nothing. Above that the screen **splits the work for you** ("next 5,000") and shows progress; through the API use `limit` (up to 5,000, repeat until nothing is left).
- The **results** show how many moved, how many were skipped and why (already owned by that person, permanently deleted, not found, outside your departments), how many were given access and how many were **switched to Restricted**.
- A reorganisation moves the documents of a **department**; the new owner can be filed under a different department.

## Pending reassignment

**Pending reassignment** lists documents that still need a real owner: those parked in the **Holding** state and those whose owner is a **deactivated or deleted user**. Transfer them like any other; a document leaves Holding as soon as someone takes it over.

## Offboarding a user

On the user's page choose **Offboard user…**. The dialog shows what they own (visible, hidden from you, in the Recycle Bin), who the **holding owner** would be, and what they leave behind in approvals:

- **Open approval tasks** they hold (assigned, claimed, delegated or escalated to them) and **runs they started** that are still in progress, with links. These are **flagged, never reassigned for you**: reassign tasks with the normal *Reassign*; a run's starter cannot change, it can only be cancelled.
- To go on you must tick **"I understand N tasks will stay stuck"**. The account is deactivated only after that, and a reason is required.
- Choose a named **new owner**, or **no successor**: the documents go to the department's **holding owner**, else its **head**, else the **tenant admin**, and are marked Holding. The holding owner and the admins are told.
- If the account cannot be disabled (the identity provider refuses), **nothing is transferred**. Doing it twice at once offboards the user once.
- A manager without the deactivate permission cannot offboard; one who can only moves the documents they may open - the rest stay with the deactivated owner and show up in Pending reassignment.

## Holding owner

Departments have a **Holding owner** field (Admin → Departments). Leave it empty to fall back to the head, then the tenant admin account. The person who is leaving is never chosen.

## History and export

The **Ownership history** panel on a document lists every change, newest first, with names that still resolve for deactivated or permanently deleted users. The history table is **append-only** - the database rejects updates and deletes. **Admin → Document Ownership → History** exports it as CSV (filter by dates, person, document, batch, reason); non-admins get only documents they can open. Cells that look like spreadsheet formulas are neutralised.

## Find by owner

The Owner filter in the document browser (and `GET /api/v1/documents?ownerId=…&ownerDepartmentId=…`) lists documents owned by a person and/or department - only ones you can open.

## Notifications

| Category | Tier | Goes to |
|---|---|---|
| `document.ownership.received` | Standard | the new owner |
| `document.ownership.released` | Optional | the previous owner (if still active) |
| `document.ownership.holding` | Standard | the holding owner and the tenant admins |

A single transfer names the document - only to a recipient who can open it; others get a neutral text. A bulk or offboarding run sends **one summary per recipient** with a count and a link, never one message per document and never a title. The person who did it is not told.

## Legal hold

Nothing in the product blocks a transfer for a legal hold, and the history row is always written. There is no Legal Hold feature yet; see `docs/ownership-legal-hold-checklist.md` for what to verify when it arrives.
