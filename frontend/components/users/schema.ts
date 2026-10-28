import { z } from "zod/v4"

/** Shared by both schemas below for firstName/lastName — same limit `profile/schema.ts`'s `profileEditFormSchema` mirrors for the same two fields. */
const NAME_MAX_LENGTH = 60

/**
 * Validation for the Add User form, per dms-add-user.html. Still no
 * password field — see the doc comment on UserInviteForm for why.
 */
export const userInviteFormSchema = z.object({
  firstName: z.string().trim().min(1, "First name is required").max(NAME_MAX_LENGTH, "Keep it under 60 characters"),
  lastName: z.string().trim().min(1, "Last name is required").max(NAME_MAX_LENGTH, "Keep it under 60 characters"),
  username: z
    .string()
    .trim()
    .min(3, "Username must be at least 3 characters")
    .max(100, "Keep it under 100 characters")
    .regex(/^[a-z0-9._-]+$/i, "Use only letters, numbers, dots, dashes, and underscores"),
  // 255: the most Keycloak stores (user_entity.email) - see CreateUserRequest/UpdateUserRequest.
  email: z.string().trim().min(1, "Email is required").max(255, "An email can be at most 255 characters").email("Enter a valid email address"),
  // No .min(1): a caller without role-view permission has no picker to
  // select one at all (see UserInviteForm's own "can still be invited
  // without one" copy for that case), and CreateUserRequest itself has no
  // roles field either - role assignment is a fully separate, optional
  // second step (assignUserRole, looped over below in onSubmit).
  roles: z.array(z.string()),
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
  firstName: z.string().trim().min(1, "First name is required").max(NAME_MAX_LENGTH, "Keep it under 60 characters"),
  // Optional, as on the backend (UpdateUserRequest) - a user created without one must still be editable.
  lastName: z.string().trim().max(NAME_MAX_LENGTH, "Keep it under 60 characters"),
  // 255: the most Keycloak stores (user_entity.email) - see CreateUserRequest/UpdateUserRequest.
  email: z.string().trim().min(1, "Email is required").max(255, "An email can be at most 255 characters").email("Enter a valid email address"),
  roles: z.array(z.string()),
})

export type UserEditFormValues = z.infer<typeof userEditFormSchema>