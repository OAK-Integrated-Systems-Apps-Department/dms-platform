# Records retention & compliance (6.1, CRM)

How a document's lifecycle is governed: switching it Active/Inactive by hand, retention rules that archive it automatically,
and legal hold, which stops all of it. Everything about *how long* and *when* is data an administrator edits - nothing is
hard-coded to a regulation.

## The lifecycle

`documents.lifecycle_state` (V130) is separate from `documents.status` (the approval/workflow status):

| State | Meaning | Listed in normal views / search |
|---|---|---|
| `active` | the normal state | yes |
| `inactive` | switched off by hand (CRM-FR-002) | no |
| `archived` | moved there by its retention rule (CRM-FR-007) | no |

Inactive and archived documents stay fully reachable (by link, and through **Inactive & Archive** for people holding
`crm.view-archive`). One filter does the hiding: `DocumentRepository.LIVE_TITLED` takes the states to show, so every list
(folders, "all documents", related, shared-with-me) is consistent and `GET /api/v1/documents?view=archive` is the same query
with the other states. Title search is that same list, so search is hidden the same way.

## Manual status (CRM-FR-001..005, 014)

`PUT /api/v1/documents/{id}/status` `{status: active|inactive, reason?}`. Marking inactive needs
`crm.change-document-status.mark-inactive`, marking active `...mark-active`, plus edit access to the document. An archived
document can be brought back to Active but not set Inactive. A **reason is optional** unless the organisation requires one
(Settings). Every change is one conditional UPDATE and is audited (`DOCUMENT_STATUS_CHANGED`) with who, when and why.

Bringing a document back **starts a fresh active period** (`reactivated_at`), so a reinstated document is not archived again
at once.

## Retention rules (CRM-FR-006, 008, 010, 011)

