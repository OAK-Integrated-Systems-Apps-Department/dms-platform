import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson, apiFetchVoid } from "@/lib/api-fetch"
import type { DownloadedFile } from "@/lib/download-file"

import type { ReportDefinition, ReportFilter } from "./definition"

const BASE = `${API_BASE_URL}/api/v1/reports`
const JSON_HEADERS = { "Content-Type": "application/json" }

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  headers: JSON_HEADERS,
  body: body === undefined ? undefined : JSON.stringify(body),
})

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

function query(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") q.set(key, String(value))
  }
  const text = q.toString()
  return text ? `?${text}` : ""
}

// ---------------------------------------------------------------- catalogue

export type FieldType = "text" | "number" | "date" | "boolean" | "choice" | "reference"

/** Mirrors CatalogResponse.Field. */
export interface CatalogField {
  key: string
  label: string
  type: FieldType
  operators: string[]
  multi: boolean
  metadata: boolean
  selectable: boolean
  filterable: boolean
  sortable: boolean
  groupable: boolean
  defaultSelected: boolean
}

export interface CatalogLimits {
  maxFilters: number
  maxColumns: number
  maxMetadataFilters: number
  maxSortKeys: number
  maxRows: number
  maxPdfRows: number
}

export interface CatalogSettings {
  allowedFormats: string[]
  defaultFormat: string
  overLimitBehaviour: string
  defaultTimeZone: string
  includeInactiveByDefault: boolean
  defaultColumns: string[]
  defaultSort: { field: string; direction: string }[]
  limits: CatalogLimits
  schedulingEnabled: boolean
  pauseAfterConsecutiveFailures?: number
  minScheduleInterval: string
  allowedDeliveryChannels: string[]
  deliveryModeDefault: string
  allowAttachments: boolean
  maxAttachmentMb: number
  allowExternalRecipients: boolean
  maxRecipientsPerSchedule: number
  showFilterSummary: boolean
}

export interface Capabilities {
  build: boolean
  run: boolean
  schedule: boolean
  publish: boolean
  configure: boolean
  viewAllRuns: boolean
}

export interface CatalogDto {
  fields: CatalogField[]
  settings: CatalogSettings
  capabilities: Capabilities
  relativeDateOptions: string[]
  maxRelativeDays: number
}

export const fetchCatalog = (): Promise<CatalogDto> =>
  apiFetchJson(`${BASE}/catalog`, undefined, "Could not load the report fields.")

export interface FieldValue {
  value: string
  label: string
}
export interface FieldValuesPage {
  content: FieldValue[]
  page: number
  size: number
  hasMore: boolean
}

export const fetchFieldValues = (key: string, q: string, page: number, size = 25): Promise<FieldValuesPage> =>
  apiFetchJson(
    `${BASE}/catalog/fields/${encodeURIComponent(key)}/values${query({ q, page, size })}`,
    undefined,
    "Could not load the choices."
  )

// ---------------------------------------------------------------- preview

export interface PreviewDto {
  total: number | null
  columns: { key: string; label: string; type: string }[]
  sample: string[][]
  limitedToAccess: boolean
  resolvedFilters: string[]
  timedOut: boolean
  timeZone: string
  maxRows: number
  maxPdfRows: number
}

export const previewReport = (definition: ReportDefinition, signal?: AbortSignal): Promise<PreviewDto> =>
  apiFetchJson(`${BASE}/preview`, { ...json("POST", definition), signal }, "Could not preview the report.")

// ---------------------------------------------------------------- runs

export type RunStatus = "queued" | "running" | "completed" | "failed" | "cancelled" | "expired"

/** Mirrors RunResponse. */
export interface RunDto {
  runId: string
  status: RunStatus
  format: string
  trigger: string
  templateId: string | null
  title: string | null
  rowCount: number | null
  truncated: boolean
  progressRows: number
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  expiresAt: string | null
  downloadable: boolean
}

export interface RunRequest {
  templateId?: string
  definition?: ReportDefinition
  format: string
  filterOverrides?: ReportFilter[]
}

export const startRun = (request: RunRequest): Promise<RunDto> =>
  apiFetchJson(`${BASE}/runs`, json("POST", request), "Could not start the report.")

export const fetchRuns = (params: { status?: string; templateId?: string; page?: number; size?: number }): Promise<Page<RunDto>> =>
  apiFetchJson(`${BASE}/runs${query(params)}`, undefined, "Could not load your report runs.")

export const fetchRun = (runId: string): Promise<RunDto> =>
  apiFetchJson(`${BASE}/runs/${runId}`, undefined, "Could not check on the report.")

export const cancelRun = (runId: string): Promise<RunDto> =>
  apiFetchJson(`${BASE}/runs/${runId}/cancel`, json("POST"), "Could not cancel the report.")

