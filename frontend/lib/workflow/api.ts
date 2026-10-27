import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

const BASE = `${API_BASE_URL}/api/v1/workflow`

export type TemplateStatus = "draft" | "active" | "inactive"
export type OnDocumentChange = "flag_only" | "restart"
export type CompletionRule = "all" | "any" | "majority"
export type RejectionRule = "halt_immediately" | "wait_for_all"
export type EscalationTarget = "none" | "department_head" | "named_user"
export type AssigneeType = "user" | "role" | "department"
export type InstanceStatus = "in_progress" | "approved" | "rejected" | "cancelled"
export type StageInstanceStatus = "not_started" | "active" | "approved" | "rejected" | "skipped"
/** `delegated` and `expired` are never written (Phase 2 decisions 1 and 6) but kept so any row reads. */
export type TaskStatus =
  | "pending"
  | "claimed"
  | "awaiting_info"
  | "approved"
  | "rejected"
  | "cancelled"
  | "delegated"
  | "expired"
/** How the caller reaches a task (My Tasks) or how its decider did (timeline detail). */
export type TaskRoute = "assigned" | "pool" | "claimed" | "delegated" | "escalated"
export type InfoRequestStatus = "open" | "answered" | "declined" | "cancelled"
export type SupportingDocumentSource = "linked_existing" | "uploaded_new"
export type TaskDecision = "approve" | "reject"
export type WorkflowActionType =
  | "submitted"
  | "claimed"
  | "released"
  | "approved"
  | "rejected"
  | "stage_activated"
  | "stage_approved"
  | "stage_rejected"
  | "stage_unassignable"
  | "workflow_approved"
  | "workflow_rejected"
  | "cancelled"
  | "version_changed"
  | "restarted"
  | "reassigned"
  | "delegated"
  | "escalated"
  | "reminded"
  | "commented"
  | "info_requested"
  | "info_provided"
  | "info_declined"
  | "info_cancelled"
  | "info_reassigned"
  | "supporting_document_added"
  | "supporting_document_removed"
export type MyTasksView = "all" | "assigned" | "available" | "claimed" | "waiting"

/** Mirrors backend/dms-core's PageResponse<T>. `page` is 0-indexed. */
export interface PageDto<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** Mirrors backend/dms-core's workflow UserRef. */
export interface WorkflowUserRefDto {
  userId: string
  displayName: string | null
}

// ---------------------------------------------------------------- templates

/** Mirrors backend/dms-core's WorkflowTemplateSummaryResponse - one list row, one per template version. */
export interface WorkflowTemplateSummaryDto {
  templateId: string
  code: string
  name: string
  versionNumber: number
  status: TemplateStatus
  documentTypeId: string
  documentTypeName: string | null
  stageCount: number
  onDocumentChange: OnDocumentChange
  createdAt: string
  updatedAt: string | null
}

/** Mirrors backend/dms-core's ParticipantResponse. displayName is null when the user/role/department no longer exists. */
export interface WorkflowParticipantDto {
  participantId: string
  assigneeType: AssigneeType
  assigneeId: string
  displayName: string | null
  displayOrder: number
}

/** Mirrors backend/dms-core's WorkflowStageResponse. */
export interface WorkflowStageDto {
  stageId: string
  stageOrder: number
  name: string
  completionRule: CompletionRule
  rejectionRule: RejectionRule
  slaHours: number | null
  /** The due-date reminder: this many hours before the task is due. Needs SLA hours. */
  reminderBeforeHours: number | null
  /** The inactivity reminder: hours with no activity on the task. null = inherit the template default. */
  inactivityReminderHours: number | null
  /** Hours after the inactivity reminder before escalating. null = inherit the template default. */
  escalateAfterReminderHours: number | null
  escalationTarget: EscalationTarget | null
  escalationUserId: string | null
  /** Whether approvers may pause their task to request information (default true). */
  allowInfoRequests: boolean
  /** Whether approving this stage requires a digital signature - always true, every stage (V117, 1.4). */
  requiresSignature: boolean
  participants: WorkflowParticipantDto[]
}

