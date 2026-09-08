"use client"

import { useEffect, useMemo, useState } from "react"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import { AlertTriangle, ChevronDown, Loader2 } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { usePermissionsStore } from "@/stores/permissions-store"

import { permissionFormSchema, type PermissionFormValues } from "./schema"
import type { PermissionRecord } from "./types"

interface PermissionFormProps {
  mode: "create" | "edit"
  /** Pass the existing permission when editing — its code is shown read-only. */
  permission?: PermissionRecord
  /** Resolves with an error message on failure (shown inline, form stays filled in), or null on success. */
  onSubmit: (values: PermissionFormValues) => Promise<string | null>
}

const selectClassName =
  "h-[38px] w-full appearance-none rounded-[9px] border border-border bg-muted px-3 pr-9 text-[13.5px] font-medium text-foreground outline-none focus-visible:border-primary focus-visible:bg-card disabled:cursor-not-allowed disabled:opacity-60"

/**
 * Add/Edit Permission form. Every permission belongs to exactly one
 * {@link PermissionAction}, which belongs to exactly one
 * {@link PermissionModule} (see `Permission.java`) — the backend only
 * needs `actionId`, but choosing an action out of every action across
 * every module in one flat list isn't usable at real catalog sizes, so
 * this renders it as two dependent selects: pick a module, then pick one
 * of that module's actions. Changing the module clears any action picked
 * under the previous one, since it's very likely no longer valid.
 */
