"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm, useWatch, type Control, type FieldErrors, type UseFormRegister } from "react-hook-form"
import { AlertTriangle, ChevronDown } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Textarea } from "@/components/ui/textarea"
import { useDocumentTypeOptions } from "@/lib/workflow/use-participant-options"
import { cn } from "@/lib/utils"

import {
  createTemplateSchema,
  updateTemplateSchema,
  type CreateTemplateFormValues,
  type UpdateTemplateFormValues,
} from "./schema"

const ON_CHANGE_OPTIONS = [
  { value: "flag_only" as const, label: "Flag only" },
  { value: "restart" as const, label: "Restart approval" },
]

const ON_CHANGE_HELP = {
  flag_only:
    "If a new version of the document is uploaded mid-approval, the workflow keeps going and approvers see a \"version changed\" warning.",
  restart:
    "If a new version of the document is uploaded mid-approval, every approval so far is discarded and the workflow starts again from stage 1.",
}

const SELECT_CLASS =
  "h-10 w-full appearance-none rounded-btn border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card disabled:cursor-not-allowed disabled:opacity-60"

interface CreateFormProps {
  onSubmit: (values: CreateTemplateFormValues) => Promise<string | null>
}

/** New Workflow Template - creates version 1 as a draft; stages are added on the template page afterwards. */
export function CreateWorkflowTemplateForm({ onSubmit }: CreateFormProps) {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const { register, handleSubmit, control, formState: { errors, isSubmitting, isValid } } = useForm<CreateTemplateFormValues>({
    resolver: zodResolver(createTemplateSchema),
    defaultValues: { name: "", code: "", description: "", documentTypeId: "", onDocumentChange: "flag_only" },
    mode: "onChange",
  })

  async function handleFormSubmit(values: CreateTemplateFormValues) {
    setSubmitError(null)
    const error = await onSubmit(values)
    if (error) setSubmitError(error)
  }

  return (
    <form onSubmit={handleSubmit(handleFormSubmit)} className="max-w-[760px] rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {submitError && <SubmitError message={submitError} />}

      <div className="mb-5 flex flex-col gap-5 sm:flex-row sm:items-start">
        <div className="flex-1">
          <Label htmlFor="template-name">Name</Label>
          <Input id="template-name" placeholder="e.g. Contract approval" aria-invalid={Boolean(errors.name)} {...register("name")} />
          {errors.name && <FieldError message={errors.name.message} />}
        </div>
        <div className="sm:w-[220px] sm:shrink-0">
          <Label htmlFor="template-code">Code <span className="text-[var(--ink-faint)] normal-case">(cannot be changed later)</span></Label>
          <Input id="template-code" placeholder="e.g. CONTRACT_APPROVAL" className="font-mono uppercase" aria-invalid={Boolean(errors.code)} {...register("code")} />
          {errors.code && <FieldError message={errors.code.message} />}
        </div>
      </div>

      <SharedFields register={register} control={control} errors={errors} />

      <hr className="my-6 border-dashed border-border" />

      <div className="flex items-center justify-end gap-2.5">
        <button type="button" onClick={() => router.push("/admin/workflows")} className="rounded-btn border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
          Cancel
        </button>
        <button type="submit" disabled={isSubmitting || !isValid} className={cn("rounded-btn border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground", "disabled:cursor-not-allowed disabled:opacity-60")}>
          Create Template
        </button>
      </div>
    </form>
  )
}

interface DetailsFormProps {
  defaultValues: UpdateTemplateFormValues
  /** Only version 1 may change its document type - later versions keep their family's type. */
  documentTypeLocked: boolean
  onSubmit: (values: UpdateTemplateFormValues) => Promise<boolean>
}

