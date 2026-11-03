import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson, apiFetchVoid } from "@/lib/api-fetch"

const DOCUMENTS = `${API_BASE_URL}/api/v1/documents`
const RETENTION = `${API_BASE_URL}/api/v1/retention`
const JSON_HEADERS = { "Content-Type": "application/json" }

/** Where a document is in its life: normal, switched off by hand, or archived by its retention rule. */
export type LifecycleState = "active" | "inactive" | "archived"
export type RetentionUnit = "days" | "months" | "years"
export type PolicyScope = "organisation" | "document_type" | "department" | "folder"

/** Mirrors backend/dms-core's DocumentLifecycleService.RetentionView - the document page's retention panel. */
export interface RetentionViewDto {
  lifecycleState: LifecycleState
  changeSource: "manual" | "retention" | "legal_hold" | null
  changeReason: string | null
  changedAt: string | null
  changedByName: string | null
  legalHold: boolean
  legalHoldReason: string | null
  policyId: string | null
  policyName: string | null
  framework: string | null
  /** e.g. "1 years" - the rule's own amount and unit. */
  activePeriod: string | null
  archivePeriod: string | null
  archiveDueAt: string | null
  reviewDueAt: string | null
  reviewFlaggedAt: string | null
  extendedUntil: string | null
  needsPolicy: boolean
  automationEnabled: boolean
}

/** Mirrors RetentionPolicyService.PolicyView. */
export interface RetentionPolicyDto {
  policyId: string
  name: string
  description: string | null
  scopeType: PolicyScope
  scopeId: string | null
  scopeName: string
  includeSubfolders: boolean
  activeAmount: number
  activeUnit: RetentionUnit
  archiveAmount: number
  archiveUnit: RetentionUnit
  clockStart: "created" | "last_modified"
  framework: string | null
  enabled: boolean
  createdAt: string
  updatedAt: string | null
  /** Keep at most this many versions of a document; null = no limit. */
  maxVersions: number | null
  /** Remove superseded versions older than this; both null = no age limit. */
  versionAgeAmount: number | null
  versionAgeUnit: RetentionUnit | null
}

/** What an administrator edits (RetentionPolicyService.PolicyRequest). */
export interface RetentionPolicyRequest {
  name: string
  description: string | null
  scopeType: PolicyScope
  scopeId: string | null
  includeSubfolders: boolean
  activeAmount: number
  activeUnit: RetentionUnit
  archiveAmount: number
  archiveUnit: RetentionUnit
  clockStart: "created" | "last_modified"
  framework: string | null
  enabled: boolean
  maxVersions: number | null
  versionAgeAmount: number | null
  versionAgeUnit: RetentionUnit | null
}

export interface ScopeOptionDto {
  id: string
  name: string
  /** Folders only - so the picker can show a path. */
  parentId: string | null
}

/** What a rule can be pointed at (RetentionPolicyService.Scopes). */
export interface RetentionScopesDto {
  documentTypes: ScopeOptionDto[]
  departments: ScopeOptionDto[]
  folders: ScopeOptionDto[]
}

/** Mirrors RetentionSettingsService.Settings. */
export interface RetentionSettingsDto {
  automationEnabled: boolean
  precedence: "longest" | "most_specific"
  whenNoPolicy: "flag" | "ignore"
  requireStatusReason: boolean
  /** How long a version removed by retention stays hidden and restorable before its files are destroyed. */
  versionGraceDays: number
}

/** Mirrors DocumentLifecycleService.ReviewItem - a document waiting for an administrator. */
export interface ReviewItemDto {
  documentId: string
  title: string
  documentTypeId: string
  documentTypeName: string | null
  folderId: string | null
  lifecycleState: LifecycleState
  policyName: string | null
  framework: string | null
  reviewDueAt: string | null
  flaggedAt: string | null
}

