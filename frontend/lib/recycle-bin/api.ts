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
}

export interface RecycleBinDto {
  retentionDays: number
  items: RecycleBinItemDto[]
}

export async function fetchRecycleBin(): Promise<RecycleBinDto> {
  return apiFetchJson(BASE, undefined, "Could not load the recycle bin.")
}

export async function restoreFromRecycleBin(documentId: string): Promise<void> {
  await apiFetch(
    `${BASE}/${documentId}/restore`,
    { method: "POST" },
    "Could not restore the document."
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
