import { z } from "zod/v4"
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
    .min(3, "Username must be at least 3 characters")
    .max(100, "Keep it under 100 characters")
    .regex(/^[a-z0-9._-]+$/i, "Use only letters, numbers, dots, dashes, and underscores"),
  email: z.string().trim().min(1, "Email is required").email("Enter a valid email address"),
  roles: z.array(z.string()).min(1, "Select at least one role"),
})

export type UserInviteFormValues = z.infer<typeof userInviteFormSchema>

/**
 * Validation for the Edit User form, per dms-edit-user.html. No username
 * field — it's read-only there (Keycloak-managed identity), so it's never
 * part of the editable values.
 *
 * `roles` was added alongside the fix wiring this form up to real role
 * assignment (`syncUserRoles` in `lib/users/api.ts`) — previously this
 * screen had no way to change a user's roles after creation at all.
 */
export const userEditFormSchema = z.object({
  firstName: z.string().trim().min(1, "First name is required").max(60, "Keep it under 60 characters"),
  lastName: z.string().trim().min(1, "Last name is required").max(60, "Keep it under 60 characters"),
  email: z.string().trim().min(1, "Email is required").email("Enter a valid email address"),
  roles: z.array(z.string()),
})

export type UserEditFormValues = z.infer<typeof userEditFormSchema>