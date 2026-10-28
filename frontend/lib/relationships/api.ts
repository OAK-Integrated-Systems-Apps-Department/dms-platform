import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import { ApiError } from "@/lib/api-error"

import type {
  BulkCreateRelationshipsRequestDto,
  BulkErrorDto,
  BulkRelationshipsResponseDto,
  ChainItemDto,
  CreateRelationshipRequestDto,
  CreateRelationshipTypeRequestDto,
  RelatedDocumentsDto,
  RelationshipCountsDto,
  RelationshipDto,
  RelationshipTypeDto,
  UpdateRelationshipRequestDto,
  UpdateRelationshipTypeRequestDto,
} from "@/lib/relationships/types"

const TYPES = `${API_BASE_URL}/api/v1/relationship-types`
const LINKS = `${API_BASE_URL}/api/v1/document-relationships`
const JSON_HEADERS = { "Content-Type": "application/json" }

/** `includeDisabled` adds disabled types and the usage count, and needs a configure-types permission. */
export async function fetchRelationshipTypes(includeDisabled = false): Promise<RelationshipTypeDto[]> {
  return apiFetchJson(
    includeDisabled ? `${TYPES}?includeDisabled=true` : TYPES,
    undefined,
    "Could not load the relationship types."
  )
}

export async function createRelationshipType(body: CreateRelationshipTypeRequestDto): Promise<RelationshipTypeDto> {
  return apiFetchJson(
    TYPES,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(body) },
    "Could not create the relationship type."
  )
}

export async function updateRelationshipType(
  typeId: string,
  body: UpdateRelationshipTypeRequestDto
): Promise<RelationshipTypeDto> {
  return apiFetchJson(
    `${TYPES}/${typeId}`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(body) },
    "Could not update the relationship type."
  )
}

export async function deleteRelationshipType(typeId: string): Promise<void> {
  await apiFetch(`${TYPES}/${typeId}`, { method: "DELETE" }, "Could not delete the relationship type.")
}

export async function fetchDocumentRelationships(documentId: string): Promise<RelatedDocumentsDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/documents/${documentId}/relationships`,
    undefined,
    "Could not load the related documents."
  )
}

export async function createRelationship(body: CreateRelationshipRequestDto): Promise<RelationshipDto> {
  return apiFetchJson(
    LINKS,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(body) },
    "Could not link the documents."
  )
}

export async function createRelationshipsBulk(
  body: BulkCreateRelationshipsRequestDto
): Promise<BulkRelationshipsResponseDto> {
  return apiFetchJson(
    `${LINKS}/bulk`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(body) },
    "Could not link the documents."
  )
}

export async function updateRelationship(id: string, body: UpdateRelationshipRequestDto): Promise<RelationshipDto> {
  return apiFetchJson(
    `${LINKS}/${id}`,
    { method: "PATCH", headers: JSON_HEADERS, body: JSON.stringify(body) },
    "Could not change the link."
  )
}

export async function removeRelationship(id: string): Promise<void> {
  await apiFetch(`${LINKS}/${id}`, { method: "DELETE" }, "Could not remove the link.")
}

export async function fetchRelationshipCounts(documentIds: string[]): Promise<RelationshipCountsDto> {
  return apiFetchJson(
    `${LINKS}/counts`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ documentIds }) },
    "Could not load the relationship counts."
  )
}

/** The loop chain of a RELATIONSHIP_CYCLE error, if the backend sent one. */
export function cycleChainOf(error: unknown): ChainItemDto[] {
  if (!(error instanceof ApiError) || error.code !== "RELATIONSHIP_CYCLE") return []
  const chain = (error.details as { chain?: ChainItemDto[] } | undefined)?.chain
  return Array.isArray(chain) ? chain : []
}

/** The per-document reasons of a RELATIONSHIP_BULK_INVALID error, if the backend sent them. */
export function bulkErrorsOf(error: unknown): BulkErrorDto[] {
  if (!(error instanceof ApiError) || error.code !== "RELATIONSHIP_BULK_INVALID") return []
  return Array.isArray(error.details) ? (error.details as BulkErrorDto[]) : []
}
