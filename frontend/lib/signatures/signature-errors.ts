import { ApiError } from "@/lib/api-error"

/**
 * The machine-readable codes `sign-and-approve` and the plain `/approve`
 * endpoint can answer with (see SignatureException/WorkflowStateException on
 * the backend). `SignAndApproveDialog` uses these to decide what stays on
 * screen after a failure: always keep the comment, always clear
 * password/totp, and for a step-up failure specifically, keep the dialog
 * open with an inline message rather than closing it.
 */
export const SIGNATURE_ERROR_CODES = {
  CONSENT_REQUIRED: "SIGNATURE_CONSENT_REQUIRED",
  PASSWORD_REQUIRED: "SIGNATURE_PASSWORD_REQUIRED",
  OTP_REQUIRED: "SIGNATURE_OTP_REQUIRED",
  STEP_UP_FAILED: "SIGNATURE_STEP_UP_FAILED",
  IMPERSONATION_FORBIDDEN: "SIGNATURE_IMPERSONATION_FORBIDDEN",
  ALREADY_RECORDED: "SIGNATURE_ALREADY_RECORDED",
  DOCUMENT_INTEGRITY: "SIGNATURE_DOCUMENT_INTEGRITY",
  KEY_SERVICE_UNAVAILABLE: "SIGNATURE_KEY_SERVICE_UNAVAILABLE",
  SELF_CHECK_FAILED: "SIGNATURE_SELF_CHECK_FAILED",
  FEATURE_DISABLED: "SIGNATURE_FEATURE_DISABLED",
  WORKFLOW_SIGNATURE_REQUIRED: "WORKFLOW_SIGNATURE_REQUIRED",
  DOCUMENT_SIGNATURE_LOCKED: "DOCUMENT_SIGNATURE_LOCKED",
  DOCUMENT_NOT_SIGNATURE_LOCKED: "DOCUMENT_NOT_SIGNATURE_LOCKED",
} as const

/** Wrong password or missing/wrong authenticator code - the two fields the dialog must clear and let the signer retry. */
export function isStepUpFailure(error: unknown): boolean {
  return error instanceof ApiError && error.code === SIGNATURE_ERROR_CODES.STEP_UP_FAILED
}

/** The stage requires a signature - thrown by the plain /approve endpoint, telling the caller to open SignAndApproveDialog instead. */
export function isSignatureRequired(error: unknown): boolean {
  return error instanceof ApiError && error.code === SIGNATURE_ERROR_CODES.WORKFLOW_SIGNATURE_REQUIRED
}

/** The document is locked because it carries a valid signature. */
export function isSignatureLocked(error: unknown): boolean {
  return error instanceof ApiError && error.code === SIGNATURE_ERROR_CODES.DOCUMENT_SIGNATURE_LOCKED
}

/** A friendly message for a sign-and-approve failure - falls back to the server's own message for anything not special-cased. */
export function signatureErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return "Something went wrong. Please try again."
  switch (error.code) {
    case SIGNATURE_ERROR_CODES.STEP_UP_FAILED:
      return "Your password or authenticator app code is incorrect."
    case SIGNATURE_ERROR_CODES.IMPERSONATION_FORBIDDEN:
      return "Signing is not allowed while impersonating another user."
    case SIGNATURE_ERROR_CODES.DOCUMENT_INTEGRITY:
      return "The stored file no longer matches its recorded hash - this document cannot be signed right now."
    case SIGNATURE_ERROR_CODES.KEY_SERVICE_UNAVAILABLE:
      return "Could not reach the signing service - please try again in a moment."
    case SIGNATURE_ERROR_CODES.SELF_CHECK_FAILED:
      return "The signature could not be verified - nothing was recorded. Please try again."
    case SIGNATURE_ERROR_CODES.ALREADY_RECORDED:
      return "This task has already been signed."
    default:
      return error.message
  }
}
