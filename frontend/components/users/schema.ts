import { z } from "zod"

/**
 * Validation for the Add User form. No password field on purpose — see
 * the doc comment on UserInviteForm for why.
 */
export const userInviteFormSchema = z.object({
  firstName: z.string().trim().min(1, "First name is required").max(60, "Keep it under 60 characters"),
  lastName: z.string().trim().min(1, "Last name is required").max(60, "Keep it under 60 characters"),
  email: z.string().trim().min(1, "Email is required").email("Enter a valid email address"),
})

export type UserInviteFormValues = z.infer<typeof userInviteFormSchema>