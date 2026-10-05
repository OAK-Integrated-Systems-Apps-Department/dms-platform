import { z } from "zod"

const departmentShape = {
  name: z.string().trim().min(1, "Name is required").max(150, "Name must be under 150 characters"),
  parentDepartmentId: z.string(),
  headUserId: z.string(),
}

/** Create Department - `code` is only accepted here (stored upper-case, never changes). Matches CreateDepartmentRequest. */
export const createDepartmentSchema = z.object({
  ...departmentShape,
  code: z.string().trim().regex(/^[A-Za-z0-9_-]{2,50}$/, "2-50 letters, digits, '_' or '-'"),
})
export type CreateDepartmentFormValues = z.infer<typeof createDepartmentSchema>

/** Edit Department - code stays fixed; status is editable. "" means none for the two optional pickers. */
export const updateDepartmentSchema = z.object({
  ...departmentShape,
  status: z.enum(["active", "inactive"]),
})
export type UpdateDepartmentFormValues = z.infer<typeof updateDepartmentSchema>
