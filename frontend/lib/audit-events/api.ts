import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

/** Mirrors backend/dms-core's AuditEventResponse DTO (the list/search shape). */
export interface AuditEventResponseDto {
  id: string
  tenantId: string | null
  actorId: string | null
  actorType: "USER" | "SYSTEM" | "API_KEY"
  actorDisplayName: string | null
  impersonatedBy: string | null
  impersonatedByDisplayName: string | null
  action: string
  module: string
  objectType: string
  objectId: string | null
  resourceName: string | null
  objectVersion: number | null
  outcome: "SUCCESS" | "FAILURE" | "DENIED"
  failureReason: string | null
  occurredAt: string
  reason: string | null
  sessionId: string | null
  authMethod: "PASSWORD" | "MFA" | "SSO" | null
  permissionCode: string | null
  dataClassification: string | null
  legalHoldFlag: boolean | null
  ipAddress: string | null
  clientType: "WEB" | "MOBILE" | "API" | "ADMIN_CLI" | null
  correlationId: string | null
  requestId: string | null
  severity: "LOW" | "MEDIUM" | "HIGH" | null
  recordHash: string
  previousHash: string | null
}

/**
 * Mirrors backend/dms-core's AuditEventDetailResponse DTO - everything in
 * AuditEventResponseDto plus the three fields deliberately left out of the
 * list response (see that DTO's own backend doc comment): the full
 * before/after snapshots and the raw user agent string.
 */
export interface AuditEventDetailResponseDto extends AuditEventResponseDto {
  previousValue: unknown | null
  newValue: unknown | null
  userAgent: string | null
}

/** Mirrors backend/dms-core's PageResponse<AuditEventResponse> envelope. */
export interface AuditEventPageResponseDto {
  content: AuditEventResponseDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/**
 * Every filter GET /api/v1/audit-events (and its /export sibling) accepts -
 * all optional, combined server-side with AND. modules/actions/actorIds/
 * objectTypes are arrays - an event matches if its own value is any ONE of
 * the given values (an OR within that field), mirroring the backend's
 * AuditEventSearchCriteria/AuditEventSpecifications. Each is sent as a
 * repeated query param under the same (singular) name, e.g.
 * `?module=ASM&module=OTUA`, which Spring binds to a `List<String>`.
 */
export interface AuditEventSearchFilters {
  modules?: string[]
  actions?: string[]
  actorIds?: string[]
  objectTypes?: string[]
  objectId?: string
  outcome?: AuditEventResponseDto["outcome"]
  severity?: AuditEventResponseDto["severity"]
  /** ISO-8601 date-time, inclusive lower bound on occurredAt. */
  occurredFrom?: string
  /** ISO-8601 date-time, inclusive upper bound on occurredAt. */
  occurredTo?: string
  /** Narrows to every event sharing this one correlation_id - how the detail page's "Part of operation" link pivots into a filtered list. */
  correlationId?: string
}

function buildQuery(
  filters: AuditEventSearchFilters,
  extra: Record<string, string | number> = {}
): string {
  const params = new URLSearchParams()
  filters.modules?.forEach((value) => params.append("module", value))
  filters.actions?.forEach((value) => params.append("action", value))
  filters.actorIds?.forEach((value) => params.append("actorId", value))
  filters.objectTypes?.forEach((value) => params.append("objectType", value))
  if (filters.objectId) params.set("objectId", filters.objectId)
  if (filters.outcome) params.set("outcome", filters.outcome)
  if (filters.severity) params.set("severity", filters.severity)
  if (filters.occurredFrom) params.set("occurredFrom", filters.occurredFrom)
  if (filters.occurredTo) params.set("occurredTo", filters.occurredTo)
  if (filters.correlationId) params.set("correlationId", filters.correlationId)
  for (const [key, value] of Object.entries(extra)) {
    params.set(key, String(value))
  }
  return params.toString()
}

/**
 * One page of `GET /api/v1/audit-events`. Unlike `fetchUsersPage`
 * (lib/users/api.ts), this is genuinely server-side filtered/paginated -
 * every filter and page change re-queries the backend rather than
 * fetching everything once and slicing client-side.
 */
export async function fetchAuditEvents(
  filters: AuditEventSearchFilters,
  page: number,
  size: number,
  sort: string
): Promise<AuditEventPageResponseDto> {
  const query = buildQuery(filters, { page, size, sort })
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/audit-events?${query}`,
    undefined,
    "Could not load audit events."
  )
}

export async function fetchAuditEventById(id: string): Promise<AuditEventDetailResponseDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/audit-events/${id}`,
    undefined,
    "Could not load this audit event."
  )
}

/**
 * Triggers a browser download of the filtered CSV export
 * (`GET /api/v1/audit-events/export`, same filters as the search above,
 * no pagination). There's no existing file-download pattern anywhere else
 * in this app to reuse - this reads the response as a Blob and clicks a
 * synthetic, off-DOM `<a download>`, then revokes the object URL once the
 * click has been dispatched.
 */
export async function exportAuditEventsCsv(filters: AuditEventSearchFilters): Promise<void> {
  const query = buildQuery(filters)
  const response = await apiFetch(
    `${API_BASE_URL}/api/v1/audit-events/export?${query}`,
    undefined,
    "Could not export the audit trail."
  )
  const blob = await response.blob()
  const filename = filenameFromContentDisposition(response.headers.get("Content-Disposition")) ?? "audit-log.csv"

  const url = URL.createObjectURL(blob)
  const link = document.createElement("a")
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

function filenameFromContentDisposition(header: string | null): string | null {
  if (!header) return null
  const match = /filename="?([^"]+)"?/.exec(header)
  return match ? match[1] : null
}