export const retryRun = (runId: string): Promise<RunDto> =>
  apiFetchJson(`${BASE}/runs/${runId}/retry`, json("POST"), "Could not retry the report.")

/** The finished file, fetched through the session (not a bare link) so the access check runs. */
export async function downloadRun(runId: string): Promise<DownloadedFile> {
  const response = await apiFetch(`${BASE}/runs/${runId}/download`, undefined, "Could not download the report.")
  const disposition = response.headers.get("Content-Disposition") ?? ""
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)
  return { blob: await response.blob(), filename: match ? decodeURIComponent(match[1]) : "report" }
}

// ---------------------------------------------------------------- templates

export interface Grant {
  principalType: "user" | "role" | "department"
  principalId: string
  accessLevel: "run" | "edit"
}

/** Mirrors TemplateResponse. */
export interface TemplateDto {
  id: string
  name: string
  description: string | null
  scope: "private" | "shared" | "official"
  ownerUserId: string
  ownerName: string | null
  definition: ReportDefinition
  locked: boolean
  userEditableFilters: string[] | null
  access: "owner" | "edit" | "run"
  warnings: string[] | null
  grants: Grant[] | null
  activeSchedules: number
  createdAt: string
  updatedAt: string | null
}

export interface TemplateRequest {
  name: string
  description: string | null
  definition: ReportDefinition
}

export const fetchTemplates = (): Promise<TemplateDto[]> =>
  apiFetchJson(`${BASE}/templates`, undefined, "Could not load your reports.")

export const fetchTemplate = (id: string): Promise<TemplateDto> =>
  apiFetchJson(`${BASE}/templates/${id}`, undefined, "Could not load the report.")

export const createTemplate = (request: TemplateRequest): Promise<TemplateDto> =>
  apiFetchJson(`${BASE}/templates`, json("POST", request), "Could not save the report.")

export const updateTemplate = (id: string, request: TemplateRequest): Promise<TemplateDto> =>
  apiFetchJson(`${BASE}/templates/${id}`, json("PUT", request), "Could not save the report.")

export const deleteTemplate = (id: string): Promise<void> =>
  apiFetchVoid(`${BASE}/templates/${id}`, { method: "DELETE" }, "Could not delete the report.")

export const duplicateTemplate = (id: string): Promise<TemplateDto> =>
  apiFetchJson(`${BASE}/templates/${id}/duplicate`, json("POST"), "Could not duplicate the report.")

export const shareTemplate = (id: string, grants: Grant[]): Promise<TemplateDto> =>
  apiFetchJson(`${BASE}/templates/${id}/share`, json("POST", { grants }), "Could not share the report.")

export interface PublishRequest {
  locked: boolean
  userEditableFilters: string[]
  audience: Grant[]
}

export const publishTemplate = (id: string, request: PublishRequest): Promise<TemplateDto> =>
  apiFetchJson(`${BASE}/templates/${id}/publish`, json("POST", request), "Could not publish the report.")

export const unpublishTemplate = (id: string): Promise<TemplateDto> =>
  apiFetchJson(`${BASE}/templates/${id}/unpublish`, json("POST"), "Could not unpublish the report.")

// ---------------------------------------------------------------- schedules

export interface Recipient {
  type: "user" | "role" | "department" | "email"
  ref: string
}

export type Frequency = "hourly" | "daily" | "weekly" | "monthly"

/** Mirrors ScheduleRequest. `timeOfDay` is "HH:mm". */
export interface ScheduleRequest {
  templateId: string
  name: string
  frequency: Frequency
  daysOfWeek?: number[]
  dayOfMonth?: number | null
  timeOfDay: string
  timeZone: string
  startDate?: string | null
  endDate?: string | null
  format: string
  deliveryChannels: string[]
  deliveryMode?: string | null
  recipients: Recipient[]
}

/** Mirrors ScheduleResponse. */
export interface ScheduleDto extends Omit<ScheduleRequest, "templateId"> {
  id: string
  templateId: string
  templateName: string | null
  ownerUserId: string
  status: "active" | "paused" | "cancelled" | "ended"
  pauseReason: string | null
  nextRunAt: string | null
  lastRunAt: string | null
  consecutiveFailures: number
  lastFailureCode: string | null
  createdAt: string
}

export const fetchSchedules = (): Promise<ScheduleDto[]> =>
  apiFetchJson(`${BASE}/schedules`, undefined, "Could not load your schedules.")

export const createSchedule = (request: ScheduleRequest): Promise<ScheduleDto> =>
  apiFetchJson(`${BASE}/schedules`, json("POST", request), "Could not save the schedule.")