/** A draft's editable details on the template page. Code and version are fixed. */
export function WorkflowTemplateDetailsForm({ defaultValues, documentTypeLocked, onSubmit }: DetailsFormProps) {
  const { register, handleSubmit, control, reset, formState: { errors, isSubmitting, isValid, isDirty } } = useForm<UpdateTemplateFormValues>({
    resolver: zodResolver(updateTemplateSchema),
    defaultValues,
    mode: "onChange",
  })

  async function handleFormSubmit(values: UpdateTemplateFormValues) {
    const ok = await onSubmit(values)
    if (ok) reset(values)
  }

  return (
    <form onSubmit={handleSubmit(handleFormSubmit)}>
      <div className="mb-5">
        <Label htmlFor="template-name">Name</Label>
        <Input id="template-name" aria-invalid={Boolean(errors.name)} {...register("name")} />
        {errors.name && <FieldError message={errors.name.message} />}
      </div>

      <SharedFields
        register={register}
        control={control}
        errors={errors}
        documentTypeLocked={documentTypeLocked}
      />

      <div className="mt-5 flex items-center justify-end gap-2.5">
        <button
          type="button"
          disabled={!isDirty || isSubmitting}
          onClick={() => reset(defaultValues)}
          className="rounded-btn border border-[var(--border-strong)] bg-card px-4 py-2 text-[13px] font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-60"
        >
          Discard
        </button>
        <button type="submit" disabled={isSubmitting || !isValid || !isDirty} className="rounded-btn border border-primary bg-primary px-[18px] py-2 text-[13px] font-bold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60">
          Save Details
        </button>
      </div>
    </form>
  )
}

/** Description, document type and on-document-change - the fields both forms share. */
function SharedFields({
  register,
  control,
  errors,
  documentTypeLocked = false,
}: {
  // Both forms' values contain these four fields; the cast keeps one renderer for both.
  register: UseFormRegister<CreateTemplateFormValues> | UseFormRegister<UpdateTemplateFormValues>
  control: Control<CreateTemplateFormValues> | Control<UpdateTemplateFormValues>
  errors: FieldErrors<UpdateTemplateFormValues>
  documentTypeLocked?: boolean
}) {
  const reg = register as UseFormRegister<UpdateTemplateFormValues>
  const ctl = control as Control<UpdateTemplateFormValues>
  const onDocumentChange = useWatch({ control: ctl, name: "onDocumentChange" })
  const { data: documentTypes = [], isPending: isLoading, isError } = useDocumentTypeOptions()

  return (
    <>
      <div className="mb-5">
        <Label htmlFor="template-description">Description <span className="text-[var(--ink-faint)] normal-case">(optional)</span></Label>
        <Textarea id="template-description" rows={3} className="min-h-[80px]" {...reg("description")} />
        {errors.description && <FieldError message={errors.description.message} />}
      </div>

      <div className="mb-5">
        <Label htmlFor="template-document-type">Document Type</Label>
        <Controller
          name="documentTypeId"
          control={ctl}
          render={({ field }) => (
            <div className="relative">
              <select
                id="template-document-type"
                value={field.value}
                onChange={(e) => field.onChange(e.target.value)}
                onBlur={field.onBlur}
                disabled={documentTypeLocked || isLoading}
                className={SELECT_CLASS}
              >
                <option value="">{isLoading ? "Loading document types…" : "Select a document type…"}</option>
                {documentTypes.map((type) => (
                  <option key={type.value} value={type.value}>
                    {type.label}
                  </option>
                ))}
              </select>
              <ChevronDown size={14} className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]" />
            </div>
          )}
        />
        {isError && <FieldError message="Could not load document types." />}
        {errors.documentTypeId && <FieldError message={errors.documentTypeId.message} />}
        {documentTypeLocked && (
          <p className="mt-1.5 text-[12px] text-muted-foreground">Only version 1 can change its document type.</p>
        )}
      </div>

      <div>
        <Label>When the document changes mid-approval</Label>
        <Controller
          name="onDocumentChange"
          control={ctl}
          render={({ field }) => <SegmentedControl value={field.value} onChange={field.onChange} options={ON_CHANGE_OPTIONS} />}
        />
        <p className="mt-2 text-[12.5px] leading-[1.55] text-muted-foreground">{ON_CHANGE_HELP[onDocumentChange]}</p>
      </div>
    </>
  )
}

function FieldError({ message }: { message?: string }) {
  return <p className="mt-1.5 text-[12px] text-destructive">{message}</p>
}

function SubmitError({ message }: { message: string }) {
  return (
    <div className="mb-6 flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
      <AlertTriangle size={14} className="mt-0.5 shrink-0" />
      <span>{message}</span>
    </div>
  )
}