export function PermissionForm({ mode, permission, onSubmit }: PermissionFormProps) {
  const router = useRouter()
  const modules = usePermissionsStore((state) => state.modules)
  const actions = usePermissionsStore((state) => state.actions)
  const catalogLoaded = usePermissionsStore((state) => state.catalogLoaded)
  const fetchCatalog = usePermissionsStore((state) => state.fetchCatalog)
  const [submitError, setSubmitError] = useState<string | null>(null)

  useEffect(() => {
    if (!catalogLoaded) fetchCatalog()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const {
    register,
    handleSubmit,
    control,
    watch,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<PermissionFormValues>({
    resolver: zodResolver(permissionFormSchema),
    defaultValues: {
      code: permission?.code ?? "",
      name: permission?.name ?? "",
      description: permission?.description ?? "",
      moduleId: permission?.moduleId ?? "",
      actionId: permission?.actionId ?? "",
    },
  })

  const selectedModuleId = watch("moduleId")
  const actionsForModule = useMemo(
    () => actions.filter((a) => a.moduleId === selectedModuleId),
    [actions, selectedModuleId]
  )

  async function handleFormSubmit(values: PermissionFormValues) {
    setSubmitError(null)
    const error = await onSubmit(values)
    if (error) setSubmitError(error)
  }

  const catalogLoading = !catalogLoaded && modules.length === 0

  return (
    <form
      onSubmit={handleSubmit(handleFormSubmit)}
      className="rounded-[14px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]"
    >
      {submitError && (
        <div className="mb-6 flex items-start gap-2 rounded-[9px] border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <span>{submitError}</span>
        </div>
      )}

      <div className="mb-5">
        <Label htmlFor="permission-code">Permission Code</Label>
        {mode === "edit" ? (
          <>
            <Input id="permission-code" value={permission?.code ?? ""} disabled readOnly />
            <p className="mt-1.5 text-[12px] text-muted-foreground">
              The code is checked throughout the system and can&apos;t be
              changed once created. Retire this permission and create a new
              one if the code itself needs to change.
            </p>
          </>
        ) : (
          <>
            <Input
              id="permission-code"
              placeholder="e.g. document.approve"
              aria-invalid={Boolean(errors.code)}
              {...register("code")}
            />
            <p className="mt-1.5 text-[12px] text-muted-foreground">
              Dot-separated, lowercase — this is what code checks against,
              e.g. <code className="font-mono">document.approve</code>.
            </p>
          </>
        )}
        {errors.code && (
          <p className="mt-1.5 text-[12px] text-destructive">{errors.code.message}</p>
        )}
      </div>

      <div className="mb-5">
        <Label htmlFor="permission-name">Name</Label>
        <Input
          id="permission-name"
          placeholder="e.g. Approve Documents"
          aria-invalid={Boolean(errors.name)}
          {...register("name")}
        />
        {errors.name && (
          <p className="mt-1.5 text-[12px] text-destructive">{errors.name.message}</p>
        )}
      </div>

      <div className="mb-6">
        <Label htmlFor="permission-description">
          Description{" "}
          <span className="text-[var(--ink-faint)] normal-case">(optional)</span>
        </Label>
        <Textarea
          id="permission-description"
          placeholder="What this permission allows and when it should be granted…"
          aria-invalid={Boolean(errors.description)}
          {...register("description")}
        />
        {errors.description && (
          <p className="mt-1.5 text-[12px] text-destructive">
            {errors.description.message}
          </p>
        )}
      </div>

      <hr className="my-6 border-dashed border-border" />

      <h2 className="mb-1 font-heading text-[16px] font-semibold">
        Module &amp; Action
      </h2>
      <p className="mb-4 text-[12.5px] text-muted-foreground">
        Every permission belongs to one action under one module — this is
        what groups it on Role Permission Configuration and the Permission
        Matrix.
      </p>

      {catalogLoading ? (
        <div className="flex items-center gap-2 rounded-[9px] border border-dashed border-border py-6 text-[13px] text-muted-foreground">
          <Loader2 size={15} className="ml-4 animate-spin" />
          Loading modules and actions…
        </div>
      ) : (
        <div className="grid gap-5 sm:grid-cols-2">
          <div>
            <Label htmlFor="permission-module">Module</Label>
            <Controller
              name="moduleId"
              control={control}
              render={({ field }) => (
                <div className="relative">
                  <select
                    id="permission-module"
                    value={field.value}
                    onChange={(event) => {
                      // Explicit shouldValidate, not field.onChange — before
                      // the form's first submit, RHF only auto-revalidates
                      // fields that already have an error, so a plain
                      // onChange here wouldn't clear a stale "Choose a
                      // module" error either. Same reasoning as the action
                      // select below.
                      setValue("moduleId", event.target.value, { shouldValidate: true })
                      // The previously-selected action almost certainly
                      // doesn't belong to the newly-chosen module — clear
                      // it rather than silently submitting a mismatched
                      // module/action pair.
                      setValue("actionId", "", { shouldValidate: true })
                    }}
                    aria-invalid={Boolean(errors.moduleId)}
                    className={selectClassName}
                  >
                    <option value="" disabled>
                      Choose a module…
                    </option>
                    {modules.map((m) => (
                      <option key={m.id} value={m.id}>
                        {m.code} — {m.name}
                      </option>
                    ))}
                  </select>
                  <ChevronDown
                    size={14}
                    className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
                  />
                </div>
              )}
            />
            {errors.moduleId && (
              <p className="mt-1.5 text-[12px] text-destructive">
                {errors.moduleId.message}
              </p>
            )}
          </div>

          <div>
            <Label htmlFor="permission-action">Action</Label>
            <Controller
              name="actionId"
              control={control}
              render={({ field }) => (
                <div className="relative">
                  <select
                    id="permission-action"
                    value={field.value}
                    onChange={(event) =>
                      // setValue(..., { shouldValidate: true }) rather than
                      // field.onChange — picking a module resets actionId
                      // via an explicit validated setValue (see above),
                      // which sets the "Choose an action" error. RHF only
                      // auto-revalidates a field on plain onChange once the
                      // form has been submitted at least once, so before
                      // that, a bare field.onChange here would update the
                      // value but leave that stale error on screen even
                      // once a real action is picked.
                      setValue("actionId", event.target.value, { shouldValidate: true })
                    }
                    disabled={!selectedModuleId}
                    aria-invalid={Boolean(errors.actionId)}
                    className={selectClassName}
                  >
                    <option value="" disabled>
                      {selectedModuleId ? "Choose an action…" : "Choose a module first"}
                    </option>
                    {actionsForModule.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.code} — {a.name}
                      </option>
                    ))}
                  </select>
                  <ChevronDown
                    size={14}
                    className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
                  />
                </div>
              )}
            />
            {errors.actionId && (
              <p className="mt-1.5 text-[12px] text-destructive">
                {errors.actionId.message}
              </p>
            )}
            {selectedModuleId && actionsForModule.length === 0 && !errors.actionId && (
              <p className="mt-1.5 text-[12px] text-muted-foreground">
                This module has no actions yet.
              </p>
            )}
          </div>
        </div>
      )}

      <hr className="my-6 border-dashed border-border" />

      <div className="flex items-center justify-end gap-2.5">
        <button
          type="button"
          onClick={() => router.push("/admin/permissions")}
          className="rounded-[9px] border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting || catalogLoading}
          className="rounded-[9px] border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
        >
          {mode === "create" ? "Create Permission" : "Save Changes"}
        </button>
      </div>
    </form>
  )
}