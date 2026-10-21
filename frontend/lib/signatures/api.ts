import { ApiError } from "@/lib/api-error"
import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch, apiFetchJson } from "@/lib/api-fetch"
import { type TaskActionResultDto } from "@/lib/workflow/api"

const DOCUMENTS = `${API_BASE_URL}/api/v1/documents`
const SIGNATURES = `${API_BASE_URL}/api/v1/signatures`
const SIGNING_KEYS = `${API_BASE_URL}/api/v1/signing-keys`
const WORKFLOW_TASKS = `${API_BASE_URL}/api/v1/workflow/tasks`
const VISUAL_SIGNATURE = `${API_BASE_URL}/api/v1/users/me/visual-signature`

const JSON_HEADERS = { "Content-Type": "application/json" }

export type SignatureMeaning = "approved" | "reviewed" | "authored" | "acknowledged"
export type SignatureStatus = "valid" | "invalidated"
export type VerificationResult = "valid" | "signature_mismatch" | "document_altered" | "unreadable"

/** Mirrors backend/dms-core's DocumentSignatureResponse. */
export interface DocumentSignatureDto {
  signatureId: string
  documentId: string
  versionId: string
  versionNumber: number
  /** False when the document has moved on to a newer version since this was signed. */
  currentVersion: boolean
  workflowInstanceId: string
  taskId: string | null
  signerUserId: string
  signerName: string
  signerEmail: string
  meaning: SignatureMeaning
  comment: string | null
  status: SignatureStatus
  statusReason: string | null
  statusChangedAt: string | null
  lastVerificationResult: VerificationResult | null
  lastVerifiedAt: string | null
  signedAt: string
  /** Cosmetic only - never part of what's cryptographically verified. Null for a signature made before this existed. */
  visualSignatureKind: VisualSignatureKind | null
  visualSignatureText: string | null
  /** Base64 PNG - null unless visualSignatureKind is "drawn". */
  visualSignatureImageBase64: string | null
}

/** "drawn" (hand-drawn on a canvas) and "uploaded" (a photo/scan, background made transparent client-side) are stored/validated identically on the backend - kept distinct only so the UI remembers which editor to show when re-opening a saved one. */
export type VisualSignatureKind = "typed" | "drawn" | "uploaded"

/** Mirrors backend/dms-core's VisualSignatureDto - a user's own saved cosmetic signature mark. */
export interface VisualSignatureDto {
  kind: VisualSignatureKind
  typedText: string | null
  /** Base64 PNG - null when kind is "typed". */
  imageBase64: string | null
  updatedAt: string
}

/** Mirrors backend/dms-core's VerifyAllResponse. */
export interface VerifyAllResultDto {
  total: number
  valid: number
  invalidated: number
  unreadable: number
  signatures: DocumentSignatureDto[]
}

/** Mirrors backend/dms-core's SignatureEvidenceResponse. */
export interface SignatureEvidenceDto {
  signatureId: string
  documentId: string
  versionId: string
  manifestText: string
  signatureDerBase64: string
  publicKeyPem: string
  algorithm: string
  fileSha256: string
  status: SignatureStatus
  lastVerificationResult: VerificationResult | null
  signedAt: string
}

/** Mirrors backend/dms-core's SignAndApproveRequest. password/totp are never logged or kept past the request. */
export interface SignAndApproveRequestDto {
  comment?: string | null
  meaning: SignatureMeaning
  consent: boolean
  password: string
  totp?: string | null
  visualSignatureKind: VisualSignatureKind
  visualSignatureText?: string | null
  /** Base64 PNG from the signing canvas - required when visualSignatureKind is "drawn". */
  visualSignatureImageBase64?: string | null
}

/** A document's signatures, newest first. */
export async function fetchDocumentSignatures(documentId: string): Promise<DocumentSignatureDto[]> {
  return apiFetchJson(`${DOCUMENTS}/${documentId}/signatures`, undefined, "Could not load this document's signatures.")
}

/** Re-verifies every one of a document's signatures now (DSI-FR-003). */
export async function verifyDocumentSignatures(documentId: string): Promise<VerifyAllResultDto> {
  return apiFetchJson(
    `${DOCUMENTS}/${documentId}/signatures/verify`,
    { method: "POST" },
    "Could not verify this document's signatures."
  )
}

/** Everything needed to verify a signature independently of this application. */
export async function fetchSignatureEvidence(signatureId: string): Promise<SignatureEvidenceDto> {
  return apiFetchJson(`${SIGNATURES}/${signatureId}/evidence`, undefined, "Could not load this signature's evidence.")
}

/** Unlocks a signature-locked document. A reason is mandatory. */
export async function releaseSignatureLock(documentId: string, reason: string): Promise<void> {
  await apiFetchJson(
    `${DOCUMENTS}/${documentId}/signature-lock/release`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ reason }) },
    "Could not unlock this document."
  )
}

