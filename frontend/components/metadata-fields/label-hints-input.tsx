"use client"

import { TagInput, type TagInputProps } from "@/components/ui/tag-input"
import { MAX_LABEL_HINTS, labelHintCaseFoldKey, labelHintError, normalizeLabelHint } from "@/lib/metadata-fields/label-hints"

/**
 * `TagInput` pre-wired with dms-core's OCR label-hint rules (see
 * lib/metadata-fields/label-hints.ts) — used by BOTH hint surfaces, the
 * field's own global "Label hints" (metadata-field-form.tsx) and a document
 * type's "Hints for this type" (document-type-fields-tab.tsx), so the two
 * can never drift into accepting different things. What distinguishes the
 * two surfaces is their scope and helper text, not their input rules.
 */
export function LabelHintsInput(props: Omit<TagInputProps, "normalize" | "validate" | "maxTags" | "dedupeKey">) {
  return (
    <TagInput
      normalize={normalizeLabelHint}
      validate={labelHintError}
      maxTags={MAX_LABEL_HINTS}
      dedupeKey={labelHintCaseFoldKey}
      {...props}
    />
  )
}
