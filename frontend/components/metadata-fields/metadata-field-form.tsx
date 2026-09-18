"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import { AlertTriangle } from "lucide-react"

import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"

import {
  createMetadataFieldSchema,
  updateMetadataFieldSchema,
  type CreateMetadataFieldFormValues,
  type UpdateMetadataFieldFormValues,
} from "./schema"
import { DATA_TYPES, DATA_TYPE_LABELS } from "./types"

const FLAG_FIELDS: { name: "searchable" | "ocrExtractable"; label: string; hint: string }[] = [
  { name: "searchable", label: "Searchable", hint: "Included in full-text/metadata search results" },
  { name: "ocrExtractable", label: "OCR Extractable", hint: "Can be auto-populated from OCR text" },
]

interface CreateFieldFormProps {
  onSubmit: (values: CreateMetadataFieldFormValues) => Promise<string | null>
}

/** Add Field — creates a new entry in the tenant's metadata schema registry (A3). */
export function CreateMetadataFieldForm({ onSubmit }: CreateFieldFormProps) {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    control,
    formState: { errors, isSubmitting },
  } = useForm<CreateMetadataFieldFormValues>({
    resolver: zodResolver(createMetadataFieldSchema),
    defaultValues: {
      fieldKey: "",
      label: "",
      helpText: "",
      dataType: "text",
      defaultValue: "",
      searchable: true,
      ocrExtractable: false,
    },
  })

  async function handleFormSubmit(values: CreateMetadataFieldFormValues) {
    setSubmitError(null)
    const error = await onSubmit(values)
    if (error) setSubmitError(error)
  }

  return (
    <form
      onSubmit={handleSubmit(handleFormSubmit)}
      className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]"
    >
      {submitError && (
        <div className="mb-6 flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <span>{submitError}</span>
        </div>
      )}

      <div className="mb-5 flex flex-col gap-5 sm:flex-row sm:items-start">
        <div className="flex-1">
          <Label htmlFor="field-key">
            Field Key <span className="text-[var(--ink-faint)] normal-case">(cannot be changed later)</span>
          </Label>
          <Input
            id="field-key"
            placeholder="e.g. classification_level"
            aria-invalid={Boolean(errors.fieldKey)}
            {...register("fieldKey")}
          />
          {errors.fieldKey && <p className="mt-1.5 text-[12px] text-destructive">{errors.fieldKey.message}</p>}
        </div>

        <div className="sm:w-[220px] sm:shrink-0">
          <Label htmlFor="field-data-type">
            Data Type <span className="text-[var(--ink-faint)] normal-case">(cannot be changed later)</span>
          </Label>
          <select
            id="field-data-type"
            {...register("dataType")}
            className="h-10 w-full appearance-none rounded-btn border border-[var(--border-strong)] bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
          >
            {DATA_TYPES.map((type) => (
              <option key={type} value={type}>
                {DATA_TYPE_LABELS[type]}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="mb-5">
        <Label htmlFor="field-label">Label</Label>
        <Input id="field-label" placeholder="e.g. Classification Level" aria-invalid={Boolean(errors.label)} {...register("label")} />
        {errors.label && <p className="mt-1.5 text-[12px] text-destructive">{errors.label.message}</p>}
      </div>

      <div className="mb-5">
        <Label htmlFor="field-help-text">
          Help Text <span className="text-[var(--ink-faint)] normal-case">(optional)</span>
        </Label>
        <Textarea id="field-help-text" placeholder="Shown beneath the field on the upload form…" {...register("helpText")} />
      </div>

      <div className="mb-6">
        <Label htmlFor="field-default-value">
          Default Value <span className="text-[var(--ink-faint)] normal-case">(optional)</span>
        </Label>
        <Input id="field-default-value" {...register("defaultValue")} />
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="mb-1">
        <h2 className="font-heading text-[16px] font-semibold">Behavior</h2>
      </div>
      <div className="divide-y divide-dashed divide-border">
        {FLAG_FIELDS.map(({ name, label, hint }) => (
          <Controller
            key={name}
            name={name}
            control={control}
            render={({ field }) => (
              <Checkbox label={`${label} — ${hint}`} checked={field.value} onCheckedChange={field.onChange} />
            )}
          />
        ))}
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="flex items-center justify-end gap-2.5">
        <button
          type="button"
          onClick={() => router.push("/admin/metadata/fields")}
          className="rounded-btn border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting}
          className={cn(
            "rounded-btn border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground",
            "disabled:cursor-not-allowed disabled:opacity-60"
          )}
        >
          Create Field
        </button>
      </div>
    </form>
  )
}

interface EditFieldFormProps {
  defaultValues: UpdateMetadataFieldFormValues
  onSubmit: (values: UpdateMetadataFieldFormValues) => Promise<string | null>
}

/** Edit Field — updates a field's descriptive/behavioral attributes; fieldKey/dataType stay fixed. */
export function UpdateMetadataFieldForm({ defaultValues, onSubmit }: EditFieldFormProps) {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    control,
    formState: { errors, isSubmitting },
  } = useForm<UpdateMetadataFieldFormValues>({
    resolver: zodResolver(updateMetadataFieldSchema),
    defaultValues,
  })

  async function handleFormSubmit(values: UpdateMetadataFieldFormValues) {
    setSubmitError(null)
    const error = await onSubmit(values)
    if (error) setSubmitError(error)
  }

  return (
    <form
      onSubmit={handleSubmit(handleFormSubmit)}
      className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]"
    >
      {submitError && (
        <div className="mb-6 flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <span>{submitError}</span>
        </div>
      )}

      <div className="mb-5">
        <Label htmlFor="field-label">Label</Label>
        <Input id="field-label" aria-invalid={Boolean(errors.label)} {...register("label")} />
        {errors.label && <p className="mt-1.5 text-[12px] text-destructive">{errors.label.message}</p>}
      </div>

      <div className="mb-5">
        <Label htmlFor="field-help-text">
          Help Text <span className="text-[var(--ink-faint)] normal-case">(optional)</span>
        </Label>
        <Textarea id="field-help-text" {...register("helpText")} />
      </div>

      <div className="mb-6">
        <Label htmlFor="field-default-value">
          Default Value <span className="text-[var(--ink-faint)] normal-case">(optional)</span>
        </Label>
        <Input id="field-default-value" {...register("defaultValue")} />
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="mb-1">
        <h2 className="font-heading text-[16px] font-semibold">Behavior</h2>
      </div>
      <div className="divide-y divide-dashed divide-border">
        {FLAG_FIELDS.map(({ name, label, hint }) => (
          <Controller
            key={name}
            name={name}
            control={control}
            render={({ field }) => (
              <Checkbox label={`${label} — ${hint}`} checked={field.value} onCheckedChange={field.onChange} />
            )}
          />
        ))}
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="flex items-center justify-end gap-2.5">
        <button
          type="button"
          onClick={() => router.push("/admin/metadata/fields")}
          className="rounded-btn border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting}
          className={cn(
            "rounded-btn border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground",
            "disabled:cursor-not-allowed disabled:opacity-60"
          )}
        >
          Save Changes
        </button>
      </div>
    </form>
  )
}