/** Revokes a user's active signing key. A reason is mandatory. */
export async function revokeSigningKey(userId: string, reason: string): Promise<void> {
  await apiFetchJson(
    `${SIGNING_KEYS}/${userId}/revoke`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify({ reason }) },
    "Could not revoke this signing key."
  )
}

/** The caller's own saved visual signature, or null if they've never set one (backend answers 404). */
export async function fetchMyVisualSignature(): Promise<VisualSignatureDto | null> {
  try {
    return await apiFetchJson(VISUAL_SIGNATURE, undefined, "Could not load your saved signature.")
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}

/**
 * Sets/replaces the caller's own saved visual signature - re-verified with their password server-side
 * (step-up, same mechanism signing itself depends on), since unlike sign-and-approve's own automatic
 * update of this row, nothing else about this standalone call proves it's really this person.
 */
export async function saveMyVisualSignature(
  input: Pick<SignAndApproveRequestDto, "visualSignatureKind" | "visualSignatureText" | "visualSignatureImageBase64"> & {
    password: string
  }
): Promise<VisualSignatureDto> {
  return apiFetchJson(
    VISUAL_SIGNATURE,
    {
      method: "PUT",
      headers: JSON_HEADERS,
      body: JSON.stringify({
        kind: input.visualSignatureKind,
        typedText: input.visualSignatureText ?? null,
        imageBase64: input.visualSignatureImageBase64 ?? null,
        password: input.password,
      }),
    },
    "Could not save your signature."
  )
}

/** Mirrors backend/dms-core's SignaturePlacementInfoResponse. */
export interface SignaturePlacementInfoDto {
  documentId: string
  versionId: string
  mimeType: string
  /** False for a file type DocumentStampService can't stamp (anything but PDF/PNG/JPEG) - placement is skipped for it. */
  supported: boolean
  pageCount: number
  /** True once this task has placed a signature at least once - the placement UI then offers to remove it. */
  alreadyPlaced: boolean
}

/** Mirrors backend/dms-core's PlaceSignatureResponse - the new, stamped version that sign-and-approve (called right after) will sign. */
export interface PlaceSignatureResultDto {
  versionId: string
  versionNumber: number
}

/** Whether/how many pages this task's current document version can be signature-placed on. */
export async function fetchSignaturePlacementInfo(taskId: string): Promise<SignaturePlacementInfoDto> {
  return apiFetchJson(
    `${WORKFLOW_TASKS}/${taskId}/signature-placement`,
    undefined,
    "Could not load this document for signature placement."
  )
}

/** Page/image `page` (0-based) rendered for the placement UI to drag the signature onto. */
export async function fetchSignaturePlacementPage(taskId: string, page: number, width: number): Promise<Blob> {
  const response = await apiFetch(
    `${WORKFLOW_TASKS}/${taskId}/signature-placement/pages/${page}?width=${width}`,
    undefined,
    "Could not load this page."
  )
  return response.blob()
}

/**
 * Burns the signer's visual signature onto the given page/rectangle, producing a new document version -
 * BEFORE sign-and-approve, which then signs exactly that new version (see the backend's own doc comment
 * on `SignaturePlacementService` for why this has to be a separate call).
 */
export async function placeSignatureOnDocument(
  taskId: string,
  input: {
    page: number
    xRatio: number
    yRatio: number
    widthRatio: number
    heightRatio: number
  } & Pick<SignAndApproveRequestDto, "visualSignatureKind" | "visualSignatureText" | "visualSignatureImageBase64">
): Promise<PlaceSignatureResultDto> {
  return apiFetchJson(
    `${WORKFLOW_TASKS}/${taskId}/signature-placement`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(input) },
    "Could not place your signature on the document."
  )
}

/** Removes this task's placed signature - the stamped version itself is kept, just no longer what will be signed. */
export async function removeSignaturePlacement(taskId: string): Promise<void> {
  await apiFetchJson(
    `${WORKFLOW_TASKS}/${taskId}/signature-placement`,
    { method: "DELETE" },
    "Could not remove the placed signature."
  )
}

/**
 * Signs AND approves a task in one transaction (1.4). On failure the caller
 * must keep the dialog open, keep the comment, and clear only the
 * password/OTP fields - see `SignAndApproveDialog`.
 */
export async function signAndApproveTask(
  taskId: string,
  request: SignAndApproveRequestDto
): Promise<TaskActionResultDto> {
  return apiFetchJson(
    `${WORKFLOW_TASKS}/${taskId}/sign-and-approve`,
    { method: "POST", headers: JSON_HEADERS, body: JSON.stringify(request) },
    "Could not sign and approve this task."
  )
}
