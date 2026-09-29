import { z } from "zod/v4"

/** Mirrors `components/users/schema.ts`'s `NAME_MAX_LENGTH` for the same two fields — no shared module across the two feature folders, so kept in sync manually. */
const NAME_MAX_LENGTH = 60

/**
 * Validation for My Profile's inline name edit — mirrors
 * `components/users/schema.ts`'s `userEditFormSchema` field-for-field for
 * first/last name, minus `email`/`roles` (this form only ever sends
 * `PUT /api/v1/users/me`, which accepts nothing else — see
 * `UpdateOwnProfileRequest` on the backend).
 */
export const profileEditFormSchema = z.object({
  firstName: z.string().trim().min(1, "First name is required").max(NAME_MAX_LENGTH, "Keep it under 60 characters"),
  lastName: z.string().trim().min(1, "Last name is required").max(NAME_MAX_LENGTH, "Keep it under 60 characters"),
})

export type ProfileEditFormValues = z.infer<typeof profileEditFormSchema>
