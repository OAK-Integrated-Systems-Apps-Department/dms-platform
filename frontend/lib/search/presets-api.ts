import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"

import type { PresetCriteria, PresetDto, PresetListDto, PresetRunDto } from "./presets"

const BASE = `${API_BASE_URL}/api/v1/search/presets`
const JSON_HEADERS = { "Content-Type": "application/json" }

export function fetchPresets(): Promise<PresetListDto> {
  return apiFetchJson(BASE, undefined, "Could not load your saved presets.")
}

export function fetchPreset(presetId: string): Promise<PresetDto> {
  return apiFetchJson(`${BASE}/${presetId}`, undefined, "Could not load the preset.")
}

/** Saves the criteria under a name. `overwrite` replaces the criteria of the preset that already has that name. */
export function createPreset(input: {
  name: string
  criteria: PresetCriteria
  overwrite?: boolean
}): Promise<PresetDto> {
  return apiFetchJson(
    BASE,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(input) },
    "Could not save the preset."
  )
}

export function updatePreset(
  presetId: string,
  input: { name?: string; criteria?: PresetCriteria }
): Promise<PresetDto> {
  return apiFetchJson(
    `${BASE}/${presetId}`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(input) },
    "Could not update the preset."
  )
}

export async function deletePreset(presetId: string): Promise<void> {
  await apiFetch(`${BASE}/${presetId}`, { method: "DELETE" }, "Could not delete the preset.")
}

/** Runs a preset: its criteria with outdated filters dropped (and named in `warnings`), plus the first page of results. */
export function runPreset(presetId: string, size?: number): Promise<PresetRunDto> {
  return apiFetchJson(
    `${BASE}/${presetId}/run`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(size ? { size } : {}) },
    "Could not run the preset."
  )
}

export const PREVIEW_FALLBACK_REASONS = ["unsupported", "failed", "timeout", "policy_blocked"] as const
export type PreviewFallbackReasonDto = (typeof PREVIEW_FALLBACK_REASONS)[number]

/**
 * Tells the server a preview opened from search fell back (a counter, nothing else is sent or stored). Fire and forget:
 * a failure here, including the rate limit, must never reach the person.
 */
export function reportPreviewFallback(reason: PreviewFallbackReasonDto): void {
  void apiFetch(
    `${API_BASE_URL}/api/v1/search/telemetry/preview`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ reason }) },
    "ignored"
  ).catch(() => {})
}
