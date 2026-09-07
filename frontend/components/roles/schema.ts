import { z } from "zod"

/**
 * Validation for the Add/Edit Role form. Kept separate from `types.ts` so
 * the zod schema (a runtime concern) doesn't leak into the plain
 * `RoleRecord` shape used for display.
 */
export const roleFormSchema = z.object({
  name: z
    .string()
    .trim()
    .min(2, "Role name must be at least 2 characters")
    .max(60, "Role name must be under 60 characters")
    .regex(
      /^\S+$/,
      "Role name cannot contain spaces — use underscores instead, e.g. Compliance_Officer"
    ),
  description: z
    .string()
    .trim()
    .max(240, "Keep the description under 240 characters")
    .optional()
    .or(z.literal("")),
  status: z.enum(["Active", "Inactive"]),
  permissions: z.array(z.string()),
})

export type RoleFormValues = z.infer<typeof roleFormSchema>
