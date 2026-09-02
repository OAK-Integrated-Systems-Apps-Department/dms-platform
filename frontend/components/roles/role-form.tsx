"use client"

import { useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { zodResolver } from "@hookform/resolvers/zod"
import { Controller, useForm } from "react-hook-form"
import { ChevronDown } from "lucide-react"

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

import { permissionGroups } from "./data"
import { roleFormSchema, type RoleFormValues } from "./schema"
import type { RoleRecord } from "./types"

interface RoleFormProps {
  mode: "create" | "edit"
  /** Pass the existing role when editing — its id, `assignedUsers`, and `system` flag are preserved. */
  role?: RoleRecord
  onSubmit: (values: RoleFormValues) => void
}

/**
 * Add/Edit Role form. Combines the R2 (Add/Edit Role) and R3 (Role
 * Permission Configuration) wireframe screens into a single page — see
 * the note in the chat response for why.
 */
export function RoleForm({ mode, role, onSubmit }: RoleFormProps) {
  const router = useRouter()
  const isSystemRole = Boolean(role?.system)

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
  // 32 modules is too many to show fully expanded by default.
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})

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

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      className="rounded-[14px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]"
    >
      {isSystemRole && (
        <div className="mb-6 rounded-[9px] border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[12.5px] text-[#7a5b00]">
          This is a system role. Its name, status, and permissions are managed
          by the platform and can&apos;t be edited here.
        </div>
      )}

      <div className="mb-5">
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

      <div className="mb-6">
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

      <hr className="my-6 border-dashed border-border" />

      <div className="mb-1 flex items-center justify-between">
        <h2 className="font-heading text-[16px] font-semibold">Permissions</h2>
        <span className="text-[12px] text-muted-foreground">
          {selectedPermissions.length} selected
        </span>
      </div>
      <p className="mb-4 text-[12.5px] text-muted-foreground">
        Choose what this role can do, grouped by module. Expand a module below,
        or use{" "}
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
        for a searchable, module-by-module view.
      </p>

      <div className="space-y-3">
        {permissionGroups.map((group) => {
          const groupIds = group.permissions.map((p) => p.id)
          const selectedCount = groupIds.filter((id) =>
            selectedPermissions.includes(id)
          ).length
          const isOpen = expanded[group.module] === true

          return (
            <Collapsible
              key={group.module}
              open={isOpen}
              onOpenChange={(open) =>
                setExpanded((prev) => ({ ...prev, [group.module]: open }))
              }
              className="overflow-hidden rounded-[10px] border border-border"
            >
              <CollapsibleTrigger className="flex w-full items-center gap-2 bg-muted px-3.5 py-2 text-left text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
                <ChevronDown
                  size={14}
                  className={cn(
                    "shrink-0 transition-transform",
                    !isOpen && "-rotate-90"
                  )}
                />
                {group.module}
                <span className="font-mono text-[10px] font-normal tracking-normal text-[var(--ink-faint)] normal-case">
                  {group.moduleCode} · {selectedCount}/{groupIds.length}
                </span>
              </CollapsibleTrigger>
              <CollapsibleContent className="divide-y divide-border px-3.5">
                {group.permissions.map((permission) => (
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
                ))}
              </CollapsibleContent>
            </Collapsible>
          )
        })}
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
