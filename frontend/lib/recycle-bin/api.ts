import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

const BASE = `${API_BASE_URL}/api/v1/recycle-bin`

/** Mirrors RecycleBinService.RecycleBinItem. */
export interface RecycleBinItemDto {
  documentId: string
  title: string
  filename: string | null
  sizeBytes: number
  folderId: string | null
  /** null when not in a folder, or the folder was deleted since */
  folderName: string | null
  /** its folder was deleted - it can't be restored back into it */
  folderGone: boolean
  deletedAt: string
  deletedBy: string | null
  deletedByName: string | null
  /** when it is deleted for good */
  purgeAt: string
  /** a protected version - it is never purged */
  protectedVersion: boolean
  /** under legal hold - kept indefinitely, never auto-purged */
  legalHold: boolean
}

/** What the recycle bin holds in storage, across the whole organisation. */
export interface RecycleBinUsageDto {
  documents: number
  bytes: number
}

/** One document type's own period; null = the organisation's. */
export interface TypeRetentionDto {
  documentTypeId: string
  typeName: string
  retentionDays: number | null
}

export interface RecycleBinDto {
  retentionDays: number
  items: RecycleBinItemDto[]
  byType: TypeRetentionDto[]
  usage: RecycleBinUsageDto
}

/** How long deleted documents of one type are kept; null = follow the organisation's period. */
export async function saveTypeRetention(
  documentTypeId: string,
  retentionDays: number | null
): Promise<void> {
  await apiFetch(
    `${BASE}/settings/document-types/${documentTypeId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ retentionDays }),
    },
    "Could not save the period for that document type."
  )
}

export async function fetchRecycleBin(): Promise<RecycleBinDto> {
  return apiFetchJson(BASE, undefined, "Could not load the recycle bin.")
}

/** restoredToTopLevel: its folder no longer existed, so it came back at the top level. */
export async function restoreFromRecycleBin(
  documentId: string
): Promise<{ restoredToTopLevel: boolean }> {
  return apiFetchJson(
    `${BASE}/${documentId}/restore`,
    { method: "POST" },
    "Could not restore the document."
  )
}

/** Deletes everything the caller may purge; held or protected documents are skipped. */
export async function emptyRecycleBin(): Promise<{
  purged: number
  skipped: number
}> {
  return apiFetchJson(
    BASE,
    { method: "DELETE" },
    "Could not empty the recycle bin."
  )
}

/** Deletes it for good - its files and their encryption keys are destroyed. */
export async function purgeFromRecycleBin(documentId: string): Promise<void> {
  await apiFetch(
    `${BASE}/${documentId}`,
    { method: "DELETE" },
    "Could not delete the document permanently."
  )
}

export async function saveRecycleBinRetention(
  retentionDays: number
): Promise<{ retentionDays: number }> {
  return apiFetchJson(
    `${BASE}/settings`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ retentionDays }),
    },
    "Could not save how long deleted documents are kept."
  )
}
