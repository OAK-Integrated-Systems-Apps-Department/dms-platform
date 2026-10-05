"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import { AlertTriangle, ChevronDown } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { useAllDepartments } from "@/lib/departments/use-departments"
import { useUserOptions } from "@/lib/workflow/use-participant-options"
import { cn } from "@/lib/utils"

import {
  createDepartmentSchema,
  updateDepartmentSchema,
  type CreateDepartmentFormValues,
  type UpdateDepartmentFormValues,
} from "./schema"

const SELECT_CLASS =
  "h-10 w-full appearance-none rounded-btn border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card disabled:cursor-not-allowed disabled:opacity-60"

const STATUS_OPTIONS = [
  { value: "active" as const, label: "Active" },
  { value: "inactive" as const, label: "Inactive" },
]

interface CreateFormProps {
  onSubmit: (values: CreateDepartmentFormValues) => Promise<string | null>
}

/** Add Department. */
export function CreateDepartmentForm({ onSubmit }: CreateFormProps) {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const { register, handleSubmit, control, formState: { errors, isSubmitting, isValid } } = useForm<CreateDepartmentFormValues>({
    resolver: zodResolver(createDepartmentSchema),
    defaultValues: { name: "", code: "", parentDepartmentId: "", headUserId: "" },
    mode: "onChange",
  })

  async function handleFormSubmit(values: CreateDepartmentFormValues) {
    setSubmitError(null)
    const error = await onSubmit(values)
    if (error) setSubmitError(error)
  }

  return (
    <form onSubmit={handleSubmit(handleFormSubmit)} className="max-w-[760px] rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {submitError && <SubmitError message={submitError} />}

      <div className="mb-5 flex flex-col gap-5 sm:flex-row sm:items-start">
        <div className="flex-1">
          <Label htmlFor="department-name">Name</Label>
          <Input id="department-name" placeholder="e.g. Finance" aria-invalid={Boolean(errors.name)} {...register("name")} />
          {errors.name && <FieldError message={errors.name.message} />}
        </div>
        <div className="sm:w-[220px] sm:shrink-0">
          <Label htmlFor="department-code">Code <span className="text-[var(--ink-faint)] normal-case">(cannot be changed later)</span></Label>
          <Input id="department-code" placeholder="e.g. FIN" className="font-mono uppercase" aria-invalid={Boolean(errors.code)} {...register("code")} />
          {errors.code && <FieldError message={errors.code.message} />}
        </div>
      </div>

      <div className="mb-5 grid gap-5 sm:grid-cols-2">
        <Controller name="parentDepartmentId" control={control} render={({ field }) => (
          <ParentSelect value={field.value} onChange={field.onChange} />
        )} />
        <Controller name="headUserId" control={control} render={({ field }) => (
          <HeadSelect value={field.value} onChange={field.onChange} />
        )} />
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="flex items-center justify-end gap-2.5">
        <button type="button" onClick={() => router.push("/admin/departments")} className="rounded-btn border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
          Cancel
        </button>
        <button type="submit" disabled={isSubmitting || !isValid} className={cn("rounded-btn border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground", "disabled:cursor-not-allowed disabled:opacity-60")}>
          Create Department
        </button>
      </div>
    </form>
  )
}

interface EditFormProps {
  departmentId: string
  headUserName: string | null
  defaultValues: UpdateDepartmentFormValues
  onSubmit: (values: UpdateDepartmentFormValues) => Promise<string | null>
}