/** Mirrors backend/dms-core's WorkflowTemplateResponse. hasInstances: some instance ran on this version, so it can never be deleted. */
export interface WorkflowTemplateDto {
  templateId: string
  code: string
  name: string
  description: string | null
  versionNumber: number
  status: TemplateStatus
  documentTypeId: string
  documentTypeName: string | null
  onDocumentChange: OnDocumentChange
  /** Inactivity defaults for every stage of this template (N-FR-006); a stage's own value overrides them. */
  defaultInactivityReminderHours: number | null
  defaultEscalateAfterReminderHours: number | null
  hasInstances: boolean
  stages: WorkflowStageDto[]
  createdAt: string
  updatedAt: string | null
}

/** Every filter `GET /api/v1/workflow/templates` accepts. */
export interface WorkflowTemplateFilters {
  status?: TemplateStatus
  documentTypeId?: string
  search?: string
}

/** The template-level inactivity defaults. Replaced as a whole: leave one out and it is cleared. */
export interface TemplateReminderDefaultsPayload {
  defaultInactivityReminderHours?: number | null
  defaultEscalateAfterReminderHours?: number | null
}

export interface CreateWorkflowTemplatePayload extends TemplateReminderDefaultsPayload {
  name: string
  code: string
  description?: string
  documentTypeId: string
  onDocumentChange?: OnDocumentChange
}

export interface UpdateWorkflowTemplatePayload extends TemplateReminderDefaultsPayload {
  name: string
  description: string | null
  documentTypeId: string
  onDocumentChange: OnDocumentChange
}

export interface ParticipantPayload {
  assigneeType: AssigneeType
  assigneeId: string
  displayOrder?: number
}

/** Mirrors backend/dms-core's UpsertWorkflowStageRequest - the participant list is replaced as a whole. */
export interface UpsertStagePayload {
  name: string
  completionRule: CompletionRule
  rejectionRule: RejectionRule
  slaHours?: number | null
  reminderBeforeHours?: number | null
  /** null / omitted = inherit the template default. */
  inactivityReminderHours?: number | null
  escalateAfterReminderHours?: number | null
  escalationTarget?: EscalationTarget | null
  escalationUserId?: string | null
  allowInfoRequests?: boolean
  participants: ParticipantPayload[]
}

function jsonInit(method: string, body?: unknown): RequestInit {
  return {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  }
}

/** One page of template versions. `page` is 0-indexed here. */
export async function fetchWorkflowTemplates(
  filters: WorkflowTemplateFilters,
  page: number,
  size: number
): Promise<PageDto<WorkflowTemplateSummaryDto>> {
  const params = new URLSearchParams()
  if (filters.status) params.set("status", filters.status)
  if (filters.documentTypeId) params.set("documentTypeId", filters.documentTypeId)
  if (filters.search) params.set("search", filters.search)
  params.set("page", String(page))
  params.set("size", String(size))
  params.set("sort", "updatedAt,desc")
  return apiFetchJson(`${BASE}/templates?${params.toString()}`, undefined, "Could not load workflow templates.")
}

export async function fetchWorkflowTemplate(templateId: string): Promise<WorkflowTemplateDto> {
  return apiFetchJson(`${BASE}/templates/${templateId}`, undefined, "Could not load this workflow template.")
}

/** Every version in this template's family (same code). */
export async function fetchWorkflowTemplateVersions(templateId: string): Promise<WorkflowTemplateSummaryDto[]> {
  return apiFetchJson(`${BASE}/templates/${templateId}/versions`, undefined, "Could not load this template's versions.")
}

export async function createWorkflowTemplate(payload: CreateWorkflowTemplatePayload): Promise<WorkflowTemplateDto> {
  return apiFetchJson(`${BASE}/templates`, jsonInit("POST", payload), "Could not create this workflow template.")
}

