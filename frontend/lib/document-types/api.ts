import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

/** Mirrors backend/dms-core's DocumentTypeResponse DTO. */
export interface DocumentTypeResponseDto {
  documentTypeId: string
  tenantId: string
  typeCode: string
  typeName: string
  description: string | null
  displayOrder: number
  currentSchemaVersion: number
  defaultRetentionPolicyId: string | null
  requiresWatermark: boolean
  coeditingEnabled: boolean
  status: "active" | "inactive"
  createdAt: string
  updatedAt: string | null
}

/** Mirrors backend/dms-core's PageResponse<DocumentTypeResponse> envelope. */
export interface DocumentTypePageResponseDto {
  content: DocumentTypeResponseDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

export interface CreateDocumentTypePayload {
  typeCode: string
  typeName: string
  description?: string
  displayOrder: number
  requiresWatermark: boolean
  coeditingEnabled: boolean
}

export interface UpdateDocumentTypePayload {
  typeName: string
  description?: string
  displayOrder: number
  requiresWatermark: boolean
  coeditingEnabled: boolean
}

const BASE = `${API_BASE_URL}/api/v1/document-types`

// Same "fetch everything in large pages, filter client-side" shape as
// lib/metadata-fields/api.ts and lib/users/api.ts's fetchAllUsers — the
// number of document types per tenant is expected to stay small.
const PAGE_FETCH_SIZE = 200

export async function fetchAllDocumentTypes(): Promise<DocumentTypeResponseDto[]> {
  const types: DocumentTypeResponseDto[] = []
  let page = 0
  while (true) {
    const response = await apiFetchJson<DocumentTypePageResponseDto>(
      `${BASE}?page=${page}&size=${PAGE_FETCH_SIZE}`,
      undefined,
      "Could not load document types."
    )
    types.push(...response.content)
    if (response.last) break
    page += 1
  }
  return types
}

export async function fetchDocumentType(documentTypeId: string): Promise<DocumentTypeResponseDto> {
  return apiFetchJson(`${BASE}/${documentTypeId}`, undefined, "Could not load this document type.")
}

export async function createDocumentType(payload: CreateDocumentTypePayload): Promise<DocumentTypeResponseDto> {
  return apiFetchJson(
    BASE,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    "Could not create this document type."
  )
}

export async function updateDocumentType(
  documentTypeId: string,
  payload: UpdateDocumentTypePayload
): Promise<DocumentTypeResponseDto> {
  return apiFetchJson(
    `${BASE}/${documentTypeId}`,
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    "Could not save changes."
  )
}

export async function activateDocumentType(documentTypeId: string): Promise<DocumentTypeResponseDto> {
  return apiFetchJson(`${BASE}/${documentTypeId}/activate`, { method: "POST" }, "Could not activate this document type.")
}

export async function deactivateDocumentType(documentTypeId: string): Promise<DocumentTypeResponseDto> {
  return apiFetchJson(`${BASE}/${documentTypeId}/deactivate`, { method: "POST" }, "Could not deactivate this document type.")
}