`retention_policies`: one rule per **scope** - the whole organisation (the default), a **document type**, a **department**
(the owner's), or a **folder** (optionally including subfolders). Each rule has:

* an **active period** and an **archive period**, each an amount of days, months or years (CRM-FR-008: e.g. 1 year active +
  6 years archived; archive 0 = review as soon as it archives);
* where the clock **starts**: the document's creation, or its last change;
* a free-text **regulation label** (GDPR, HMRC, Companies Act 2006, or anything) - the label is shown and audited; the
  periods are the administrator's to set and to check against their obligations. The product ships **no legal periods**;
* a notes field and an on/off switch.

Rules for different scopes run side by side. Manage them under **Policies > Records Retention** (needs
`crm.manage-retention-policies.*`). Every create/change/delete is audited.

### When several rules cover one document

Setting `precedence` (per organisation):

* `longest` (default) - the rule that keeps the document longest wins; a tie goes to the more specific rule;
* `most_specific` - folder (the nearer folder first), then document type, then department, then the organisation default.

### What a rule's dates mean

`archive due = clock start + active period`; `review due = archive due + archive period`. The clock start is the later of the
rule's clock (creation / last change) and the last manual reinstatement. An administrator's extension can only push the review
date later.

## The automatic job (CRM-FR-007, 012, 015, CRM-NFR-002, 003)

`RetentionJob` runs hourly (`dms.retention.interval`, default `PT1H`; first run after `PT3M`), one server at a time (a Postgres
advisory lock). For each organisation that has **at least one enabled rule** and has not switched automation off, it walks the
live documents and for each one:

1. skips it entirely if it is under **legal hold** (CRM-FR-015);
2. finds the winning rule; if none, flags it "no rule" (or ignores it, per setting);
3. archives it if its active period has elapsed (`active`/`inactive` -> `archived`);
4. flags it **for administrator review** if its total retention has elapsed. **It never deletes.**

Every change is a conditional UPDATE (`changeLifecycle`, `flagForReview`, ...), so a second run does nothing, two servers cannot
both act, and a legal hold placed at the same moment as an archive **always wins** (CRM-NFR-003). A document that fails is
logged and retried next run. An organisation with no rules is left completely alone.

**Apply rules now** (`POST /api/v1/retention/run`, `crm.configure-retention`) runs the same pass for your organisation on demand.
The document page always computes the rule and dates live, so a brand-new document shows "archives on ..." before the next run.

## Legal hold (CRM-FR-013..017)

Placing/lifting a hold is the check-out module's (`DocumentCheckoutController`, V127). Here it means:

* the **status cannot be changed either way** (409 `DOCUMENT_ON_LEGAL_HOLD`, naming the hold and its reason) - CRM-FR-014;
* the job **skips** the document, and the document page says archiving is paused - CRM-FR-015;
* it **cannot be deleted** by anyone, administrators included (the delete path and the recycle-bin purge both refuse) - CRM-FR-016;
* lifting needs `crm.lift-legal-hold`, and then **whatever fell due during the hold is applied at once**
  (`RetentionEngine.catchUp`), not at the next scheduled run.

## After total retention: review, never auto-delete (CRM-FR-012 and the alternate flows)

A flagged document appears under **Records Retention > Awaiting review** (`crm.review-retention`). From its page an administrator can:

* **Keep longer** - extend by N days/months/years with a mandatory reason; the flag clears and the review date moves out;
* **Send to the recycle bin** - with a mandatory reason; it then follows the recycle bin's own retention. Refused while the
  document is under legal hold, in an active approval, or linked to other documents (409 `DOCUMENT_REFERENCED`).

Documents no rule covers appear under **Without a rule**; add a rule (an organisation-wide one covers everything) and apply the
rules - the flag clears.

## Settings (per organisation, `tenant_retention_settings`; no row = defaults)

| Setting | Default | Meaning |
|---|---|---|
| `automationEnabled` | on | the scheduled job runs for this organisation (a paused organisation can still use "Apply rules now") |
| `precedence` | `longest` | which rule wins when several cover a document |
| `whenNoPolicy` | `flag` | flag a document no rule covers for an administrator, or `ignore` it |
| `requireStatusReason` | off | a reason is mandatory for a manual status change |

Changing any is audited (`RETENTION_SETTINGS_CHANGED`) and needs `crm.configure-retention`.

## Permissions

V10 already had `crm.change-document-status.mark-active/mark-inactive`, `crm.manage-retention-policies.create/update/delete`,
`crm.place-legal-hold`, `crm.lift-legal-hold`. V130 adds `crm.view-archive`, `crm.manage-retention-policies.view`,
`crm.review-retention`, `crm.configure-retention`. As with every permission, tenant administrators hold them implicitly; other
roles get them from the Roles screen.

## Audit (CRM-FR-018, CRM-NFR-005)

`DOCUMENT_STATUS_CHANGED`, `DOCUMENT_AUTO_ARCHIVED`, `DOCUMENT_RETENTION_FLAGGED`, `DOCUMENT_RETENTION_EXTENDED`,
`DOCUMENT_RETENTION_DISPOSED`, `DOCUMENT_NO_RETENTION_POLICY`, `RETENTION_POLICY_CREATED/UPDATED/DELETED`,
`RETENTION_SETTINGS_CHANGED`, `RETENTION_RUN_REQUESTED`, plus the existing `DOCUMENT_LEGAL_HOLD_PLACED/LIFTED`. Automatic changes are
attributed to the system and record the rule, regulation label and dates that caused them.

## Old versions (version retention)

The same rules limit a document's **old versions** (V131). A rule can set either, both or neither of:

* **keep at most N versions** of a document;
* **remove superseded versions older than** an amount of days, months or years.

A version outside **either** limit is removed - in two steps, like the recycle bin, so nothing irreversible happens at once:

1. **Remove** - hidden from the version history (`is_deleted`, `retention_removed_at`). Its file is untouched and an administrator
   can restore it (**Records retention > Removed versions**, `POST /api/v1/retention/versions/{id}/restore`).
2. **Destroy** - once the organisation's **grace period** has passed (setting `versionGraceDays`, default 30, 0 = at the next run),
   its file, preview and OCR text are destroyed for good (`purged_at`) by the same code the recycle bin uses
   (`RecycleBinService.destroyFiles`). A failure leaves it hidden and retried - never half-destroyed.

**Never removed or destroyed:** the current, latest or published version; a protected version (`dvc.protect-versions`); a signed
version or the one a signature was placed on; a version in an approval or used as a supporting document; one a relationship
points at; one an administrator chose to keep (restoring a version sets `retention_keep`, so the next run does not remove it
again); a version still being scanned; and **anything on a document under legal hold**. Such versions still count towards N, so a
document can keep more than N when some are in use - and it always keeps its current version.

The rule that applies is the document's winning rule (the same precedence as everything else). Setting or changing the limits
needs the DVC permissions `dvc.configure-version-retention-rules.set-max-versions` (the maximum) and `...set-retention-period`
(the age), on top of the permission to edit the rule. Audited: `DOCUMENT_VERSIONS_REMOVED`, `RETENTION_VERSION_RESTORED`,
`DOCUMENT_VERSIONS_DESTROYED` (one event per document per run, listing the version numbers).

## Notifications

A retention run tells the people concerned, through the notification system (in-app always; email by each category's default and
each person's own preferences). One notification per kind **per run**, never one per document:

| Category | Who | When |
|---|---|---|
| `retention.review.due` | people with `crm.review-retention`, and tenant administrators | documents newly flagged because their total retention ended (flagged once - not repeated every run) |
| `retention.no-rule` | the same | documents no rule covers were found (email off by default) |
| `retention.archived.owner` | each document owner | their documents were archived by a rule (optional; email off by default) |

## Dashboard and Pinned

* **Dashboard:** every count of documents (the total, added per day, by type, by format, per folder, most opened) counts **Active**
  documents only, so it matches what the document browser lists. **Storage is the exception** - an inactive or archived document's
  files still take their space, so storage and version figures include them.
* **Folder tiles:** the number on each folder counts **Active** documents only, so it matches what opening the folder lists. A folder
  that holds only inactive or archived documents shows as empty but **cannot be deleted** - the refusal says it holds "N inactive or
  archived documents" and where to find them (Inactive & Archive).
* **Search and pickers:** the top-bar search, the document pickers and the home "recent documents" all use the normal listing, so they
  leave inactive and archived documents out as well.
* **Pinned:** a pinned document that becomes inactive or archived **stays on the list**, flagged (the existing PID rule: a pin never
  just vanishes), and its row carries the Inactive/Archived badge. The Pinned count line says "inactive or archived".

## Rolling back

`db/rollback/V130__create_records_retention.rollback.sql` (not run by Flyway) drops the rules, settings and lifecycle columns:
documents that were Inactive/Archived return to normal listings as active documents. Legal holds are untouched.

## FOLLOW-UP (known gaps, deliberately not fixed here)

* **The job walks every live document each run** (paged, indexed by tenant). Fine for the sizes seen so far; a large tenant would
  want a "due soon" index.
* **Right-to-erasure / data-subject requests (GDPR)** are not modelled; a retention rule labelled GDPR is only a label.
* **Bulk import/export** ignore the lifecycle (imported documents start Active; export includes whatever is selected).
