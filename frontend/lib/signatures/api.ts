import { API_BASE_URL } from "@/lib/api-config"
import { apiFetchJson } from "@/lib/api-fetch"
import { type TaskActionResultDto } from "@/lib/workflow/api"

const DOCUMENTS = `${API_BASE_URL}/api/v1/documents`
const SIGNATURES = `${API_BASE_URL}/api/v1/signatures`
const SIGNING_KEYS = `${API_BASE_URL}/api/v1/signing-keys`
const WORKFLOW_TASKS = `${API_BASE_URL}/api/v1/workflow/tasks`

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
