"use client"

import { useMemo, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import {
  AlertTriangle,
  Check,
  ChevronDown,
  Loader2,
  Minus,
} from "lucide-react"

import { Checkbox } from "@/components/ui/checkbox"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SearchInput } from "@/components/ui/search-input"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"

import { filterPermissionGroups, getLeafPermissionIds } from "./permission-utils"
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
 * Green tri-state checkbox used for every "select all" control on this
 * form — the master toggle, one per module (category), and one per
 * action group (sub-category). `showLabel` renders the "Select all" text
 * to its left (master + category level); sub-categories render the bare
 * checkbox only, per the design.
 *
 * Deliberately its own control rather than reusing `Checkbox`: that
 * component always renders a label + checkbox pair sized/colored for an
 * individual permission row, whereas this needs to be green, optionally
 * label-less, and support an indeterminate ("some but not all selected")
 * visual state that a plain boolean checkbox can't express.
 */
function SelectAllToggle({
  checked,
  indeterminate = false,
  onToggle,
  showLabel = true,
  disabled = false,
  label = "Select all",
}: {
  checked: boolean
  indeterminate?: boolean
  onToggle: (checked: boolean) => void
  showLabel?: boolean
  disabled?: boolean
  label?: string
}) {
  return (
    <span className="flex shrink-0 items-center gap-1.5">
      {showLabel && (
        <span className="text-[11px] font-semibold tracking-[0.04em] text-green-700 uppercase">
          {label}
        </span>
      )}
      <button
        type="button"
        role="checkbox"
        aria-checked={indeterminate ? "mixed" : checked}
        aria-label={label}
        disabled={disabled}
        onClick={(event) => {
          event.stopPropagation()
          onToggle(!checked)
        }}
        className={cn(
          "flex size-[20px] shrink-0 items-center justify-center rounded-[5px] border-2 transition-colors",
          checked || indeterminate
            ? "border-green-600 bg-green-600 text-white"
            : "border-green-600/50 bg-card",
          disabled && "cursor-not-allowed opacity-60"
        )}
      >
        {indeterminate ? (
          <Minus size={13} strokeWidth={3} />
        ) : (
          checked && <Check size={13} strokeWidth={3} />
        )}
      </button>
    </span>
  )
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
  const [permSearch, setPermSearch] = useState("")
  const [submitError, setSubmitError] = useState<string | null>(null)

  const isSearchingPermissions = permSearch.trim().length > 0
  const searchedGroups = filterPermissionGroups(permissionGroups, permSearch)

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

  // Full, unfiltered catalog — "select all" always means every permission
  // in scope, regardless of whatever the search box currently narrows the
  // *display* down to. Using the filtered `searchedGroups` here instead
  // would make "select all" silently select fewer permissions than its
  // label promises whenever a search is active, which is worse than just
  // ignoring the search for this one purpose.
  const allPermissionIds = useMemo(
    () => getLeafPermissionIds(permissionGroups.flatMap((g) => g.permissions)),
    [permissionGroups]
  )

  // Per-module leaf ids, keyed by moduleCode, computed from the full
  // unfiltered catalog — used only for the category "select all" toggle,
  // so it stays correct regardless of what the search box currently
  // narrows the *displayed* rows down to (see the note on `allPermissionIds`).
  const fullGroupIdsByModule = useMemo(
    () =>
      Object.fromEntries(
        permissionGroups.map((g) => [
          g.moduleCode,
          getLeafPermissionIds(g.permissions),
        ])
      ),
    [permissionGroups]
  )

  function togglePermission(id: string, checked: boolean) {
    setValue(
      "permissions",
      checked
        ? [...selectedPermissions, id]
        : selectedPermissions.filter((p) => p !== id),
      { shouldDirty: true }
    )
  }

  /** Select/deselect every id in `ids` at once — backs all three "select all" levels. */
  function toggleAllPermissions(ids: string[], checked: boolean) {
    setValue(
      "permissions",
      checked
        ? Array.from(new Set([...selectedPermissions, ...ids]))
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
        <div className="flex items-center gap-3.5">
          <SelectAllToggle
            checked={
              allPermissionIds.length > 0 &&
              allPermissionIds.every((id) => selectedPermissions.includes(id))
            }
            indeterminate={
              !allPermissionIds.every((id) => selectedPermissions.includes(id)) &&
              allPermissionIds.some((id) => selectedPermissions.includes(id))
            }
            onToggle={(checked) => toggleAllPermissions(allPermissionIds, checked)}
            disabled={isSystemRole || catalogLoading}
          />
          <span className="text-[12px] text-muted-foreground">
            {selectedPermissions.length} selected
          </span>
        </div>
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

      <SearchInput
        className="mb-4 sm:w-[280px]"
        value={permSearch}
        onChange={setPermSearch}
        placeholder="Search permissions…"
      />

      <div className="grid grid-cols-1 gap-3.5 sm:grid-cols-2">
        {catalogLoading ? (
          <div className="col-span-full flex items-center gap-2 rounded-[9px] border border-dashed border-border py-6 text-[13px] text-muted-foreground">
            <Loader2 size={15} className="ml-4 animate-spin" />
            Loading the permission catalog…
          </div>
        ) : searchedGroups.length === 0 ? (
          <div className="col-span-full rounded-[9px] border border-dashed border-border py-6 text-center text-[13px] text-muted-foreground">
            No permissions match &quot;{permSearch}&quot;.
          </div>
        ) : (
          searchedGroups.map((group) => {
          const groupIds = getLeafPermissionIds(group.permissions)
          const selectedCount = groupIds.filter((id) =>
            selectedPermissions.includes(id)
          ).length
          const isOpen =
            isSearchingPermissions || openModules[group.moduleCode] === true
          const fullGroupIds = fullGroupIdsByModule[group.moduleCode] ?? []
          const groupAllChecked =
            fullGroupIds.length > 0 &&
            fullGroupIds.every((id) => selectedPermissions.includes(id))
          const groupIndeterminate =
            !groupAllChecked &&
            fullGroupIds.some((id) => selectedPermissions.includes(id))

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
                isOpen && "sm:col-span-2"
              )}
            >
              <div className="flex items-center gap-2 bg-muted px-3.5 py-2">
                <CollapsibleTrigger className="flex min-w-0 flex-1 items-center gap-2 text-left text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
                  <ChevronDown
                    size={14}
                    className={cn(
                      "shrink-0 transition-transform",
                      !isOpen && "-rotate-90"
                    )}
                  />
                  <span className="min-w-0 flex-1 truncate">{group.module}</span>
                  <span className="shrink-0 font-mono text-[10.5px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
                    {group.moduleCode} · {selectedCount}/{groupIds.length}
                  </span>
                </CollapsibleTrigger>
                <SelectAllToggle
                  checked={groupAllChecked}
                  indeterminate={groupIndeterminate}
                  onToggle={(checked) =>
                    toggleAllPermissions(fullGroupIds, checked)
                  }
                  disabled={isSystemRole}
                />
              </div>
              <CollapsibleContent className="divide-y divide-border px-3.5">
                {group.permissions.map((permission) =>
                  permission.permissions &&
                  permission.permissions.length > 0 ? (
                    // Action with fine-grained permissions nested under it
                    // (e.g. "Manage Users") — its own id isn't grantable, so
                    // list its children instead, each individually
                    // checkable, plus a bare select-all checkbox for the
                    // whole sub-group.
                    <div key={permission.id} className="py-1.5">
                      <div className="flex items-center justify-between gap-2 pt-1 pb-0.5">
                        <p className="text-[11.5px] font-semibold tracking-[0.03em] text-muted-foreground uppercase">
                          {permission.label}
                        </p>
                        {(() => {
                          const childIds = getLeafPermissionIds(
                            permission.permissions!
                          )
                          const childAllChecked =
                            childIds.length > 0 &&
                            childIds.every((id) =>
                              selectedPermissions.includes(id)
                            )
                          const childIndeterminate =
                            !childAllChecked &&
                            childIds.some((id) =>
                              selectedPermissions.includes(id)
                            )
                          return (
                            <SelectAllToggle
                              checked={childAllChecked}
                              indeterminate={childIndeterminate}
                              onToggle={(checked) =>
                                toggleAllPermissions(childIds, checked)
                              }
                              showLabel={false}
                              disabled={isSystemRole}
                            />
                          )
                        })()}
                      </div>
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