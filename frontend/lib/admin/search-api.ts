import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson, apiFetchVoid } from "@/lib/api-fetch"

const BASE = `${API_BASE_URL}/api/v1/admin/search`
const JSON_HEADERS = { "Content-Type": "application/json" }

/** Mirrors SearchAdminService.Status. */
export interface SearchAdminStatusDto {
  engine: "up" | "down" | "red"
  searchEnabled: boolean
  indexReady: boolean
  backfillState: "none" | "running" | "done" | "failed"
  pendingJobs: number
  failedJobs: number
  lagSeconds: number
  generation: number
  lastReconciledAt: string | null
  gap: { missing: number; orphans: number } | null
  /** Searchable metadata fields changed since the last reindex. */
  reindexRecommended?: boolean
  reindexRunning?: boolean
  reconcileRunning?: boolean
}

export function fetchSearchAdminStatus(): Promise<SearchAdminStatusDto> {
  return apiFetchJson(`${BASE}/status`, undefined, "Could not load the search status.")
}

export function requestReindex(): Promise<{ generation: number }> {
  return apiFetchJson(`${BASE}/reindex`, { method: "POST" }, "Could not start the reindex.")
}

export function requestReconcile(): Promise<void> {
  return apiFetchVoid(`${BASE}/reconcile`, { method: "POST" }, "Could not start the check.")
}

export interface PresetLimitDto {
  maxPerUser: number
  defaultMax: number
}

export function fetchPresetLimit(): Promise<PresetLimitDto> {
  return apiFetchJson(`${BASE}/presets/limit`, undefined, "Could not load the preset limit.")
}

export function savePresetLimit(maxPerUser: number): Promise<{ maxPerUser: number }> {
  return apiFetchJson(
    `${BASE}/presets/limit`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify({ maxPerUser }) },
    "Could not save the preset limit."
  )
}
