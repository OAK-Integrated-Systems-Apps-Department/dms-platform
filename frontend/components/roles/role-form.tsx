"use client"

import { useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import { AlertTriangle, ChevronDown, Loader2 } from "lucide-react"

import { Checkbox } from "@/components/ui/checkbox"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"

import { getLeafPermissionIds } from "./permission-utils"
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
 * the note in the chat response for why.
 */
export function RoleForm({ mode, role, onSubmit }: RoleFormProps) {
  const router = useRouter()
  const isSystemRole = Boolean(role?.system)
  const { groups: permissionGroups, loading: catalogLoading } = usePermissionCatalog()
  // 32 modules is too much to show fully expanded in this quick-list view —
  // collapsed by default; open individually, or head to Role Permission
  // Configuration for search + expand/collapse-all.
  const [openModules, setOpenModules] = useState<Record<string, boolean>>({})
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    control,
    watch,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<RoleFormValues>({
    resolver: zodResolver(roleFormSchema),
    defaultValues: {
      name: role?.name ?? "",
      description: role?.description ?? "",
      status: role?.status ?? "Active",
      permissions: role?.permissions ?? [],
    },
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
      className="rounded-[14px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]"
    >
      {isSystemRole && (
        <div className="mb-6 rounded-[9px] border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[12.5px] text-[#7a5b00]">
          This is a system role. Its name, status, and permissions are managed
          by the platform and can&apos;t be edited here.
        </div>
      )}

      {submitError && (
        <div className="mb-6 flex items-start gap-2 rounded-[9px] border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--rust)]">
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
        Choose what this role can do, grouped by functional area. Toggle the
        quick list below, or use{" "}
        {mode === "edit" && role ? (
          <Link
            href={`/admin/roles/${role.id}/permissions`}
            className="font-semibold text-primary hover:underline"
          >
            Role Permission Configuration →
          </Link>
        ) : (
          "Role Permission Configuration"
        )}{" "}
        for a searchable, group-by-group view.
      </p>

      <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-2 lg:grid-cols-3">
        {catalogLoading ? (
          <div className="col-span-full flex items-center gap-2 rounded-[9px] border border-dashed border-border py-6 text-[13px] text-muted-foreground">
            <Loader2 size={15} className="ml-4 animate-spin" />
            Loading the permission catalog…
          </div>
        ) : (
          permissionGroups.map((group) => {
          const groupIds = getLeafPermissionIds(group.permissions)
          const selectedCount = groupIds.filter((id) =>
            selectedPermissions.includes(id)
          ).length
          const isOpen = openModules[group.moduleCode] === true

          return (
            <Collapsible
              key={group.moduleCode}
              open={isOpen}
              onOpenChange={(open) =>
                setOpenModules((prev) => ({
                  ...prev,
                  [group.moduleCode]: open,
                }))
              }
              className={cn(
                "overflow-hidden rounded-[10px] border border-border",
                isOpen && "sm:col-span-2 lg:col-span-3"
              )}
            >
              <CollapsibleTrigger className="flex w-full items-center gap-2 bg-muted px-3.5 py-2 text-left text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
                <ChevronDown
                  size={14}
                  className={cn(
                    "shrink-0 transition-transform",
                    !isOpen && "-rotate-90"
                  )}
                />
                <span className="flex-1 truncate">{group.module}</span>
                <span className="shrink-0 font-mono text-[10.5px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
                  {group.moduleCode} · {selectedCount}/{groupIds.length}
                </span>
              </CollapsibleTrigger>
              <CollapsibleContent className="divide-y divide-border px-3.5">
                {group.permissions.map((permission) =>
                  permission.permissions &&
                  permission.permissions.length > 0 ? (
                    // Action with fine-grained permissions nested under it
                    // (e.g. "Manage Users") — its own id isn't grantable, so
                    // list its children instead; use Role Permission
                    // Configuration for select-all on these.
                    <div key={permission.id} className="py-1.5">
                      <p className="pt-1 pb-0.5 text-[11.5px] font-semibold tracking-[0.03em] text-muted-foreground uppercase">
                        {permission.label}
                      </p>
                      <div className="divide-y divide-border border-t border-dashed border-border pl-4">
                        {permission.permissions.map((child) => (
                          <Checkbox
                            key={child.id}
                            id={child.id}
                            label={child.label}
                            disabled={isSystemRole}
                            checked={selectedPermissions.includes(child.id)}
                            onCheckedChange={(checked) =>
                              togglePermission(child.id, checked)
                            }
                          />
                        ))}
                      </div>
                    </div>
                  ) : (
                    <Checkbox
                      key={permission.id}
                      id={permission.id}
                      label={permission.label}
                      disabled={isSystemRole}
                      checked={selectedPermissions.includes(permission.id)}
                      onCheckedChange={(checked) =>
                        togglePermission(permission.id, checked)
                      }
                    />
                  )
                )}
              </CollapsibleContent>
            </Collapsible>
          )
        })
        )}
      </div>

      <hr className="my-6 border-dashed border-border" />

      <div className="flex items-center justify-end gap-2.5">
        <button
          type="button"
          onClick={handleCancel}
          className="rounded-[9px] border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting || isSystemRole}
          className={cn(
            "rounded-[9px] border border-primary bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.02em] text-primary-foreground",
            "disabled:cursor-not-allowed disabled:opacity-60"
          )}
        >
          {mode === "create" ? "Create Role" : "Save Changes"}
        </button>
      </div>
    </form>
  )
}