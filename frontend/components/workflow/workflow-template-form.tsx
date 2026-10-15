"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm, useWatch, type Control, type FieldErrors, type UseFormRegister } from "react-hook-form"
import { AlertTriangle, ChevronDown } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Textarea } from "@/components/ui/textarea"
import { useDocumentTypeOptions, useUserOptions } from "@/lib/workflow/use-participant-options"
import { cn } from "@/lib/utils"

import { HoursInput, ReminderHelp } from "./reminder-fields"
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
    defaultValues: {
      name: "",
      code: "",
      description: "",
      documentTypeId: "",
      onDocumentChange: "flag_only",
      defaultReminderBeforeHours: null,
      defaultInactivityReminderHours: null,
      defaultEscalateAfterReminderHours: null,
      defaultEscalationTarget: "none",
      defaultEscalationUserId: null,
    },
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

      <ReminderDefaults control={ctl} errors={errors} />
    </>
  )
}

const ESCALATION_OPTIONS = [
  { value: "none" as const, label: "None" },
  { value: "department_head" as const, label: "Department head" },
  { value: "named_user" as const, label: "Named person" },
]

const ESCALATION_HELP = {
  none: "No default escalation. A stage can still set its own target.",
  department_head:
    "Stages with no target of their own escalate to the department head (a department pool task: that department's head; otherwise the head of the submitter's department).",
  named_user: "Stages with no target of their own escalate to this person.",
}

/**
 * The template's reminder and escalation defaults (3.2, N-FR-006 - per workflow type). Every stage uses them unless
 * it sets its own value on its stage card; the stage cards show what they inherit from here.
 */
function ReminderDefaults({
  control,
  errors,
}: {
  control: Control<UpdateTemplateFormValues>
  errors: FieldErrors<UpdateTemplateFormValues>
}) {
  const target = useWatch({ control, name: "defaultEscalationTarget" })
  const users = useUserOptions(target === "named_user")

  return (
    <div className="mt-6 flex flex-col gap-4 rounded-lg border border-dashed border-border p-4">
      <div>
        <p className="text-[12px] font-bold uppercase tracking-[0.08em] text-muted-foreground">
          Reminder &amp; escalation defaults
        </p>
        <p className="mt-1 text-[12.5px] text-muted-foreground">
          Used by every stage of this template unless the stage sets its own. Leave a box empty for no default.
        </p>
      </div>
      <ReminderHelp />

      <div className="grid gap-4 sm:grid-cols-3">
        <Controller
          name="defaultReminderBeforeHours"
          control={control}
          render={({ field }) => (
            <div>
              <Label htmlFor="template-default-reminder">Due-date reminder: hours before due</Label>
              <HoursInput
                id="template-default-reminder"
                max={8759}
                value={field.value}
                onChange={field.onChange}
                invalid={Boolean(errors.defaultReminderBeforeHours)}
              />
              <p className="mt-1.5 text-[12px] text-muted-foreground">
                Only for stages with SLA hours, and only when shorter than the stage&apos;s SLA.
              </p>
              {errors.defaultReminderBeforeHours && <FieldError message={errors.defaultReminderBeforeHours.message} />}
            </div>
          )}
        />
        <Controller
          name="defaultInactivityReminderHours"
          control={control}
          render={({ field }) => (
            <div>
              <Label htmlFor="template-default-inactivity">Inactivity reminder: hours without activity</Label>
              <HoursInput
                id="template-default-inactivity"
                value={field.value}
                onChange={field.onChange}
                invalid={Boolean(errors.defaultInactivityReminderHours)}
              />
              {errors.defaultInactivityReminderHours && <FieldError message={errors.defaultInactivityReminderHours.message} />}
            </div>
          )}
        />
        <Controller
          name="defaultEscalateAfterReminderHours"
          control={control}
          render={({ field }) => (
            <div>
              <Label htmlFor="template-default-escalate-after">Escalate after the inactivity reminder: hours</Label>
              <HoursInput
                id="template-default-escalate-after"
                value={field.value}
                onChange={field.onChange}
                invalid={Boolean(errors.defaultEscalateAfterReminderHours)}
              />
              {errors.defaultEscalateAfterReminderHours && (
                <FieldError message={errors.defaultEscalateAfterReminderHours.message} />
              )}
            </div>
          )}
        />
      </div>

      <div>
        <Label>Default escalation target</Label>
        <Controller
          name="defaultEscalationTarget"
          control={control}
          render={({ field }) => (
            <SegmentedControl value={field.value} onChange={field.onChange} options={ESCALATION_OPTIONS} />
          )}
        />
        <p className="mt-2 text-[12.5px] text-muted-foreground">{ESCALATION_HELP[target]}</p>
        {target === "named_user" && (
          <Controller
            name="defaultEscalationUserId"
            control={control}
            render={({ field }) => (
              <div className="mt-3 sm:w-[320px]">
                <SearchableSelect
                  options={(users.data ?? []).map((u) => ({ value: u.value, label: u.hint ? `${u.label} (${u.hint})` : u.label }))}
                  selected={field.value ?? ""}
                  onChange={(value) => field.onChange(value || null)}
                  allLabel={users.isPending ? "Loading people…" : "Choose a person"}
                  searchPlaceholder="Search people"
                />
                {errors.defaultEscalationUserId && <FieldError message={errors.defaultEscalationUserId.message} />}
              </div>
            )}
          />
        )}
      </div>
    </div>
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
