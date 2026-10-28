# Workflow audit events (3.1 Workflow Automation, Phases 1 and 2)

Every workflow state change writes one row to `audit_events`, in the same transaction as the change. The audit trail is the compliance record. The instance timeline the UI shows is a separate, local table, `workflow_actions` (decision 13).

Registered in `AuditActionCatalog` (module `WA`, departments under `OTUA`, document comments under `DOCM`) and emitted through `AuditService.event(...)`. Phase 1 defines and emits the format; compliance export is out of scope.

## Common fields

| Column | Value |
|---|---|
| `module` | `WA`, or `OTUA` for departments |
| `object_type` | `WORKFLOW_TEMPLATE`, `WORKFLOW_INSTANCE`, `WORKFLOW_TASK`, `WORKFLOW_INFO_REQUEST`, `WORKFLOW_SUPPORTING_DOCUMENT`, `DOCUMENT_COMMENT` or `DEPARTMENT` |
| `object_id` | Template, instance, task, information request, supporting document, comment or department id |
| `resource_name` | The document title for runtime events; `CODE vN` for templates |
| `object_version` | `template_version_number` for runtime events; `version_number` for templates |
| `actor_*` | The acting user. `SYSTEM` when there is no request user, e.g. a version change promoted by the scan retry scheduler, or a reminder/escalation from the SLA scheduler |
| `tenant_id` | Always set explicitly from the instance or template, so background contexts audit to the right tenant |
| `reason` | Rejection, cancel or withdrawal reason. **Mandatory** where marked |
| `previous_value` / `new_value` | JSON, shapes below |

Runtime `new_value` always starts with the instance summary:

```json
{
  "instanceId": "…",
  "documentId": "…",
  "documentVersionId": "…",
  "templateId": "…",
  "templateVersionNumber": 1,
  "status": "in_progress"
}
```

…followed by event-specific fields.

## Events

