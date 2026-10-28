import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import type { DocumentListItemDto } from "@/lib/documents/api"

const BASE = `${API_BASE_URL}/api/v1/pins`

/** Mirrors PinService.PinnedDocument. */
export interface PinnedDocumentDto {
  /** The same row the document browser shows. */
  document: DocumentListItemDto
  pinnedAt: string
  /** Still readable, but archived - shown flagged rather than removed. */
  archived: boolean
}

/** Mirrors PinService.Pins. */
export interface PinsDto {
  /** How many documents one person may pin in this organisation. */
  limit: number
  /** Newest pin first. */
  pins: PinnedDocumentDto[]
}

export async function fetchPins(): Promise<PinsDto> {
  return apiFetchJson(BASE, undefined, "Could not load your pinned documents.")
}

export async function pinDocument(documentId: string): Promise<void> {
  await apiFetch(`${BASE}/${documentId}`, { method: "PUT" }, "Could not pin the document.")
}

export async function unpinDocument(documentId: string): Promise<void> {
  await apiFetch(`${BASE}/${documentId}`, { method: "DELETE" }, "Could not unpin the document.")
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
