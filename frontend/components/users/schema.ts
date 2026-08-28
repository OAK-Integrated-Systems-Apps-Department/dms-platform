import { z } from "zod"

/**
 * Validation for the Add User form, per dms-add-user.html. Still no
 * password field — see the doc comment on UserInviteForm for why.
 */
export const userInviteFormSchema = z.object({
  firstName: z.string().trim().min(1, "First name is required").max(60, "Keep it under 60 characters"),
  lastName: z.string().trim().min(1, "Last name is required").max(60, "Keep it under 60 characters"),
  username: z
    .string()
    .trim()
    .min(2, "Username must be at least 2 characters")
    .max(60, "Keep it under 60 characters")
    .regex(/^[a-z0-9._-]+$/i, "Use only letters, numbers, dots, dashes, and underscores"),
  email: z.string().trim().min(1, "Email is required").email("Enter a valid email address"),
  roles: z.array(z.string()).min(1, "Select at least one role"),
  additionalProfile: z.string().trim().max(500, "Keep it under 500 characters").optional().or(z.literal("")),
})

export type UserInviteFormValues = z.infer<typeof userInviteFormSchema>