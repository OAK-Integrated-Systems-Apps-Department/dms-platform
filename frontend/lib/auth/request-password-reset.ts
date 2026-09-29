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
  await fetch(`${API_BASE_URL}/api/v1/auth/forgot-password`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ usernameOrEmail }),
  })
}
