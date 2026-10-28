# Notification audit events (3.2 Notification)

Notification records live in **two layers** (plan decision 6):

1. **`notification_delivery_attempts`** - every send attempt, one row each, append-only. `UPDATE` and `DELETE` are blocked by a trigger (and `REVOKE`d), so the log is immutable even for the owning database role. This is the record N-FR-005 / N-NFR-005 ask for: channel, recipient, timestamp and outcome of every attempt, including skipped ones. It is part of the compliance export.
2. **The main audit trail (`audit_events`)** - only the few events below, written through `AuditService.event(...)` in the same transaction as the change. It carries the things an auditor reviews rather than every email.

Registered in `AuditActionCatalog` under module `NOTIF`. Escalations are not listed here: they are audited by the existing `WORKFLOW_TASK_ESCALATED` event (including the skipped ones), see [workflow-audit-events.md](workflow-audit-events.md).

## Layer 1: `notification_delivery_attempts`

| Column | Value |
|---|---|
| `attempt_id`, `tenant_id` | Row id; the notification's tenant |
| `delivery_id`, `notification_id` | The delivery that was attempted and the notification it belongs to |
| `recipient_user_id` | Who it was for |
| `attempt_number` | 1-based, per delivery |
| `channel` | `email` or `in_app` |
| `destination` | The address used (null for in-app) |
| `outcome` | `sent`, `failed` or `skipped` |
| `error_code`, `error_message` | Set on `failed` (a short code and the message, truncated to 2000 characters); `skipped` carries the reason in `error_code` (`email_disabled`, `no_email`) |
| `duration_ms` | How long the send took (null when nothing was sent) |
| `attempted_at` | When |

An in-app delivery is written as one `sent` attempt at creation. An email delivery that is skipped is written as one `skipped` attempt; a sent email is one `sent` attempt; each retry is one more `failed` attempt until it succeeds or `max_attempts` is reached.

## Layer 2: events in `audit_events`

Common fields: `module` = `NOTIF`, `tenant_id` set explicitly (these run on background threads with no request context), `outcome` set on every row.

| Action | Object | Severity | Actor | When | `new_value` / other fields |
|---|---|---|---|---|---|
| `NOTIFICATION_DELIVERY_FAILED` | `NOTIFICATION_DELIVERY` (`object_id` = delivery id) | MEDIUM | `SYSTEM` | A delivery used all its attempts and is now `failed`. The in-app entry stands; an admin can retry it | `outcome` = `FAILURE`, `failure_reason` = the last error. `notificationId, recipientUserId, categoryCode, channel, attempts, lastError` |
| `NOTIFICATION_RECIPIENT_NO_EMAIL` | `NOTIFICATION_DELIVERY` (`object_id` = the **notification** id) | MEDIUM | `SYSTEM` | A recipient has no email address, so the notification went in-app only - flags the missing contact to the administrator | `notificationId, recipientUserId, categoryCode` |
| `NOTIFICATION_SETTING_CHANGED` | `NOTIFICATION_SETTING` (`resource_name` = the category code) | MEDIUM | The tenant admin | A category's tenant email default was set or changed (critical categories cannot be) | `previous_value {emailEnabledDefault}` (the string `"null"` when there was no tenant setting before), `new_value {emailEnabledDefault}` |
| `NOTIFICATION_PREFERENCE_CHANGED` | `NOTIFICATION_SETTING` (`resource_name` = the category code) | LOW | The user | A user turned a category's email on or off for themselves (critical categories cannot be) | `previous_value {emailEnabled}`, `new_value {emailEnabled}` |
| `NOTIFICATION_DELIVERY_RETRIED` | `NOTIFICATION_DELIVERY` (`object_id` = delivery id) | MEDIUM | The admin who pressed "Retry" | A `failed` or `skipped` delivery was put back in the queue | `new_value {channel}` |

Not audited here, on purpose: a notification being created, read, archived, or sent successfully. Those are in the inbox table and layer 1.

## Related changes to existing events

The reminder and escalation settings on workflow templates and stages (3.2) change what two existing events record - see [workflow-audit-events.md](workflow-audit-events.md): the template snapshot carries the two template inactivity defaults, the stage list carries the two stage overrides, and `WORKFLOW_TASK_REMINDER_SENT` is also written for an inactivity reminder.
