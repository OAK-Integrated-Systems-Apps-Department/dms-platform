import { ApiError, friendlyMessage } from "@/lib/api-error"

/**
 * Fallback copy for the machine-readable `code` the backend puts on a
 * workflow or department error (ErrorResponse.code). A toast shows the
 * backend's own `message` whenever there is one, since it carries the
 * detail (which stage, which approver slot is empty); these are used only
 * when a coded error arrives without a message.
 */
const ERROR_MESSAGES: Record<string, string> = {
  // Submitting a document
  WORKFLOW_ALREADY_IN_PROGRESS: "This document already has a workflow in progress.",
  WORKFLOW_DOCUMENT_NOT_ELIGIBLE: "This document can't be submitted for approval in its current state.",
  WORKFLOW_NO_ACTIVE_TEMPLATE: "There is no active approval workflow for this document type.",
  WORKFLOW_NO_ELIGIBLE_APPROVERS: "No eligible approvers could be found for this workflow.",
  WORKFLOW_INITIATOR_IS_APPROVER: "You are the only approver on a stage, and you can't approve your own submission.",
  WORKFLOW_UNSATISFIABLE_STAGE: "A stage of this workflow can't be completed with the approvers currently available.",
  WORKFLOW_TEMPLATE_INVALID: "This workflow template is incomplete. Every stage needs at least one participant.",
  // Acting on a task
  WORKFLOW_STAGE_NOT_ACTIVE: "This stage is no longer active.",
  WORKFLOW_NOT_IN_PROGRESS: "This workflow has already finished.",
  WORKFLOW_ENTRY_SATISFIED: "Someone else already completed this approval.",
  WORKFLOW_TASK_CLOSED: "This task is already closed.",
  WORKFLOW_TASK_ALREADY_CLAIMED: "Someone else claimed this task first.",
  WORKFLOW_TASK_NOT_CLAIMABLE: "This task can't be claimed.",
  WORKFLOW_TASK_NOT_CLAIMED_BY_YOU: "Claim this task before acting on it, or it was claimed by someone else.",
  WORKFLOW_ALREADY_ACTED_IN_STAGE: "You have already acted in this stage.",
  WORKFLOW_NOT_ASSIGNEE: "This task isn't assigned to you.",
  WORKFLOW_SELF_APPROVAL_FORBIDDEN: "You can't approve a document you submitted.",
  WORKFLOW_CANCEL_FORBIDDEN: "Only the person who submitted the document (or a tenant admin) can cancel this workflow.",
  // Template design
  WORKFLOW_TEMPLATE_CODE_EXISTS: "A workflow template with this code already exists.",
  WORKFLOW_TEMPLATE_NOT_DRAFT: "Only draft templates can be edited. Create a new version to make changes.",
  WORKFLOW_DRAFT_EXISTS: "A draft version of this template already exists. Edit that draft instead.",
  WORKFLOW_TEMPLATE_IN_USE: "This template version has been used by a workflow and can't be deleted.",
  WORKFLOW_TEMPLATE_NOT_ACTIVE: "This template isn't active.",
  WORKFLOW_TEMPLATE_ACTIVE_CONFLICT: "Another template is already active for this document type. Deactivate it first.",
  WORKFLOW_INVALID_ASSIGNEE: "One of the participants no longer exists or is inactive.",
  WORKFLOW_DUPLICATE_PARTICIPANT: "The same participant is listed twice in this stage.",
  WORKFLOW_INVALID_STAGE: "This stage no longer exists on the template.",
  WORKFLOW_INVALID_TEMPLATE_DEFAULTS:
    "The reminder defaults aren't valid: escalation needs an inactivity reminder, and a named person needs someone chosen.",
  WORKFLOW_INVALID_STAGE_ORDER: "The stage order is out of date. Reload the page and try again.",
  WORKFLOW_DOCUMENT_TYPE_INVALID: "Pick an active document type.",
  WORKFLOW_DOCUMENT_TYPE_LOCKED: "The document type can only be changed on version 1 of a template.",
  CONCURRENT_UPDATE: "Someone else changed this at the same time. Please try again.",
  // Departments
  DEPARTMENT_CODE_EXISTS: "A department with this code already exists.",
  DEPARTMENT_CYCLE: "A department can't be placed under one of its own sub-departments.",
  DEPARTMENT_HAS_MEMBERS: "This department still has members. Move them to another department first.",
  DEPARTMENT_HAS_CHILDREN: "This department has sub-departments. Move or delete them first.",
  DEPARTMENT_IN_USE: "A workflow stage routes to this department, so it can't be deleted.",
  DEPARTMENT_INACTIVE: "That department is inactive.",
}

/** The friendly message for a failed workflow/department call - by `code` when known, else 403 copy or the backend message. */
export function workflowErrorMessage(error: unknown, fallback = "Something went wrong. Please try again."): string {
  if (error instanceof ApiError && error.code) {
    // A coded error is a deliberate refusal (including a 403 such as
    // WORKFLOW_NOT_ASSIGNEE), so its message is meant for the user.
    return error.message.trim() || ERROR_MESSAGES[error.code] || fallback
  }
  return friendlyMessage(error, fallback)
}