export interface ReviewPageDto {
  content: ReviewItemDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** What one retention run did (RetentionEngine.Result). */
export interface RetentionRunDto {
  archived: number
  flagged: number
  withoutPolicy: number
  versionsRemoved: number
  versionsDestroyed: number
  failed: number
}

/** Mirrors VersionRetentionService.RemovedVersion - an old version hidden by retention, still restorable. */
export interface RemovedVersionDto {
  versionId: string
  documentId: string
  documentTitle: string
  versionNumber: number
  fileName: string
  sizeBytes: number
  removedAt: string
  /** When its files are destroyed for good unless it is restored first. */
  destroysAt: string
}

export interface RemovedVersionPageDto {
  content: RemovedVersionDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** `GET /api/v1/documents/{id}/retention` */
export function fetchDocumentRetention(documentId: string): Promise<RetentionViewDto> {
  return apiFetchJson(`${DOCUMENTS}/${documentId}/retention`, undefined, "Could not load this document's retention.")
}

/** `PUT /api/v1/documents/{id}/status` - 409 DOCUMENT_ON_LEGAL_HOLD names the hold and its reason. */
export function changeDocumentStatus(
  documentId: string,
  status: "active" | "inactive",
  reason: string
): Promise<RetentionViewDto> {
  return apiFetchJson(
    `${DOCUMENTS}/${documentId}/status`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify({ status, reason: reason.trim() || null }) },
    "Could not change the status."
  )
}

/** `POST /api/v1/documents/{id}/retention/extend` - reason required. */
export function extendRetention(
  documentId: string,
  amount: number,
  unit: RetentionUnit,
  reason: string
): Promise<RetentionViewDto> {
  return apiFetchJson(
    `${DOCUMENTS}/${documentId}/retention/extend`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ amount, unit, reason }) },
    "Could not extend the retention."
  )
}

/** `POST /api/v1/documents/{id}/retention/dispose` - to the recycle bin, never a permanent deletion. */
export function disposeDocument(documentId: string, reason: string): Promise<void> {
  return apiFetchVoid(
    `${DOCUMENTS}/${documentId}/retention/dispose`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ reason }) },
    "Could not dispose of the document."
  )
}

export function fetchRetentionPolicies(): Promise<RetentionPolicyDto[]> {
  return apiFetchJson(`${RETENTION}/policies`, undefined, "Could not load the retention rules.")
}

export function fetchRetentionScopes(): Promise<RetentionScopesDto> {
  return apiFetchJson(`${RETENTION}/scopes`, undefined, "Could not load the choices for a retention rule.")
}

export function createRetentionPolicy(request: RetentionPolicyRequest): Promise<RetentionPolicyDto> {
  return apiFetchJson(
    `${RETENTION}/policies`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(request) },
    "Could not create the rule."
  )
}

export function updateRetentionPolicy(policyId: string, request: RetentionPolicyRequest): Promise<RetentionPolicyDto> {
  return apiFetchJson(
    `${RETENTION}/policies/${policyId}`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(request) },
    "Could not save the rule."
  )
}

export function deleteRetentionPolicy(policyId: string): Promise<void> {
  return apiFetchVoid(`${RETENTION}/policies/${policyId}`, { method: "DELETE" }, "Could not delete the rule.")
}

export function fetchRetentionSettings(): Promise<RetentionSettingsDto> {
  return apiFetchJson(`${RETENTION}/settings`, undefined, "Could not load the retention settings.")
}

export function saveRetentionSettings(settings: RetentionSettingsDto): Promise<RetentionSettingsDto> {
  return apiFetchJson(
    `${RETENTION}/settings`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(settings) },
    "Could not save the retention settings."
  )
}

export function fetchReviewQueue(page: number, size: number): Promise<ReviewPageDto> {
  return apiFetchJson(`${RETENTION}/review?page=${page}&size=${size}`, undefined, "Could not load the review queue.")
}

export function fetchWithoutPolicy(page: number, size: number): Promise<ReviewPageDto> {
  return apiFetchJson(
    `${RETENTION}/without-policy?page=${page}&size=${size}`,
    undefined,
    "Could not load the documents without a rule."
  )
}

/** `POST /api/v1/retention/run` - the same pass the scheduled job makes; safe to repeat. */
export function runRetentionNow(): Promise<RetentionRunDto> {
  return apiFetchJson(`${RETENTION}/run`, { method: "POST" }, "Could not run the retention rules.")
}

/** `GET /api/v1/retention/versions` - old versions removed by retention that can still be restored. */
export function fetchRemovedVersions(page: number, size: number): Promise<RemovedVersionPageDto> {
  return apiFetchJson(`${RETENTION}/versions?page=${page}&size=${size}`, undefined, "Could not load the removed versions.")
}

/** `POST /api/v1/retention/versions/{id}/restore` - also exempts it from further trimming. */
export function restoreRemovedVersion(versionId: string): Promise<void> {
  return apiFetchVoid(`${RETENTION}/versions/${versionId}/restore`, { method: "POST" }, "Could not restore the version.")
}
