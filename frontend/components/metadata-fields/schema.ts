import { z } from "zod"

import { DATA_TYPES } from "./types"

/** Create Field form — fieldKey/dataType are immutable after creation, so only this schema accepts them. */
export const createMetadataFieldSchema = z.object({
  fieldKey: z
    .string()
    .trim()
    .min(2, "Field key must be at least 2 characters")
    .max(100, "Field key must be under 100 characters")
    .regex(/^[a-z][a-z0-9_]*$/, "Must be lower_snake_case, starting with a letter"),
  label: z.string().trim().min(2, "Label must be at least 2 characters").max(150, "Label must be under 150 characters"),
  helpText: z.string().trim().max(500, "Keep help text under 500 characters").optional().or(z.literal("")),
  dataType: z.enum(DATA_TYPES),
  defaultValue: z.string().trim().max(500).optional().or(z.literal("")),
  systemField: z.boolean(),
  searchable: z.boolean(),
  ocrExtractable: z.boolean(),
})
export type CreateMetadataFieldFormValues = z.infer<typeof createMetadataFieldSchema>

/** Edit Field form — fieldKey/dataType/systemField stay fixed once created. */
export const updateMetadataFieldSchema = z.object({
  label: z.string().trim().min(2, "Label must be at least 2 characters").max(150, "Label must be under 150 characters"),
  helpText: z.string().trim().max(500, "Keep help text under 500 characters").optional().or(z.literal("")),
  defaultValue: z.string().trim().max(500).optional().or(z.literal("")),
  searchable: z.boolean(),
  ocrExtractable: z.boolean(),
})
export type UpdateMetadataFieldFormValues = z.infer<typeof updateMetadataFieldSchema>
