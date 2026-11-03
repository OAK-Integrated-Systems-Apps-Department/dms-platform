import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson, apiFetchVoid, apiUpload, type UploadTransfer } from "@/lib/api-fetch"
import { type IngestionTicketDto } from "@/lib/documents/api"

const DOCUMENTS = `${API_BASE_URL}/api/v1/documents`
const JSON_HEADERS = { "Content-Type": "application/json" }

/** Why a document can or cannot be edited right now - the first reason that applies. */
export type CheckoutState = "available" | "checked_out" | "legal_hold" | "signature_locked"

/** Mirrors backend/dms-core's CheckoutStatusResponse. */
export interface CheckoutStatusDto {
  state: CheckoutState
  checkedOutById: string | null
  checkedOutByName: string | null
  checkedOutAt: string | null
  checkoutExpiresAt: string | null
  /** True when the caller holds the lock - the only person who may check in or discard. */
  checkedOutByMe: boolean
  legalHold: boolean
  legalHoldReason: string | null
  legalHoldAt: string | null
  signatureLocked: boolean
  /** Who made the CURRENT version (uploaded, checked in, or saved in the online editor), when, and which version. */
  lastEditedById: string | null
  lastEditedByName: string | null
  lastEditedAt: string | null
  /** 1 means it was only ever uploaded, never edited; 0 when there is no version. */
  lastEditedVersion: number
}

/** `GET /api/v1/documents/{id}/checkout` */
export function fetchCheckoutStatus(documentId: string): Promise<CheckoutStatusDto> {
  return apiFetchJson(`${DOCUMENTS}/${documentId}/checkout`, undefined, "Could not load this document's lock status.")
}

/** `POST /api/v1/documents/{id}/checkout` - 409 names who holds it. */
export function checkOutDocument(documentId: string): Promise<CheckoutStatusDto> {
  return apiFetchJson(`${DOCUMENTS}/${documentId}/checkout`, { method: "POST" }, "Could not check this document out.")
}

/** `POST /api/v1/documents/{id}/checkin` - the edited file becomes a new version (scanned first) and the lock is released. */
export function checkInDocument(
  documentId: string,
  file: File,
  comment?: string,
  transfer?: UploadTransfer
): Promise<IngestionTicketDto> {
  const form = new FormData()
  form.append("file", file)
  const params = new URLSearchParams()
  if (comment?.trim()) params.set("comment", comment.trim())
  return apiUpload<IngestionTicketDto>(
    `${DOCUMENTS}/${documentId}/checkin?${params.toString()}`,
    form,
    transfer,
    "Could not check this document in."
  )
}

/** `POST /api/v1/documents/{id}/checkout/discard` - give the lock up, no new version. */
export function discardCheckout(documentId: string): Promise<CheckoutStatusDto> {
  return apiFetchJson(`${DOCUMENTS}/${documentId}/checkout/discard`, { method: "POST" }, "Could not discard the check-out.")
}

/** `POST /api/v1/documents/{id}/checkout/force-release` - reason required (HIGH audit). */
export function forceReleaseCheckout(documentId: string, reason: string): Promise<CheckoutStatusDto> {
  return apiFetchJson(
    `${DOCUMENTS}/${documentId}/checkout/force-release`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ reason }) },
    "Could not release the lock."
  )
}

/** `POST /api/v1/documents/{id}/legal-hold` */
export async function placeLegalHold(documentId: string, reason: string): Promise<void> {
  await apiFetchVoid(
    `${DOCUMENTS}/${documentId}/legal-hold`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ reason }) },
    "Could not place the legal hold."
  )
}

/** `POST /api/v1/documents/{id}/legal-hold/release` */
export async function liftLegalHold(documentId: string, reason: string): Promise<void> {
  await apiFetchVoid(
    `${DOCUMENTS}/${documentId}/legal-hold/release`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ reason }) },
    "Could not lift the legal hold."
  )
}

/** Mirrors backend/dms-core's EditingController.EditingConfig. */
export interface EditingConfigDto {
  /** The engine is switched on AND answering now - "Edit online" is hidden otherwise. */
  enabled: boolean
  /** exclusive: starting a session checks the document out. simultaneous: no lock, everyone edits together. */
  mode: "exclusive" | "simultaneous"
  /** Lower-case file extensions the engine can edit. */
  extensions: string[]
}

/** Mirrors EditingSessionService.Session. The token goes to the editor in a form POST - never in a URL. */
export interface EditingSessionDto {
  editorUrl: string
  accessToken: string
  accessTokenExpiresAtMillis: number
  mode: "exclusive" | "simultaneous"
}

/** `GET /api/v1/editing/config` */
export function fetchEditingConfig(): Promise<EditingConfigDto> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/editing/config`, undefined, "Could not check whether online editing is available.")
}

/** `POST /api/v1/documents/{id}/edit-session` - 409/503 carry a message that says to use check-out and download instead. */
export function startEditSession(documentId: string): Promise<EditingSessionDto> {
  return apiFetchJson(`${DOCUMENTS}/${documentId}/edit-session`, { method: "POST" }, "Could not open the online editor.")
}

/** Mirrors EditingSettingsService.View - this organisation's own online-editing setting. */
export interface EditingSettingsDto {
  enabled: boolean
  mode: "exclusive" | "simultaneous"
  /** The SERVER has an editing engine; when false it cannot be turned on (it can always be turned off). */
  engineConfigured: boolean
}

/** `GET /api/v1/editing/settings` - needs one of the cico.configure-editing-engine permissions. */
export function fetchEditingSettings(): Promise<EditingSettingsDto> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/editing/settings`, undefined, "Could not load the online-editing setting.")
}

/** `PUT /api/v1/editing/settings` - needs cico.configure-editing-engine.select-provider; audited. */
export function saveEditingSettings(settings: { enabled: boolean; mode: "exclusive" | "simultaneous" }): Promise<EditingSettingsDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/editing/settings`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(settings) },
    "Could not save the online-editing setting."
  )
}

/** Where the online editor opens: the whole window, or inside the normal DMS page. */
export type EditorLayout = "fullpage" | "inline"

/** Mirrors UserPreferencesService.View - the signed-in user's own interface preferences. */
export interface UserPreferencesDto {
  editorLayout: EditorLayout
}

/** `GET /api/v1/users/me/preferences` - defaults to full page until the user chooses. */
export function fetchUserPreferences(): Promise<UserPreferencesDto> {
  return apiFetchJson(`${API_BASE_URL}/api/v1/users/me/preferences`, undefined, "Could not load your preferences.")
}

/** `PUT /api/v1/users/me/preferences` */
export function saveUserPreferences(preferences: UserPreferencesDto): Promise<UserPreferencesDto> {
  return apiFetchJson(
    `${API_BASE_URL}/api/v1/users/me/preferences`,
    { method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(preferences) },
    "Could not save your preference."
  )
}
