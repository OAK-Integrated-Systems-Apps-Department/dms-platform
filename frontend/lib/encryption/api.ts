import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"

const BASE = `${API_BASE_URL}/api/v1/encryption`

/** Mirrors KeyEncryptionService.KeyInfo. */
export interface KeyInfoDto {
  /** "openbao" or "local-dev" */
  provider: string
  keyName: string
  latestVersion: number
  minDecryptionVersion: number
  /** false = the development key file, not a key management service */
  managed: boolean
}

/** Mirrors EncryptionAdminService.JobStatus. */
export interface EncryptionJobDto {
  job: string
  running: boolean
  processed: number
  failed: number
  startedAt: string | null
  finishedAt: string | null
  lastError: string | null
}

/** Mirrors EncryptionAdminService.EncryptionStatus. */
export interface EncryptionStatusDto {
  enabled: boolean
  key: KeyInfoDto | null
  keyError: string | null
  encryptedObjects: number
  /** key version -> objects whose data key it wraps */
  objectsByKeyVersion: Record<string, number>
  objectsOnOlderVersions: number
  unencryptedDocuments: number
  /** quarantined files and downloadable export ZIPs stored before encryption */
  unencryptedOtherFiles: number
  rewrap: EncryptionJobDto
  encryptExisting: EncryptionJobDto
}

export async function fetchEncryptionStatus(): Promise<EncryptionStatusDto> {
  return apiFetchJson(
    `${BASE}/status`,
    undefined,
    "Could not load the encryption status."
  )
}

export async function rotateEncryptionKey(): Promise<KeyInfoDto> {
  return apiFetchJson(
    `${BASE}/rotate`,
    { method: "POST" },
    "Could not rotate the key."
  )
}

export async function startRewrap(): Promise<EncryptionJobDto> {
  return apiFetchJson(
    `${BASE}/rewrap`,
    { method: "POST" },
    "Could not start re-wrapping."
  )
}

export async function startEncryptExisting(): Promise<EncryptionJobDto> {
  return apiFetchJson(
    `${BASE}/encrypt-existing`,
    { method: "POST" },
    "Could not start encrypting existing documents."
  )
}
