import type {
  CompletionRule,
  InfoRequestStatus,
  InstanceStatus,
  OnDocumentChange,
  RejectionRule,
  StageInstanceStatus,
  TaskStatus,
  TemplateStatus,
  WorkflowActionType,
} from "./api"

type BadgeVariant = "default" | "muted" | "outline" | "gold" | "rust"

/** Display label + badge variant per status - shared by the template, task and status screens. */
export const TEMPLATE_STATUS_LABELS: Record<TemplateStatus, string> = {
  draft: "Draft",
  active: "Active",
  inactive: "Inactive",
}

export const TEMPLATE_STATUS_BADGE_VARIANT: Record<TemplateStatus, BadgeVariant> = {
  draft: "gold",
  active: "default",
  inactive: "muted",
}

export const INSTANCE_STATUS_LABELS: Record<InstanceStatus, string> = {
  in_progress: "In progress",
  approved: "Approved",
  rejected: "Rejected",
  cancelled: "Cancelled",
}

export const INSTANCE_STATUS_BADGE_VARIANT: Record<InstanceStatus, BadgeVariant> = {
  in_progress: "gold",
  approved: "default",
  rejected: "rust",
  cancelled: "muted",
}

export const STAGE_STATUS_LABELS: Record<StageInstanceStatus, string> = {
  not_started: "Not started",
  active: "Active",
  approved: "Approved",
  rejected: "Rejected",
  skipped: "Skipped",
}

export const STAGE_STATUS_BADGE_VARIANT: Record<StageInstanceStatus, BadgeVariant> = {
  not_started: "muted",
  active: "gold",
  approved: "default",
  rejected: "rust",
  skipped: "outline",
}

export const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
  pending: "Pending",
  claimed: "Claimed",
  awaiting_info: "Waiting on info",
  approved: "Approved",
  rejected: "Rejected",
  cancelled: "Cancelled",
  delegated: "Delegated",
  expired: "Expired",
}

export const TASK_STATUS_BADGE_VARIANT: Record<TaskStatus, BadgeVariant> = {
  pending: "outline",
  claimed: "gold",
  awaiting_info: "gold",
  approved: "default",
  rejected: "rust",
  cancelled: "muted",
  delegated: "muted",
  expired: "muted",
}

export const INFO_REQUEST_STATUS_LABELS: Record<InfoRequestStatus, string> = {
  open: "Open",
  answered: "Answered",
  declined: "Declined",
  cancelled: "Cancelled",
}

export const INFO_REQUEST_STATUS_BADGE_VARIANT: Record<InfoRequestStatus, BadgeVariant> = {
  open: "gold",
  answered: "default",
  declined: "rust",
  cancelled: "muted",
}

export const COMPLETION_RULE_LABELS: Record<CompletionRule, string> = {
  all: "All",
  any: "Any",
  majority: "Majority",
}

export const REJECTION_RULE_LABELS: Record<RejectionRule, string> = {
  halt_immediately: "Stop at first rejection",
  wait_for_all: "Wait for every approver",
}

export const ON_DOCUMENT_CHANGE_LABELS: Record<OnDocumentChange, string> = {
  flag_only: "Flag only",
  restart: "Restart",
}

/** Timeline wording per action - "{actor} {label}". */
export const ACTION_TYPE_LABELS: Record<WorkflowActionType, string> = {
  submitted: "submitted the document for approval",
  claimed: "claimed a task",
  released: "released a task back to the pool",
  approved: "approved",
  rejected: "rejected",
  stage_activated: "Stage started",
  stage_approved: "Stage approved",
  stage_rejected: "Stage rejected",
  stage_unassignable: "Stage has no eligible approvers",
  workflow_approved: "Workflow approved",
  workflow_rejected: "Workflow rejected",
  cancelled: "cancelled the workflow",
  version_changed: "A new document version was uploaded",
  restarted: "Workflow restarted",
  reassigned: "reassigned a task",
  delegated: "delegated a task",
  escalated: "Task escalated",
  reminded: "Reminder sent",
  commented: "commented",
  info_requested: "asked for information",
  info_provided: "answered an information request",
  info_declined: "declined an information request",
  info_cancelled: "withdrew an information request",
  info_reassigned: "redirected an information request",
  supporting_document_added: "attached a supporting document",
  supporting_document_removed: "removed a supporting document",
}
