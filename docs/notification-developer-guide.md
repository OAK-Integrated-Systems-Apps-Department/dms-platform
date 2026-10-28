# Notification developer guide (3.2)

How to send a notification from any module, and how to add a new category. The
notification module (`com.oak.dms.notification`) is a shared service: email +
in-app today, behind a channel interface so another channel can be added later.

## TL;DR — send a notification

Inject `NotificationService` and call `notify(...)` **inside your business
transaction** (so the inbox row and the delivery rows commit atomically with the
change that triggered them — decision 3):

```java
notificationService.notify(NotificationRequest.builder()
        .tenantId(tenantId)                     // events run without TenantContext — pass it
        .recipientUserIds(List.of(userId))      // one in-app row per recipient
        .categoryCode("document.shared")        // must exist in notification_categories
        .title("A folder was shared with you")  // also the email subject — put the key facts here
        .body("Dilani shared \"Q3 plan\" with you.")
        .linkUrl("/documents/" + documentId)    // relative to app.frontend-base-url, or absolute
        .objectType("document").objectId(documentId)
        .actorUserId(actorId)
        .sourceModule("documents")              // defaults to the category's module if null
        .dedupeKey("doc-shared:" + grantId)     // idempotent per (tenant, recipient, dedupeKey)
        .build());
```

Rules the service applies for you:
- **In-app is always created** and shown in the bell inbox — even if email is off or fails.
- **Email** is created `pending` only when the recipient's resolved preference allows it
  (critical → always; standard → on unless turned off; optional → off unless turned on) **and**
  they have an email address. Otherwise a `skipped` email delivery records why
  (`email_disabled` / `no_email`); no-email also flags the admin.
- **Delivery** happens out-of-band: the dispatcher claims `pending` rows (SKIP LOCKED + lease),
  sends via the channel, retries with backoff up to `max_attempts`, and on final failure marks
  the delivery `failed` (audited) while the in-app entry stands. An admin can "retry now".
- Every attempt is written to the immutable `notification_delivery_attempts` log.

### Access-aware content (documents)

If the notification names or links a document, tell the service which one with `documentId` and give it the
version to show a recipient who can't open it. The service checks **once per call, for all recipients**, and a
recipient who can't open the document gets the neutral content instead - and the inbox entry carries no
reference to the document either:

```java
NotificationRequest.builder()
        // ...
        .title("Approval needed: " + title).body("\"" + title + "\" is waiting for you.").linkUrl("/documents/" + id)
        .documentId(id)
        .neutralTitle("Approval needed: a document")     // must not contain the document's title
        .neutralBody("A document is waiting for you.")
        .neutralLinkUrl("/tasks")                        // null = no link; never a /documents/ link
        .build();
```

"Can open" is `DocumentAccessService.forUsers(...).canSee(document)`, which includes the workflow-scoped read the
people an approval is waiting on rely on. Set `.requireViewPermission(true)` to also require `docm.documents.view`
(mentions do - a mentioned person has always needed the permission *and* the access).

Because the check calls Keycloak for tenant admins, **put all the recipients of one event in one `notify()` call**
rather than looping - one lookup instead of one per person. Don't set `documentId` when the content doesn't name the
document, or when being a recipient already implies access (a "shared with you" notice).

### Dedupe keys

`dedupeKey` makes `(tenant, recipient, dedupeKey)` idempotent - the second call for the same key is dropped, silently.
Use it for producers that can run twice for the same fact (a scheduler that may retry). **Leave it null for
event-driven notifications**: the event fires once per transaction, and a repeat (a document resubmitted after a
rejection, a task delegated to the same person twice) is a genuinely new notification. A key built from the document
or the user alone would swallow those.

### Calling it from an existing after-commit listener

The existing workflow/comment/scanning listeners publish Spring events. To keep notifications
in the caller's transaction, the notification listeners use
`@TransactionalEventListener(phase = BEFORE_COMMIT)` (not AFTER_COMMIT) so the write commits
atomically with the business change. Keep that phase when adding new triggers.

## Add a new category

1. **Seed it** in a new migration (next free `V<n>`), following `V103`'s category seed:
   ```sql
   INSERT INTO notification_categories (category_code, module, tier, default_email_enabled, display_name, description)
   VALUES ('reports.schedule.delivered', 'reports', 'optional', false, 'Scheduled report ready', '...')
   ON CONFLICT (category_code) DO NOTHING;
   ```
   Pick the tier deliberately:
   - **critical** — email always on, user/tenant cannot turn it off (security, escalations, "to you" tasks).
   - **standard** — email on by default, user can turn off.
   - **optional** — email off by default, user can turn on.
2. **Call** `notificationService.notify(...)` with the new `categoryCode`. That's it — the inbox,
   preferences page, tenant settings and admin delivery log pick it up automatically from the
   catalogue. **Do not** reuse `module.info` for a feature that deserves its own on/off switch;
   `module.info` is only the catch-all for one-off informational alerts.
3. If the category needs an admin screen label, the `display_name`/`description` you seeded are
   what the preferences and settings pages show.

## Worked examples for other modules

These are the cross-module requirements that this service is designed to satisfy; each is a
one-call integration plus a seeded category.

- **DOT-FR-006 (ownership transfer)** — on reassigning a document's owner, notify the new owner:
  category `document.ownership-transferred` (tier `standard`), `notify(...)` from the ownership
  service within its transaction.
- **OMD-FR-016 (overdue threshold → manager)** — a scheduled job that counts overdue tasks per
  manager and, over the configured threshold, calls `notify(...)` with category
  `workflow.overdue-threshold` (tier `standard`/`critical` as the team decides), recipients = the
  manager(s).
- **SHAD-FR-009 (health metric → admins)** — the health monitor calls `notify(...)` with category
  `system.health-breach` (tier `critical`), recipients = tenant admins.
- **CRG-FR-006 (scheduled report delivery + failure)** — on success notify the subscribers
  (category `reports.schedule.delivered`, optional); on failure notify the owner (category
  `reports.schedule.failed`, standard/critical). Validate data-access permission at execution
  time before including any link.

`module.info` is the catch-all optional category (email off by default) for one-off informational alerts only.
**A module that plugs in should add its own category** (step 1 above) - that is what gives its users and tenant
admins their own on/off switch and its own row in the preferences and settings pages.
