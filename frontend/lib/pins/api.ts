import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import type { DocumentListItemDto } from "@/lib/documents/api"

const BASE = `${API_BASE_URL}/api/v1/pins`

/**
 * Who sees a pin. `ALL`: everyone except the people named. `USERS`: only the people named.
 * Mirrors PinService.AudienceMode.
 */
export type PinAudienceMode = "ALL" | "USERS"

/** A person an audience names. Mirrors PinService.Member. */
export interface PinMemberDto {
  userId: string
  displayName: string
  email: string
}

/** Mirrors PinService.AudienceSummary. */
export interface PinAudienceSummaryDto {
  mode: PinAudienceMode
  /** How many people it names - exclusions for ALL, inclusions for USERS. */
  count: number
}

/** What a pinner chooses. `userIds` are exclusions for ALL and inclusions for USERS. */
export interface PinAudienceInput {
  mode: PinAudienceMode
  userIds: string[]
}

/** Mirrors PinService.PinnedDocument. */
export interface PinnedDocumentDto {
  /** The same row the document browser shows. */
  document: DocumentListItemDto
  pinnedAt: string
  /** Still readable, but archived - shown flagged rather than removed. */
  archived: boolean
  pinnedBy: string
  pinnedByName: string
  /** Pinned by the signed-in user - the only pins they can change or unpin. */
  mine: boolean
  /** Who the pinner chose; only present on the caller's own pins. */
  audience: PinAudienceSummaryDto | null
}

/** Mirrors PinService.Pins. */
export interface PinsDto {
  /** How many documents one person may pin in this organisation. */
  limit: number
  /** Newest pin first. */
  pins: PinnedDocumentDto[]
}

/** Mirrors PinService.Audience: the caller's own audience, with the people it names. */
export interface PinAudienceDto {
  mode: PinAudienceMode
  members: PinMemberDto[]
}

export async function fetchPins(): Promise<PinsDto> {
  return apiFetchJson(BASE, undefined, "Could not load your pinned documents.")
}

/** Pins a document for an audience, or changes the audience of the caller's own pin on it. */
export async function pinDocument(
  documentId: string,
  audience: PinAudienceInput
): Promise<void> {
  await apiFetch(
    `${BASE}/${documentId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(audience),
    },
    "Could not pin the document."
  )
}

/** Removes the caller's own pin, for everyone it was shown to. */
export async function unpinDocument(documentId: string): Promise<void> {
  await apiFetch(
    `${BASE}/${documentId}`,
    { method: "DELETE" },
    "Could not unpin the document."
  )
}

export async function fetchPinAudience(
  documentId: string
): Promise<PinAudienceDto> {
  return apiFetchJson(
    `${BASE}/${documentId}/audience`,
    undefined,
    "Could not load who this is pinned for."
  )
}

/** People to choose from when picking an audience; needs only the pin permission. */
export async function searchPinCandidates(
  query: string,
  limit = 50
): Promise<PinMemberDto[]> {
  const params = new URLSearchParams({ q: query, limit: String(limit) })
  return apiFetchJson(
    `${BASE}/audience-candidates?${params}`,
    undefined,
    "Could not search for people."
  )
}

export async function savePinLimit(maxPins: number): Promise<void> {
  await apiFetch(
    `${BASE}/limit`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ maxPins }),
    },
    "Could not save the pin limit."
  )
}
