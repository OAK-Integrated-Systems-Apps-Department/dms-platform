import { API_BASE_URL } from "@/lib/api-config"

/**
 * Issues a PASSWORD_RESET-kind token via email, landing on
 * `/update-password` — see `PasswordResetService.requestReset` on the
 * backend. Shared by `/reset-password` (an unauthenticated user submitting
 * their own username/email) and My Profile's "Change password" action (an
 * authenticated user requesting one for their own known email) — same
 * flow, same generic "always looks like success" response either way, so
 * neither caller reads the response body for content.
 */
export async function requestPasswordReset(usernameOrEmail: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/v1/auth/forgot-password`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ usernameOrEmail }),
  })
  // The backend answers 200 whether or not the account exists, so a non-OK
  // status is a real failure (rate limit, validation, server down) - say so
  // instead of reporting a send that never happened.
  if (!response.ok) {
    throw new Error(
      response.status === 429
        ? "Too many attempts. Please wait a few minutes and try again."
        : "Could not send the reset link. Please try again."
    )
  }
}