/** Edit Department - name, parent, head and status. The code stays fixed. */
export function UpdateDepartmentForm({ departmentId, headUserName, defaultValues, onSubmit }: EditFormProps) {
  const router = useRouter()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const { register, handleSubmit, control, formState: { errors, isSubmitting, isValid } } = useForm<UpdateDepartmentFormValues>({
    resolver: zodResolver(updateDepartmentSchema),
    defaultValues,
    mode: "onChange",
  })

  async function handleFormSubmit(values: UpdateDepartmentFormValues) {
    setSubmitError(null)
    const error = await onSubmit(values)
    if (error) setSubmitError(error)
  }

  return (
    <form onSubmit={handleSubmit(handleFormSubmit)} className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {submitError && <SubmitError message={submitError} />}

      <div className="mb-5">
        <Label htmlFor="department-name">Name</Label>
        <Input id="department-name" aria-invalid={Boolean(errors.name)} {...register("name")} />
        {errors.name && <FieldError message={errors.name.message} />}
      </div>

      <div className="mb-5 grid gap-5 sm:grid-cols-2">
        <Controller name="parentDepartmentId" control={control} render={({ field }) => (
          <ParentSelect value={field.value} onChange={field.onChange} excludeId={departmentId} />
        )} />
        <Controller name="headUserId" control={control} render={({ field }) => (
          <HeadSelect value={field.value} onChange={field.onChange} currentLabel={headUserName} />
        )} />
      </div>

      <div>
        <Label>Status</Label>
        <Controller name="status" control={control} render={({ field }) => (
          <SegmentedControl value={field.value} onChange={field.onChange} options={STATUS_OPTIONS} />
        )} />
        <p className="mt-2 text-[12.5px] text-muted-foreground">
          An inactive department can&apos;t be picked for new workflow stages or assigned to users.
        </p>
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="flex items-center justify-end gap-2.5">
        <button type="button" onClick={() => router.push("/admin/departments")} className="rounded-btn border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground">
          Cancel
        </button>
        <button type="submit" disabled={isSubmitting || !isValid} className={cn("rounded-btn border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground", "disabled:cursor-not-allowed disabled:opacity-60")}>
          Save Changes
        </button>
      </div>
    </form>
  )
}

/** Parent department picker - "" is no parent. Excludes the department being edited (the backend also rejects cycles). */
function ParentSelect({ value, onChange, excludeId }: { value: string; onChange: (value: string) => void; excludeId?: string }) {
  const { data, isPending, isError } = useAllDepartments()
  const options = (data ?? []).filter((d) => d.departmentId !== excludeId && (d.status === "active" || d.departmentId === value))
  return (
    <div>
      <Label htmlFor="department-parent">Parent Department <span className="text-[var(--ink-faint)] normal-case">(optional)</span></Label>
      <NativeSelect id="department-parent" value={value} onChange={onChange} disabled={isPending}>
        <option value="">{isPending ? "Loading…" : "None (top level)"}</option>
        {options.map((d) => (
          <option key={d.departmentId} value={d.departmentId}>{d.name}</option>
        ))}
      </NativeSelect>
      {isError && <FieldError message="Could not load departments." />}
    </div>
  )
}

/** Department head picker - "" is no head. Uses the admin user list, which needs a user-management permission. */
function HeadSelect({ value, onChange, currentLabel }: { value: string; onChange: (value: string) => void; currentLabel?: string | null }) {
  const { data, isPending, isError } = useUserOptions()
  // Keep the saved head selectable even when the user list can't load (or
  // no longer lists them), so saving other fields never clears it silently.
  const missingCurrent = Boolean(value) && !(data ?? []).some((u) => u.value === value)
  return (
    <div>
      <Label htmlFor="department-head">Department Head <span className="text-[var(--ink-faint)] normal-case">(optional)</span></Label>
      <NativeSelect id="department-head" value={value} onChange={onChange} disabled={isPending}>
        <option value="">{isPending ? "Loading…" : "No head"}</option>
        {missingCurrent && <option value={value}>{currentLabel ?? "Current head"}</option>}
        {(data ?? []).map((u) => (
          <option key={u.value} value={u.value}>{u.label}{u.hint ? ` (${u.hint})` : ""}</option>
        ))}
      </NativeSelect>
      {isError && <FieldError message="Could not load users - you may need a user management permission." />}
    </div>
  )
}

function NativeSelect({
  id,
  value,
  onChange,
  disabled,
  children,
}: {
  id: string
  value: string
  onChange: (value: string) => void
  disabled?: boolean
  children: React.ReactNode
}) {
  return (
    <div className="relative">
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} disabled={disabled} className={SELECT_CLASS}>
        {children}
      </select>
      <ChevronDown size={14} className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]" />
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
