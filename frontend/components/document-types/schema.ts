import { z } from "zod"

/** Create Document Type form — typeCode is immutable after creation, so only this schema accepts it. */
export const createDocumentTypeSchema = z.object({
  typeCode: z
    .string()
    .trim()
    .min(2, "Type code must be at least 2 characters")
    .max(50, "Type code must be under 50 characters")
    .regex(/^[A-Z][A-Z0-9_]*$/, "Must be UPPER_SNAKE_CASE, starting with a letter"),
  typeName: z.string().trim().min(2, "Name must be at least 2 characters").max(150, "Name must be under 150 characters"),
  description: z.string().trim().max(500, "Keep the description under 500 characters").optional().or(z.literal("")),
  icon: z.string().trim().max(50).optional().or(z.literal("")),
  displayOrder: z.number().int().min(0).max(9999),
  requiresWatermark: z.boolean(),
  coeditingEnabled: z.boolean(),
})
export type CreateDocumentTypeFormValues = z.infer<typeof createDocumentTypeSchema>

/** Edit Document Type form — typeCode stays fixed once created. */
export const updateDocumentTypeSchema = createDocumentTypeSchema.omit({ typeCode: true })
export type UpdateDocumentTypeFormValues = z.infer<typeof updateDocumentTypeSchema>