/** Drafts only. */
export async function updateWorkflowTemplate(
  templateId: string,
  payload: UpdateWorkflowTemplatePayload
): Promise<WorkflowTemplateDto> {
  return apiFetchJson(`${BASE}/templates/${templateId}`, jsonInit("PUT", payload), "Could not save changes.")
}

/** Copies this version into a new draft (the next version number). */
export async function createWorkflowTemplateVersion(templateId: string): Promise<WorkflowTemplateDto> {
  return apiFetchJson(`${BASE}/templates/${templateId}/versions`, { method: "POST" }, "Could not create a new version.")
}

export async function activateWorkflowTemplate(templateId: string): Promise<WorkflowTemplateDto> {
  return apiFetchJson(`${BASE}/templates/${templateId}/activate`, { method: "POST" }, "Could not activate this template.")
}

export async function deactivateWorkflowTemplate(templateId: string): Promise<WorkflowTemplateDto> {
  return apiFetchJson(`${BASE}/templates/${templateId}/deactivate`, { method: "POST" }, "Could not deactivate this template.")
}

export async function deleteWorkflowTemplate(templateId: string): Promise<void> {
  await apiFetch(`${BASE}/templates/${templateId}`, { method: "DELETE" }, "Could not delete this template.")
}

export async function addWorkflowStage(templateId: string, payload: UpsertStagePayload): Promise<WorkflowTemplateDto> {
  return apiFetchJson(`${BASE}/templates/${templateId}/stages`, jsonInit("POST", payload), "Could not add this stage.")
}

export async function updateWorkflowStage(
  templateId: string,
  stageId: string,
  payload: UpsertStagePayload
): Promise<WorkflowTemplateDto> {
  return apiFetchJson(
    `${BASE}/templates/${templateId}/stages/${stageId}`,
    jsonInit("PUT", payload),
    "Could not save this stage."
  )
}

export async function deleteWorkflowStage(templateId: string, stageId: string): Promise<WorkflowTemplateDto> {
  return apiFetchJson(
    `${BASE}/templates/${templateId}/stages/${stageId}`,
    { method: "DELETE" },
    "Could not delete this stage."
  )
}

/** `stageIds` must list every stage of the template, in the new order. */
export async function reorderWorkflowStages(templateId: string, stageIds: string[]): Promise<WorkflowTemplateDto> {
  return apiFetchJson(
    `${BASE}/templates/${templateId}/stages/order`,
    jsonInit("PUT", { stageIds }),
    "Could not reorder the stages."
  )
}

// ---------------------------------------------------------------- instances

/** Mirrors backend/dms-core's TaskView. assigneeName is the user, role or department name. */
export interface WorkflowTaskDto {
  taskId: string
  assigneeType: AssigneeType
  assigneeId: string
  assigneeName: string | null
  status: TaskStatus
  claimedBy: WorkflowUserRefDto | null
  actedBy: WorkflowUserRefDto | null
  decision: TaskDecision | null
  decisionReason: string | null
  actedAt: string | null
  /** Moves forward by the paused time when an information request closes. */
  dueAt: string | null
  /** Original holder of a delegated/reassigned task (null when reassigned from an unclaimed pool). */
  delegatedFrom: WorkflowUserRefDto | null
  delegatedTo: WorkflowUserRefDto | null
  delegatedAt: string | null
  /** Added escalation route; null with escalatedAt set means the target couldn't be used. */
  escalatedTo: WorkflowUserRefDto | null
  escalatedAt: string | null
  reminderSentAt: string | null
  slaPausedAt: string | null
  slaPausedSeconds: number
  openInfoRequestCount: number
}

/** Mirrors backend/dms-core's StageInstanceView. */
export interface WorkflowStageInstanceDto {
  stageInstanceId: string
  stageOrder: number
  name: string
  status: StageInstanceStatus
  completionRule: CompletionRule
  rejectionRule: RejectionRule
  entryCount: number
  requiredCount: number
  approvedCount: number
  rejectedCount: number
  activatedAt: string | null
  dueAt: string | null
  completedAt: string | null
  tasks: WorkflowTaskDto[]
  allowInfoRequests: boolean
  /** Whether approving this stage requires a digital signature (1.4). */
  requiresSignature: boolean
}

