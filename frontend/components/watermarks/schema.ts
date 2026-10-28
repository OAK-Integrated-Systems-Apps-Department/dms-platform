import { z } from "zod"

import {
  WATERMARK_COLOR_PATTERN,
  WATERMARK_STYLE_LIMITS,
  WATERMARK_TEMPLATE_LABEL_MAX_LENGTH,
  WATERMARK_TEXT_MAX_LENGTH,
} from "@/lib/watermarks/constants"

const placement = z.enum(["tiled", "centered", "corner", "header-footer"])
const pageScope = z.enum(["all-pages", "first-page-only"])

const color = z
  .string()
  .regex(WATERMARK_COLOR_PATTERN, "Use a six-digit hex colour, like #808080.")

const fontSize = z
  .number({ message: "Enter a font size." })
  .int("Font size must be a whole number.")
  .min(WATERMARK_STYLE_LIMITS.fontSize.min, `At least ${WATERMARK_STYLE_LIMITS.fontSize.min}pt.`)
  .max(WATERMARK_STYLE_LIMITS.fontSize.max, `At most ${WATERMARK_STYLE_LIMITS.fontSize.max}pt.`)

const opacity = z
  .number({ message: "Enter an opacity." })
  .int("Opacity must be a whole number.")
  // The floor is 5, not 0: an invisible watermark is indistinguishable from
  // a missing one, and someone who set it to zero would believe their copy
  // was marked when it wasn't.
  .min(WATERMARK_STYLE_LIMITS.opacity.min, `At least ${WATERMARK_STYLE_LIMITS.opacity.min}% - a watermark can't be invisible.`)
  .max(WATERMARK_STYLE_LIMITS.opacity.max, `At most ${WATERMARK_STYLE_LIMITS.opacity.max}%.`)

const rotation = z
  .number({ message: "Enter a rotation." })
  .int("Rotation must be a whole number.")
  .min(WATERMARK_STYLE_LIMITS.rotation.min, `At least ${WATERMARK_STYLE_LIMITS.rotation.min}°.`)
  .max(WATERMARK_STYLE_LIMITS.rotation.max, `At most ${WATERMARK_STYLE_LIMITS.rotation.max}°.`)

/**
 * The "ask for a watermarked copy" form.
 *
 * `source` is what makes template-or-text mutually exclusive without two
 * fields that can both be filled: the user picks one mode, and the
 * refinement below only requires whichever is active. Validating both and
 * then checking "exactly one is set" would let somebody fill in text,
 * switch to a template, and submit a request carrying both - which the
 * backend rejects with a 400 nobody would understand.
 *
 * The text cap here is the FIELD's cap (500). The resolved text has a lower
 * one (200 by default), so a short template full of placeholders can still
 * be refused after substitution - the backend's 422 carries that, and the
 * dialog shows it under this field rather than pretending to know in
 * advance how long a display name is.
 */
export const watermarkRequestSchema = z
  .object({
    source: z.enum(["template", "custom"]),
    templateId: z.string(),
    customText: z.string().max(WATERMARK_TEXT_MAX_LENGTH, `At most ${WATERMARK_TEXT_MAX_LENGTH} characters.`),
    versionId: z.string(),
    fontSize,
    opacity,
    rotation,
    color,
    placement,
    pageScope,
  })
  .refine((values) => values.source !== "template" || values.templateId.length > 0, {
    message: "Choose a template.",
    path: ["templateId"],
  })
  .refine((values) => values.source !== "custom" || values.customText.trim().length > 0, {
    message: "Type what the watermark should say.",
    path: ["customText"],
  })

export type WatermarkRequestFormValues = z.infer<typeof watermarkRequestSchema>

/**
 * The admin template form. Style fields are optional here in a way they are
 * not on a request: leaving one blank means "use the system default", which
 * is how a template can set only what it cares about. An empty string from
 * an untouched number input therefore has to mean null, not zero.
 */
export const watermarkTemplateSchema = z.object({
  label: z
    .string()
    .trim()
    .min(1, "Give the template a name.")
    .max(WATERMARK_TEMPLATE_LABEL_MAX_LENGTH, `At most ${WATERMARK_TEMPLATE_LABEL_MAX_LENGTH} characters.`),
  defaultText: z
    .string()
    .trim()
    .min(1, "Say what the watermark should read.")
    .max(WATERMARK_TEXT_MAX_LENGTH, `At most ${WATERMARK_TEXT_MAX_LENGTH} characters.`),
  active: z.boolean(),
  /** Blank = inherit the system default. */
  fontSize: fontSize.nullable(),
  opacity: opacity.nullable(),
  rotation: rotation.nullable(),
  color: color.nullable(),
  placement: placement.nullable(),
  pageScope: pageScope.nullable(),
})

export type WatermarkTemplateFormValues = z.infer<typeof watermarkTemplateSchema>