| Action | Object | Severity | Reason required | Extra `new_value` fields |
|---|---|---|---|---|
| `WORKFLOW_TEMPLATE_CREATED` | template | MEDIUM | – | `templateId, code, name, versionNumber, status, documentTypeId, onDocumentChange`, and the template's inactivity defaults (3.2): `defaultInactivityReminderHours, defaultEscalateAfterReminderHours` (the template-level due-date reminder and escalation target defaults no longer exist) |
| `WORKFLOW_TEMPLATE_UPDATED` | template | MEDIUM | – | Same, plus a `previous_value` snapshot - so a change to a default (an admin changing when reminders and escalations fire) shows as a before/after pair |
| `WORKFLOW_TEMPLATE_STAGES_UPDATED` | template | MEDIUM | – | `change` (`stage_added`, `stage_updated`, `stage_deleted` or `stages_reordered`), `stageId`, `stages` (the full stage list after the change, including each stage's `inactivityReminderHours` and `escalateAfterReminderHours` overrides - null means it inherits the template default) |
| `WORKFLOW_TEMPLATE_VERSION_CREATED` | template (the new draft) | MEDIUM | – | `sourceTemplateId, sourceVersionNumber`, plus the snapshot above (a new version copies the template defaults and the stage overrides) |
| `WORKFLOW_TEMPLATE_ACTIVATED` | template | MEDIUM | – | Snapshot |
| `WORKFLOW_TEMPLATE_DEACTIVATED` | template | MEDIUM | – | Snapshot, plus `supersededBy` when another activation demoted it |
| `WORKFLOW_TEMPLATE_DELETED` | template | HIGH | – | `previous_value` snapshot |
| `WORKFLOW_SUBMITTED` | instance | MEDIUM | – | `templateCode, stageCount` |
| `WORKFLOW_TASK_CLAIMED` / `_RELEASED` | task | LOW | – | `taskId, stageInstanceId, stageOrder` |
| `WORKFLOW_TASK_APPROVED` | task | MEDIUM | – | Plus `assigneeType, assigneeId, decision` |
| `WORKFLOW_TASK_REJECTED` | task | MEDIUM | **yes** | Same |
| `WORKFLOW_STAGE_COMPLETED` | instance | LOW | – | `stageOutcome` (`approved` or `rejected`), `stageOrder, stageName, completionRule, rejectionRule, entryCount, requiredCount, approvedCount, rejectedCount, cancelledTaskIds` |
| `WORKFLOW_APPROVED` | instance | MEDIUM | – | – |
| `WORKFLOW_REJECTED` | instance | HIGH | **yes** | `rejections: [{taskId, actedBy, reason}]`. `reason` is the single rejection reason verbatim, or `Name: reason; …` when there are several (`wait_for_all`) |
| `WORKFLOW_CANCELLED` | instance | HIGH | **yes** | `cause` (`withdrawn` or `document_version_changed`), `cancelledTaskIds` |
| `WORKFLOW_DOCUMENT_VERSION_CHANGED` | instance | MEDIUM | – | `fromVersionId, toVersionId`, and `restartRefused` (a code) when a restart fell back to flag-only |
| `WORKFLOW_RESTARTED` | instance (the new one) | MEDIUM | – | `previousInstanceId, newInstanceId, fromVersionId, toVersionId` |
| `DEPARTMENT_CREATED` / `_UPDATED` | department | MEDIUM | – | `name, code, parentDepartmentId, headUserId, status` |
| `DEPARTMENT_DELETED` | department | HIGH | – | `previous_value` snapshot |
| `USER_DEPARTMENT_CHANGED` | user | MEDIUM | – | `previous_value {departmentId}`, `new_value {departmentId}` |

### Phase 2

| Action | Object | Severity | Reason required | Extra `new_value` fields |
|---|---|---|---|---|
| `WORKFLOW_TASK_DELEGATED` | task | MEDIUM | – | `from, fromName, to, toName, originalHolder, returnedToOriginal, delegatedFrom, delegatedTo` |
| `WORKFLOW_TASK_REASSIGNED` | task | MEDIUM | **yes** | Same; `from` is null for a task reassigned from an unclaimed pool |
| `WORKFLOW_TASK_REMINDER_SENT` | task | LOW | – | Two kinds, both actor `SYSTEM`, each sent once per task. **Due-date:** `dueAt, reminderBeforeHours` (the stage's own value). **Inactivity** (3.2): `reason: "inactivity"`, `inactivityReminderHours` - no `dueAt`, since a task without an SLA can still be reminded |
| `WORKFLOW_TASK_ESCALATED` | task | MEDIUM | – | `dueAt` (absent when the task has no due time and escalated after an inactivity reminder), `escalationTarget` (the stage's own), `source` (`named_user`, `pool_department_head` or `initiator_department_head`), then `target, targetName`, or `skipped` (`no_target`, `target_inactive`, `target_is_initiator`, `target_is_holder`, `target_already_decided`, `target_lacks_permission`). Actor `SYSTEM` |
| `WORKFLOW_INFO_REQUESTED` | information request | MEDIUM | – | `infoRequestId, taskId, requestedBy, requestedFrom, requestedFromName, requestStatus` |
| `WORKFLOW_INFO_PROVIDED` | information request | MEDIUM | – | Same, plus `supportingDocumentIds, taskResumed` |
| `WORKFLOW_INFO_DECLINED` | information request | MEDIUM | **yes** | Same, plus `taskResumed` |
| `WORKFLOW_INFO_CANCELLED` | information request | MEDIUM | **yes** | `cause` (`withdrawn_by_requester`, `stage_approved`, `workflow_rejected`, `withdrawn`, `restarted` or `task_reassigned`), `automatic` |
| `WORKFLOW_INFO_REQUEST_REASSIGNED` | information request | LOW | – | `from, fromName, to, toName` |
| `WORKFLOW_SUPPORTING_DOCUMENT_ADDED` / `_REMOVED` | supporting document | LOW | – | `supportingDocumentId, documentId, documentVersionId, source, title, infoRequestId` |
| `DOCUMENT_COMMENT_CREATED` / `_UPDATED` | comment | LOW | – | `commentId, documentId, workflowInstanceId, parentCommentId, length, mentionCount`. **Never the comment text** |
| `DOCUMENT_COMMENT_DELETED` | comment | MEDIUM | – | Same |

The decision events (`WORKFLOW_TASK_APPROVED`/`_REJECTED`) also carry `route` (`assigned`, `pool`, `claimed`, `delegated` or `escalated`), `delegatedFrom`, `delegatedTo` and `escalatedTo`.

A refused request writes **no** workflow audit row. For example, a submission without an active template (Scenario G) writes nothing. A 403 from `@PreAuthorize` is still audited as the generic `ACCESS_DENIED`, as it is everywhere else.

## Timeline (`workflow_actions.action_type`)

The timeline records the same facts, in UI terms:

- **By people:** `submitted`, `claimed`, `released`, `approved`, `rejected`, `cancelled`
- **By the system** (`actor_user_id` null): `stage_activated` (detail lists every entry and the users it resolved to at activation), `stage_approved`, `stage_rejected`, `stage_unassignable`, `workflow_approved`, `workflow_rejected`, `version_changed`, `restarted`
- **Phase 2, by people:** `delegated`, `reassigned` (reason = the admin's reason), `commented` (detail `commentId`, never the text), `info_requested` (reason = the request's message), `info_provided`, `info_declined`, `info_cancelled` (withdrawn), `info_reassigned`, `supporting_document_added`, `supporting_document_removed`
- **Phase 2, by the system:** `reminded` (detail `reminderBeforeHours`, or `reason: "inactivity"` with `inactivityReminderHours`), `escalated` (detail `target` or `skipped`), `info_cancelled` when the engine closes a request (detail `automatic: true`, `cause`)
- `approved` and `rejected` carry `detail.route`, and `detail.delegatedFrom`/`delegatedFromName` when a delegate decided, so the timeline reads "Approved by <delegate>, delegated from <original>" (decision 2).
- Entries about an information request have `info_request_id` set (V96).