/**
 * Mirrors backend/dms-core's WorkflowInstanceResponse. nextAssignees are the
 * open tasks of the current stage; myOpenTaskId is the caller's actionable
 * task, if any.
 */
export interface WorkflowInstanceDto {
  instanceId: string
  documentId: string
  documentTitle: string | null
  documentVersionId: string | null
  latestDocumentVersionId: string | null
  versionChangedFlag: boolean
  templateId: string
  templateCode: string
  templateName: string
  templateVersionNumber: number
  status: InstanceStatus
  initiatedBy: WorkflowUserRefDto | null
  startedAt: string
  completedAt: string | null
  outcomeReason: string | null
  currentStageInstanceId: string | null
  stages: WorkflowStageInstanceDto[]
  nextAssignees: WorkflowTaskDto[]
  myOpenTaskId: string | null
  infoRequests: InfoRequestDto[]
  supportingDocuments: SupportingDocumentDto[]
  /** Open requests of this run addressed to the caller. */
  myOpenInfoRequestIds: string[]
}

/** Mirrors backend/dms-core's SupportingDocumentView. canOpen: the viewer holds docm.documents.view. */
export interface SupportingDocumentDto {
  supportingDocumentId: string
  instanceId: string
  documentId: string
  title: string | null
  versionId: string
  versionNumber: number | null
  source: SupportingDocumentSource
  note: string | null
  addedBy: WorkflowUserRefDto
  addedAt: string
  infoRequestId: string | null
  canOpen: boolean
  /** The pinned version's file, for the shared viewer. Populated only when canOpen; null otherwise. */
  fileName: string | null
  mimeType: string | null
  sizeBytes: number | null
}

/** Mirrors backend/dms-core's InfoRequestView. */
export interface InfoRequestDto {
  infoRequestId: string
  instanceId: string
  documentId: string | null
  documentTitle: string | null
  taskId: string
  stageInstanceId: string
  stageOrder: number | null
  requestedBy: WorkflowUserRefDto
  requestedFrom: WorkflowUserRefDto
  message: string
  status: InfoRequestStatus
  responseMessage: string | null
  declineReason: string | null
  cancelReason: string | null
  dueAt: string | null
  requestedAt: string
  respondedAt: string | null
  respondedBy: WorkflowUserRefDto | null
  supportingDocuments: SupportingDocumentDto[]
}

/** Mirrors backend/dms-core's SupportingDocumentInput. documentVersionId defaults to the latest version. */
export interface SupportingDocumentInput {
  documentId: string
  documentVersionId?: string | null
  source: SupportingDocumentSource
  note?: string | null
}

/** Mirrors backend/dms-core's PersonOptionResponse - one row of a people picker. */
export interface PersonOptionDto {
  userId: string
  displayName: string | null
  username: string | null
  departmentName: string | null
  /** Set by /people/lookup: whether they could open documents. */
  hasDocumentAccess: boolean | null
}

/** Mirrors backend/dms-core's WorkflowActionView - one timeline entry. actor is null for system actions. */
export interface WorkflowActionDto {
  actionId: string
  actionType: WorkflowActionType
  actor: WorkflowUserRefDto | null
  reason: string | null
  detail: Record<string, unknown> | null
  stageInstanceId: string | null
  stageOrder: number | null
  taskId: string | null
  occurredAt: string
}

/** Mirrors backend/dms-core's WorkflowInstanceSummaryResponse - one row of a document's workflow history. */
export interface WorkflowInstanceSummaryDto {
  instanceId: string
  templateName: string
  templateVersionNumber: number
  status: InstanceStatus
  initiatedBy: WorkflowUserRefDto | null
  startedAt: string
  completedAt: string | null
  outcomeReason: string | null
  versionChangedFlag: boolean
}

/**
 * Starts the document type's active workflow on this document, optionally
 * attaching supporting documents in the same transaction. An invalid
 * attachment fails the whole submission - nothing is created.
 */
