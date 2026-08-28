import { z } from "zod/v4"

/**
 * Validation for the credentials step of Sign In (dms-login.html).
 * Keycloak owns the actual "is this username/password correct" check —
 * this only guards against submitting the form empty/whitespace before
 * a network call is ever made.
 */
export const loginCredentialsSchema = z.object({
  username: z.string().trim().min(1, "Username or email is required"),
  password: z.string().min(1, "Password is required"),
})

export type LoginCredentialsValues = z.infer<typeof loginCredentialsSchema>