export const updateSchedule = (id: string, request: ScheduleRequest): Promise<ScheduleDto> =>
  apiFetchJson(`${BASE}/schedules/${id}`, json("PUT", request), "Could not save the schedule.")

export const cancelSchedule = (id: string): Promise<void> =>
  apiFetchVoid(`${BASE}/schedules/${id}`, { method: "DELETE" }, "Could not cancel the schedule.")

export const pauseSchedule = (id: string): Promise<void> =>
  apiFetchVoid(`${BASE}/schedules/${id}/pause`, json("POST"), "Could not pause the schedule.")

export const resumeSchedule = (id: string): Promise<void> =>
  apiFetchVoid(`${BASE}/schedules/${id}/resume`, json("POST"), "Could not resume the schedule.")

export const runScheduleNow = (id: string): Promise<{ runIds: string[] }> =>
  apiFetchJson(`${BASE}/schedules/${id}/run-now`, json("POST"), "Could not run the schedule.")

export interface ScheduleRunDto {
  run: RunDto
  delivered: boolean | null
  deliveryNote: string | null
  sent: number
  failed: number
  pending: number
  skipped: number
}

export const fetchScheduleRuns = (id: string): Promise<ScheduleRunDto[]> =>
  apiFetchJson(`${BASE}/schedules/${id}/runs`, undefined, "Could not load the schedule's history.")

// ---------------------------------------------------------------- administration

export interface AdminSettings {
  settings: Record<string, unknown>
  ceilings: Record<string, number | boolean>
}

export const fetchAdminSettings = (): Promise<AdminSettings> =>
  apiFetchJson(`${BASE}/admin/settings`, undefined, "Could not load the report settings.")

export const saveAdminSettings = (update: Record<string, unknown>): Promise<AdminSettings> =>
  apiFetchJson(`${BASE}/admin/settings`, json("PUT", update), "Could not save the report settings.")

export interface FieldOverride {
  fieldKey: string
  enabled?: boolean | null
  labelOverride?: string | null
  selectable?: boolean | null
  filterable?: boolean | null
  sortable?: boolean | null
  groupable?: boolean | null
  defaultSelected?: boolean | null
  displayOrder?: number | null
  showCode?: boolean | null
}

export interface AdminField {
  key: string
  label: string
  type: string
  custom: boolean
  canSelect: boolean
  canFilter: boolean
  canSort: boolean
  canGroup: boolean
  defaultSelected: boolean
  override: FieldOverride | null
  /** Official templates and live schedules whose definition uses the field (counts only). */
  officialTemplatesUsing?: number
  schedulesUsing?: number
}

export const fetchAdminFields = (): Promise<AdminField[]> =>
  apiFetchJson(`${BASE}/admin/fields`, undefined, "Could not load the fields.")

export const saveAdminFields = (overrides: FieldOverride[]): Promise<AdminField[]> =>
  apiFetchJson(`${BASE}/admin/fields`, json("PUT", { overrides }), "Could not save the fields.")

/**
 * A one-page sample file (5 invented rows, no organisation data) in the organisation's current output settings. The settings sent are
 * the ones on screen, not yet saved, so an administrator sees the effect before committing.
 */
export async function generateSample(format: "pdf" | "csv", unsaved: Record<string, unknown>): Promise<DownloadedFile> {
  const response = await apiFetch(`${BASE}/admin/settings/sample?format=${format}`, json("POST", unsaved), "Could not make the sample.")
  const disposition = response.headers.get("Content-Disposition") ?? ""
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)
  return { blob: await response.blob(), filename: match ? decodeURIComponent(match[1]) : `sample.${format}` }
}

export interface AdminScheduleDto {
  id: string
  name: string
  ownerUserId: string
  ownerName: string | null
  templateId: string
  templateName: string | null
  frequency: string
  timeZone: string
  format: string
  status: string
  pauseReason: string | null
  nextRunAt: string | null
  lastRunAt: string | null
  consecutiveFailures: number
  lastFailureCode: string | null
  recipientCount: number
}

export const fetchAdminSchedules = (params: { status?: string; page?: number; size?: number }): Promise<Page<AdminScheduleDto>> =>
  apiFetchJson(`${BASE}/admin/schedules${query(params)}`, undefined, "Could not load the schedules.")

export const adminScheduleAction = (id: string, action: "pause" | "resume" | "cancel"): Promise<void> =>
  apiFetchVoid(`${BASE}/admin/schedules/${id}/${action}`, json("POST"), `Could not ${action} the schedule.`)

export const adminReassignSchedule = (id: string, newOwnerUserId: string): Promise<AdminScheduleDto> =>
  apiFetchJson(`${BASE}/admin/schedules/${id}/reassign`, json("POST", { newOwnerUserId }), "Could not hand over the schedule.")