export async function submitDocumentToWorkflow(
  documentId: string,
  supportingDocuments?: SupportingDocumentInput[]
): Promise<WorkflowInstanceDto> {
  return apiFetchJson(
    `${BASE}/instances`,
    jsonInit("POST", { documentId, supportingDocuments }),
    "Could not submit this document for approval."
  )
}

export async function fetchWorkflowInstance(instanceId: string): Promise<WorkflowInstanceDto> {
  return apiFetchJson(`${BASE}/instances/${instanceId}`, undefined, "Could not load this workflow.")
}

/** Timeline entries, oldest first. `page` is 0-indexed here. */
export async function fetchWorkflowInstanceActions(
  instanceId: string,
  page: number,
  size: number
): Promise<PageDto<WorkflowActionDto>> {
  return apiFetchJson(
    `${BASE}/instances/${instanceId}/actions?page=${page}&size=${size}`,
    undefined,
    "Could not load this workflow's history."
  )
}

/** Initiator or tenant admin only. The reason is required. */
export async function cancelWorkflowInstance(instanceId: string, reason: string): Promise<WorkflowInstanceDto> {
  return apiFetchJson(`${BASE}/instances/${instanceId}/cancel`, jsonInit("POST", { reason }), "Could not cancel this workflow.")
}

/** A document's workflow runs, newest first. `page` is 0-indexed here. */
export async function fetchDocumentWorkflowInstances(
  documentId: string,
  page: number,
  size: number
): Promise<PageDto<WorkflowInstanceSummaryDto>> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/workflow-instances?page=${page}&size=${size}`,
    undefined,
    "Could not load this document's workflow history."
  )
}

/**
 * Mirrors backend/dms-core's DocumentWorkflowStatusResponse - the newest run
 * of one document, for the badges in a list of documents.
 */
export interface DocumentWorkflowStatusDto {
  documentId: string
  instanceId: string
  status: InstanceStatus
  startedAt: string
  versionChangedFlag: boolean
}

/**
 * `GET /api/v1/workflow/document-status` - the newest run of each of these
 * documents, in one call (at most 200). Documents the caller can't open, and
 * documents never submitted, are simply left out.
 */
export async function fetchDocumentWorkflowStatuses(
  documentIds: string[]
): Promise<DocumentWorkflowStatusDto[]> {
  if (documentIds.length === 0) return []
  const params = new URLSearchParams({
    documentIds: documentIds.slice(0, 200).join(","),
  })
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/workflow/document-status?${params.toString()}`,
    undefined,
    "Could not load approval status."
  )
}

// -------------------------------------------------------------------- tasks

/** Mirrors backend/dms-core's MyTaskResponse - one row of the My Tasks queue. poolName is the role/department name for a pool task. */
export interface MyTaskDto {
  taskId: string
  instanceId: string
  documentId: string
  documentTitle: string | null
  documentTypeName: string | null
  templateName: string
  stageOrder: number
  stageName: string
  totalStages: number
  assigneeType: AssigneeType
  poolName: string | null
  status: "pending" | "claimed" | "awaiting_info"
  claimedByMe: boolean
  dueAt: string | null
  overdue: boolean
  activatedAt: string | null
  initiatedBy: WorkflowUserRefDto | null
  versionChangedFlag: boolean
  route: TaskRoute | null
  delegatedFrom: WorkflowUserRefDto | null
  openInfoRequestCount: number
  /** Whether approving this task's stage requires a digital signature - open SignAndApproveDialog instead of a plain approve when true (1.4). */
  requiresSignature: boolean
}

/** Mirrors backend/dms-core's MyTaskCountResponse. requestsForMe: open information requests addressed to me. */
export interface MyTaskCountDto {
  open: number
  overdue: number
  requestsForMe: number
}

