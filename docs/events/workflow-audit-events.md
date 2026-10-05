# Workflow audit events (3.1 Workflow Automation, Phase 1)

Every workflow state change writes one row to `audit_events`, in the same transaction as the change. The audit trail is the compliance record. The instance timeline the UI shows is a separate, local table, `workflow_actions` (decision 13).

Registered in `AuditActionCatalog` (module `WA`, departments under `OTUA`) and emitted through `AuditService.event(...)`. Phase 1 defines and emits the format; compliance export is out of scope.

## Common fields

| Column | Value |
|---|---|
| `module` | `WA`, or `OTUA` for departments |
| `object_type` | `WORKFLOW_TEMPLATE`, `WORKFLOW_INSTANCE`, `WORKFLOW_TASK` or `DEPARTMENT` |
| `object_id` | Template, instance, task or department id |
| `resource_name` | The document title for runtime events; `CODE vN` for templates |
| `object_version` | `template_version_number` for runtime events; `version_number` for templates |
| `actor_*` | The acting user. `SYSTEM` when there is no request user, e.g. a version change promoted by the scan retry scheduler |
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
| `WORKFLOW_TEMPLATE_CREATED` | template | MEDIUM | – | `templateId, code, name, versionNumber, status, documentTypeId, onDocumentChange` |
| `WORKFLOW_TEMPLATE_UPDATED` | template | MEDIUM | – | Same, plus a `previous_value` snapshot |
| `WORKFLOW_TEMPLATE_STAGES_UPDATED` | template | MEDIUM | – | `change` (`stage_added`, `stage_updated`, `stage_deleted` or `stages_reordered`), `stageId`, `stages` (the full stage list after the change) |
| `WORKFLOW_TEMPLATE_VERSION_CREATED` | template (the new draft) | MEDIUM | – | `sourceTemplateId, sourceVersionNumber` |
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

A refused request writes **no** workflow audit row. For example, a submission without an active template (Scenario G) writes nothing. A 403 from `@PreAuthorize` is still audited as the generic `ACCESS_DENIED`, as it is everywhere else.

## Timeline (`workflow_actions.action_type`)

The timeline records the same facts, in UI terms:

- **By people:** `submitted`, `claimed`, `released`, `approved`, `rejected`, `cancelled`
- **By the system** (`actor_user_id` null): `stage_activated` (detail lists every entry and the users it resolved to at activation), `stage_approved`, `stage_rejected`, `stage_unassignable`, `workflow_approved`, `workflow_rejected`, `version_changed`, `restarted`

The CHECK constraint already allows the Phase 2 values: `reassigned`, `delegated`, `escalated`, `reminded`, `commented` and `info_*`.
