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
  createDocumentTypeSchema,
  updateDocumentTypeSchema,
  type CreateDocumentTypeFormValues,
  type UpdateDocumentTypeFormValues,
} from "./schema"

interface CreateFormProps {
  onSubmit: (values: CreateDocumentTypeFormValues) => Promise<string | null>
}

/** Add Document Type (A5). */
export function CreateDocumentTypeForm({ onSubmit }: CreateFormProps) {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const { register, handleSubmit, control, formState: { errors, isSubmitting } } = useForm<CreateDocumentTypeFormValues>({
    resolver: zodResolver(createDocumentTypeSchema),
    defaultValues: { typeCode: "", typeName: "", description: "", icon: "", displayOrder: 0, requiresWatermark: false, coeditingEnabled: false },
  })

  async function handleFormSubmit(values: CreateDocumentTypeFormValues) {
    setSubmitError(null)
    const error = await onSubmit(values)
    if (error) setSubmitError(error)
  }

  return (
    <form onSubmit={handleSubmit(handleFormSubmit)} className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {submitError && (
        <div className="mb-6 flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <span>{submitError}</span>
        </div>
      )}

      <div className="mb-5 flex flex-col gap-5 sm:flex-row sm:items-start">
        <div className="flex-1">
          <Label htmlFor="type-code">Type Code <span className="text-[var(--ink-faint)] normal-case">(cannot be changed later)</span></Label>
          <Input id="type-code" placeholder="e.g. CONTRACT" aria-invalid={Boolean(errors.typeCode)} {...register("typeCode")} />
          {errors.typeCode && <p className="mt-1.5 text-[12px] text-destructive">{errors.typeCode.message}</p>}
        </div>
        <div className="sm:w-[140px] sm:shrink-0">
          <Label htmlFor="display-order">Display Order</Label>
          <Input id="display-order" type="number" {...register("displayOrder", { valueAsNumber: true })} />
        </div>
      </div>

      <div className="mb-5">
        <Label htmlFor="type-name">Name</Label>
        <Input id="type-name" placeholder="e.g. Contract" aria-invalid={Boolean(errors.typeName)} {...register("typeName")} />
        {errors.typeName && <p className="mt-1.5 text-[12px] text-destructive">{errors.typeName.message}</p>}
      </div>

      <div className="mb-5">
        <Label htmlFor="type-description">Description <span className="text-[var(--ink-faint)] normal-case">(optional)</span></Label>
        <Textarea id="type-description" {...register("description")} />
      </div>

      <div className="mb-6">
        <Label htmlFor="type-icon">Icon <span className="text-[var(--ink-faint)] normal-case">(optional, e.g. file-signature)</span></Label>
        <Input id="type-icon" {...register("icon")} />
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="divide-y divide-dashed divide-border">
        <Controller name="requiresWatermark" control={control} render={({ field }) => (
          <Checkbox label="Requires Watermark — applied automatically on preview/download" checked={field.value} onCheckedChange={field.onChange} />
        )} />
        <Controller name="coeditingEnabled" control={control} render={({ field }) => (
          <Checkbox label="Co-editing Enabled — allows collaborative check-in/check-out" checked={field.value} onCheckedChange={field.onChange} />
        )} />
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="flex items-center justify-end gap-2.5">
        <button type="button" onClick={() => router.push("/admin/metadata/document-types")} className="rounded-btn border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
          Cancel
        </button>
        <button type="submit" disabled={isSubmitting} className={cn("rounded-btn border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground", "disabled:cursor-not-allowed disabled:opacity-60")}>
          Create Document Type
        </button>
      </div>
    </form>
  )
}

interface EditFormProps {
  defaultValues: UpdateDocumentTypeFormValues
  onSubmit: (values: UpdateDocumentTypeFormValues) => Promise<string | null>
}

/** Edit Document Type (A5) — descriptive/behavioral attributes; typeCode stays fixed. */
export function UpdateDocumentTypeForm({ defaultValues, onSubmit }: EditFormProps) {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const { register, handleSubmit, control, formState: { errors, isSubmitting } } = useForm<UpdateDocumentTypeFormValues>({
    resolver: zodResolver(updateDocumentTypeSchema),
    defaultValues,
  })

  async function handleFormSubmit(values: UpdateDocumentTypeFormValues) {
    setSubmitError(null)
    const error = await onSubmit(values)
    if (error) setSubmitError(error)
  }

  return (
    <form onSubmit={handleSubmit(handleFormSubmit)} className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {submitError && (
        <div className="mb-6 flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <span>{submitError}</span>
        </div>
      )}

      <div className="mb-5 flex flex-col gap-5 sm:flex-row sm:items-start">
        <div className="flex-1">
          <Label htmlFor="type-name">Name</Label>
          <Input id="type-name" aria-invalid={Boolean(errors.typeName)} {...register("typeName")} />
          {errors.typeName && <p className="mt-1.5 text-[12px] text-destructive">{errors.typeName.message}</p>}
        </div>
        <div className="sm:w-[140px] sm:shrink-0">
          <Label htmlFor="display-order">Display Order</Label>
          <Input id="display-order" type="number" {...register("displayOrder", { valueAsNumber: true })} />
        </div>
      </div>

      <div className="mb-5">
        <Label htmlFor="type-description">Description <span className="text-[var(--ink-faint)] normal-case">(optional)</span></Label>
        <Textarea id="type-description" {...register("description")} />
      </div>

      <div className="mb-6">
        <Label htmlFor="type-icon">Icon <span className="text-[var(--ink-faint)] normal-case">(optional)</span></Label>
        <Input id="type-icon" {...register("icon")} />
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="divide-y divide-dashed divide-border">
        <Controller name="requiresWatermark" control={control} render={({ field }) => (
          <Checkbox label="Requires Watermark — applied automatically on preview/download" checked={field.value} onCheckedChange={field.onChange} />
        )} />
        <Controller name="coeditingEnabled" control={control} render={({ field }) => (
          <Checkbox label="Co-editing Enabled — allows collaborative check-in/check-out" checked={field.value} onCheckedChange={field.onChange} />
        )} />
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="flex items-center justify-end gap-2.5">
        <button type="button" onClick={() => router.push("/admin/metadata/document-types")} className="rounded-btn border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
          Cancel
        </button>
        <button type="submit" disabled={isSubmitting} className={cn("rounded-btn border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground", "disabled:cursor-not-allowed disabled:opacity-60")}>
          Save Changes
        </button>
      </div>
    </form>
  )
}