/** Mirrors backend/dms-core's TaskActionResponse - the task, its stage and its instance after the action. */
export interface TaskActionResultDto {
  taskId: string
  taskStatus: TaskStatus
  stageInstanceId: string
  stageStatus: StageInstanceStatus
  approvedCount: number
  rejectedCount: number
  requiredCount: number
  instanceId: string
  instanceStatus: InstanceStatus
  currentStageInstanceId: string | null
}

/** One page of the caller's queue. `page` is 0-indexed here. */
export async function fetchMyTasks(view: MyTasksView, page: number, size: number): Promise<PageDto<MyTaskDto>> {
  const params = new URLSearchParams({ view, page: String(page), size: String(size) })
  return apiFetchJson(`${BASE}/tasks/my?${params.toString()}`, undefined, "Could not load your tasks.")
}

export async function fetchMyTaskCount(): Promise<MyTaskCountDto> {
  return apiFetchJson(`${BASE}/tasks/my/count`, undefined, "Could not load your task count.")
}

export async function claimTask(taskId: string): Promise<TaskActionResultDto> {
  return apiFetchJson(`${BASE}/tasks/${taskId}/claim`, { method: "POST" }, "Could not claim this task.")
}

export async function releaseTask(taskId: string): Promise<TaskActionResultDto> {
  return apiFetchJson(`${BASE}/tasks/${taskId}/release`, { method: "POST" }, "Could not release this task.")
}

export async function approveTask(taskId: string, comment?: string): Promise<TaskActionResultDto> {
  return apiFetchJson(
    `${BASE}/tasks/${taskId}/approve`,
    jsonInit("POST", { comment: comment || null }),
    "Could not approve this task."
  )
}

/** The reason is mandatory (WA-FR-003) - the backend 400s a blank one. */
export async function rejectTask(taskId: string, reason: string): Promise<TaskActionResultDto> {
  return apiFetchJson(`${BASE}/tasks/${taskId}/reject`, jsonInit("POST", { reason }), "Could not reject this task.")
}

// ------------------------------------------------------- participant lookups

/** One pickable stage participant (or department head), already resolved to a display label. */
export interface LookupOption {
  value: string
  label: string
  hint?: string
}

/** Mirrors backend LookupOptionResponse. */
interface LookupRow {
  id: string
  label: string
  hint: string | null
}

/**
 * The stage builder's pick lists (GET /api/v1/workflow/lookups/*): active
 * rows of the caller's tenant, names only. Readable by template designers
 * and department managers without the full Users/Roles/Document Types admin
 * permissions.
 */
async function fetchLookup(kind: "users" | "roles" | "document-types", failure: string): Promise<LookupOption[]> {
  const rows = await apiFetchJson<LookupRow[]>(`${BASE}/lookups/${kind}`, undefined, failure)
  return rows.map((row) => ({ value: row.id, label: row.label, hint: row.hint ?? undefined }))
}

export function fetchUserOptions(): Promise<LookupOption[]> {
  return fetchLookup("users", "Could not load users.")
}

export function fetchRoleOptions(): Promise<LookupOption[]> {
  return fetchLookup("roles", "Could not load roles.")
}

export function fetchDocumentTypeOptions(): Promise<LookupOption[]> {
  return fetchLookup("document-types", "Could not load document types.")
}

// ---------------------------------------------------------- delegation (Phase 2)

/** The holder hands the task on; delegating back to the original holder clears the delegation. */
export async function delegateTask(taskId: string, delegateUserId: string, note?: string): Promise<TaskActionResultDto> {
  return apiFetchJson(
    `${BASE}/tasks/${taskId}/delegate`,
    jsonInit("POST", { delegateUserId, note: note || null }),
    "Could not delegate this task."
  )
}

/** Admin (wa.reassign-workflow-task): move any open task; the reason is mandatory. */
export async function reassignTask(taskId: string, userId: string, reason: string): Promise<TaskActionResultDto> {
  return apiFetchJson(`${BASE}/tasks/${taskId}/reassign`, jsonInit("POST", { userId, reason }), "Could not reassign this task.")
}

