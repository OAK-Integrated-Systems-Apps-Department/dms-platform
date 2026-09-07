import { z } from "zod"

/**
 * Validation for the Add/Edit Permission form.
 *
 * `moduleId` exists purely to drive the cascading Module → Action selects
 * in the UI — the backend only ever receives `actionId` (a permission
 * belongs directly to an action, not to a module; see `Permission.java`).
 * It's kept out of the request body in `stores/permissions-store.ts`
 * rather than dropped from the schema, so the form can still validate
 * "a module must be chosen before an action can be" as two separate,
 * clearly-labeled errors instead of one opaque "choose an action".
 *
 * `code` is included here (create mode requires it) but the Edit Permission
 * form never renders an editable field for it — `PermissionRecord.code`
 * already exists there and is immutable server-side (see
 * `UpdatePermissionRequest.java`), so `permission-form.tsx` renders it
 * read-only in edit mode and this schema's own regex still fires against
 * the pre-filled value, which is harmless since it's not user-editable.
 */
export const permissionFormSchema = z.object({
  code: z
    .string()
    .trim()
    .min(3, "Permission code must be at least 3 characters")
    .max(100, "Permission code must be under 100 characters")
    .regex(
      /^[a-z0-9]+(\.[a-z0-9]+)+$/,
      "Use dot-separated lowercase segments, e.g. document.approve"
    ),
  name: z
    .string()
    .trim()
    .min(2, "Name must be at least 2 characters")
    .max(150, "Name must be under 150 characters"),
  description: z
    .string()
    .trim()
    .max(500, "Keep the description under 500 characters")
    .optional()
    .or(z.literal("")),
  moduleId: z.string().min(1, "Choose a module"),
  actionId: z.string().min(1, "Choose an action"),
})

export type PermissionFormValues = z.infer<typeof permissionFormSchema>
