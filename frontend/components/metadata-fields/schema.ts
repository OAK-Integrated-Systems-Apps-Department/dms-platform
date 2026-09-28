import { z } from "zod"

import { labelHintsError } from "@/lib/metadata-fields/label-hints"

import { DATA_TYPES } from "./types"

/**
 * The field's GLOBAL OCR label hints (see lib/metadata-fields/label-hints.ts).
 * `LabelHintsInput` already refuses a bad entry as it's typed, so this is the
 * backstop for the list as a whole — mainly the 20-hint cap — reported as one
 * message under the input rather than per chip.
 */
const labelAliasesSchema = z.array(z.string()).superRefine((hints, ctx) => {
  const error = labelHintsError(hints)
  if (error) ctx.addIssue({ code: "custom", message: error })
})

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
  searchable: z.boolean(),
  ocrExtractable: z.boolean(),
  labelAliases: labelAliasesSchema,
})
export type CreateMetadataFieldFormValues = z.infer<typeof createMetadataFieldSchema>

/** Edit Field form — fieldKey/dataType/systemField stay fixed once created. */
export const updateMetadataFieldSchema = z.object({
  label: z.string().trim().min(2, "Label must be at least 2 characters").max(150, "Label must be under 150 characters"),
  helpText: z.string().trim().max(500, "Keep help text under 500 characters").optional().or(z.literal("")),
  defaultValue: z.string().trim().max(500).optional().or(z.literal("")),
  searchable: z.boolean(),
  ocrExtractable: z.boolean(),
  labelAliases: labelAliasesSchema,
})
export type UpdateMetadataFieldFormValues = z.infer<typeof updateMetadataFieldSchema>
