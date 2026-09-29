"use client"

import { useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import { AlertTriangle } from "lucide-react"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"

import { PermissionPicker } from "./permission-picker"
import { formatRoleNameForDisplay } from "./role-utils"
import { roleFormSchema, type RoleFormValues } from "./schema"
import type { RoleRecord } from "./types"
import { usePermissionCatalog } from "./use-permission-catalog"

interface RoleFormProps {
  mode: "create" | "edit"
  /** Pass the existing role when editing — its id, `assignedUsers`, and `system` flag are preserved. */
  role?: RoleRecord
  /** Resolves with an error message on failure (shown inline, form stays filled in), or null on success. */
  onSubmit: (values: RoleFormValues) => Promise<string | null>
}

/**
 * Add/Edit Role form. Combines the R2 (Add/Edit Role) and R3 (Role
 * Permission Configuration) wireframe screens into a single page — see
 * the note in the chat response for why. The permission picker itself is
 * `PermissionPicker`, shared with `RolePermissionConfig` so both screens
 * get the same search/expand-collapse/select-all behavior instead of
 * this form maintaining its own simplified copy.
 */
export function RoleForm({ mode, role, onSubmit }: RoleFormProps) {
  const router = useRouter()
  const isSystemRole = Boolean(role?.system)
  const { groups: permissionGroups, loading: catalogLoading } = usePermissionCatalog()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    control,
    watch,
    setValue,
    formState: { errors, isSubmitting, isValid },
  } = useForm<RoleFormValues>({
    resolver: zodResolver(roleFormSchema),
    defaultValues: {
      name: role?.name ? formatRoleNameForDisplay(role.name) : "",
      description: role?.description ?? "",
      status: role?.status ?? "Active",
      permissions: role?.permissions ?? [],
    },
    mode: "onChange",
  })

  const selectedPermissions = watch("permissions")

  function togglePermission(id: string, checked: boolean) {
    setValue(
      "permissions",
      checked
        ? [...selectedPermissions, id]
        : selectedPermissions.filter((p) => p !== id),
      { shouldDirty: true }
    )
  }

  function setGroupPermissions(ids: string[], checked: boolean) {
    setValue(
      "permissions",
      checked
        ? [...new Set([...selectedPermissions, ...ids])]
        : selectedPermissions.filter((p) => !ids.includes(p)),
      { shouldDirty: true }
    )
  }

  function handleCancel() {
    router.push("/admin/roles")
  }

  async function handleFormSubmit(values: RoleFormValues) {
    setSubmitError(null)
    const error = await onSubmit(values)
    if (error) setSubmitError(error)
  }

  return (
    <form
      onSubmit={handleSubmit(handleFormSubmit)}
      className="rounded-lg border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]"
    >
      {isSystemRole && (
        <div className="mb-6 rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--gold-foreground)]">
          This is a system role. Its name, status, and permissions are managed
          by the platform and can&apos;t be edited here.
        </div>
      )}

      {submitError && (
        <div className="mb-6 flex items-start gap-2 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <span>{submitError}</span>
        </div>
      )}

      <div className="mb-5 flex flex-col gap-5 sm:flex-row sm:items-start">
        <div className="flex-1">
          <Label htmlFor="role-name">Role Name</Label>
          <Input
            id="role-name"
            placeholder="e.g. Compliance Officer"
            disabled={isSystemRole}
            aria-invalid={Boolean(errors.name)}
            {...register("name")}
          />
          {errors.name && (
            <p className="mt-1.5 text-[12px] text-destructive">
              {errors.name.message}
            </p>
          )}
        </div>

        <div className="sm:w-[240px] sm:shrink-0">
          <Label>Status</Label>
          <Controller
            name="status"
            control={control}
            render={({ field }) => (
              <SegmentedControl
                value={field.value}
                onChange={field.onChange}
                disabled={isSystemRole}
                options={[
                  { value: "Active", label: "Active" },
                  { value: "Inactive", label: "Inactive" },
                ]}
              />
            )}
          />
        </div>
      </div>

      <div className="mb-6">
        <Label htmlFor="role-description">
          Description{" "}
          <span className="text-[var(--ink-faint)] normal-case">
            (optional)
          </span>
        </Label>
        <Textarea
          id="role-description"
          placeholder="What this role is for and who should hold it…"
          disabled={isSystemRole}
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

      <div className="mb-1 flex items-center justify-between">
        <h2 className="font-heading text-[16px] font-semibold">Permissions</h2>
        <span className="text-[12px] text-muted-foreground">
          {selectedPermissions.length} selected
        </span>
      </div>
      <p className="mb-4 text-[12.5px] text-muted-foreground">
        Choose what this role can do, grouped by functional area.
        {mode === "edit" && role && (
          <>
            {" "}
            Can also be managed separately via{" "}
            <Link
              href={`/admin/roles/${role.id}/permissions`}
              className="font-semibold text-primary hover:underline"
            >
              Role Permission Configuration →
            </Link>
          </>
        )}
      </p>

      <PermissionPicker
        groups={permissionGroups}
        loading={catalogLoading}
        selected={selectedPermissions}
        onToggle={togglePermission}
        onSetGroup={setGroupPermissions}
        disabled={isSystemRole}
      />

      <hr className="my-6 border-dashed border-border" />

      <div className="flex items-center justify-end gap-2.5">
        <button
          type="button"
          onClick={handleCancel}
          className="rounded-btn border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting || isSystemRole || !isValid}
          className={cn(
            "rounded-btn border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground",
            "disabled:cursor-not-allowed disabled:opacity-60"
          )}
        >
          {mode === "create" ? "Create Role" : "Save Changes"}
        </button>
      </div>
    </form>
  )
}