export async function fetchDelegateCandidates(taskId: string, search: string): Promise<PersonOptionDto[]> {
  const params = new URLSearchParams({ limit: "20" })
  if (search.trim()) params.set("search", search.trim())
  return apiFetchJson(`${BASE}/tasks/${taskId}/delegate-candidates?${params.toString()}`, undefined, "Could not load people.")
}

/** Active people of my tenant (mentions, information requests). */
export async function lookupPeople(search: string, limit = 10): Promise<PersonOptionDto[]> {
  const params = new URLSearchParams({ limit: String(limit) })
  if (search.trim()) params.set("search", search.trim())
  return apiFetchJson(`${API_BASE_URL}/api/v1/people/lookup?${params.toString()}`, undefined, "Could not load people.")
}

// ---------------------------------------------------- information requests (Phase 2)

export async function raiseInfoRequest(
  taskId: string,
  requestedFromUserId: string,
  message: string,
  dueAt?: string | null
): Promise<InfoRequestDto> {
  return apiFetchJson(
    `${BASE}/tasks/${taskId}/info-requests`,
    jsonInit("POST", { requestedFromUserId, message, dueAt: dueAt || null }),
    "Could not send the request."
  )
}

export async function respondInfoRequest(
  infoRequestId: string,
  message: string | null,
  supportingDocuments: SupportingDocumentInput[]
): Promise<InfoRequestDto> {
  return apiFetchJson(
    `${BASE}/info-requests/${infoRequestId}/respond`,
    jsonInit("POST", { message, supportingDocuments }),
    "Could not send your answer."
  )
}

export async function declineInfoRequest(infoRequestId: string, reason: string): Promise<InfoRequestDto> {
  return apiFetchJson(`${BASE}/info-requests/${infoRequestId}/decline`, jsonInit("POST", { reason }), "Could not decline the request.")
}

export async function withdrawInfoRequest(infoRequestId: string, reason?: string): Promise<InfoRequestDto> {
  return apiFetchJson(
    `${BASE}/info-requests/${infoRequestId}/withdraw`,
    jsonInit("POST", { reason: reason || null }),
    "Could not withdraw the request."
  )
}

export async function reassignInfoRequest(infoRequestId: string, requestedFromUserId: string, reason?: string): Promise<InfoRequestDto> {
  return apiFetchJson(
    `${BASE}/info-requests/${infoRequestId}/reassign`,
    jsonInit("POST", { requestedFromUserId, reason: reason || null }),
    "Could not redirect the request."
  )
}

/** "Requests for me", newest first. status: open | answered | declined | cancelled | all. */
export async function fetchMyInfoRequests(status: string, page: number, size: number): Promise<PageDto<InfoRequestDto>> {
  const params = new URLSearchParams({ status, page: String(page), size: String(size) })
  return apiFetchJson(`${BASE}/info-requests/my?${params.toString()}`, undefined, "Could not load your requests.")
}

// ------------------------------------------------- supporting documents (Phase 2)

export async function addSupportingDocument(instanceId: string, input: SupportingDocumentInput): Promise<SupportingDocumentDto> {
  return apiFetchJson(
    `${BASE}/instances/${instanceId}/supporting-documents`,
    jsonInit("POST", input),
    "Could not attach the document."
  )
}

export async function removeSupportingDocument(instanceId: string, supportingDocumentId: string): Promise<void> {
  await apiFetch(
    `${BASE}/instances/${instanceId}/supporting-documents/${supportingDocumentId}`,
    { method: "DELETE" },
    "Could not remove the document."
  )
}

/**
 * The supporting document's PINNED version bytes (decision 17) - what was attached, not the
 * document's current version. Served to anyone who can see the run and open the document; no
 * dvc.versions.view needed.
 */
export async function fetchSupportingDocumentContent(instanceId: string, supportingDocumentId: string): Promise<Blob> {
  const response = await apiFetch(
    `${BASE}/instances/${instanceId}/supporting-documents/${supportingDocumentId}/content`,
    undefined,
    "Could not open this supporting document."
  )
  return response.blob()
}
