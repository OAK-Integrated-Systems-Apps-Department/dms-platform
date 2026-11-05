/**
 * The signed-in person's OWN password (and, if their account has an authenticator app, the current code), sent with a
 * sensitive request so the server can check it is really them asking - see backend PasswordConfirmation. Sent as
 * headers so DELETE works like POST and PUT. Never stored, never put in a URL, never logged.
 */
export interface PasswordConfirmation {
  password: string
  /** The authenticator code - only for an account that has one. */
  code?: string
}

export const CONFIRM_PASSWORD_HEADER = "X-Confirm-Password"
export const CONFIRM_CODE_HEADER = "X-Confirm-Totp"

/** The headers that carry a confirmation. */
export function confirmationHeaders(
  confirmation: PasswordConfirmation
): Record<string, string> {
  const headers: Record<string, string> = {
    [CONFIRM_PASSWORD_HEADER]: confirmation.password,
  }
  if (confirmation.code?.trim()) {
    headers[CONFIRM_CODE_HEADER] = confirmation.code.trim()
  }
  return headers
}

/** What the server says (error `code`) when the confirmation fails. */
export const PASSWORD_INCORRECT = "PASSWORD_INCORRECT"
export const PASSWORD_OTP_REQUIRED = "PASSWORD_OTP_REQUIRED"
