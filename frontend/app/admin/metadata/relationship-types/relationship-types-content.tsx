"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Plus, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RelationshipTypeForm } from "@/components/relationships/relationship-type-form"
import { RelationshipTypesTable } from "@/components/relationships/relationship-types-table"
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage, isNetworkError } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import {
  createRelationshipType,
  deleteRelationshipType,
  updateRelationshipType,
} from "@/lib/relationships/api"
import { useAllRelationshipTypes } from "@/lib/relationships/use-relationship-types"
import { usePageTitle } from "@/lib/use-page-title"

import type { RelationshipTypeDto } from "@/lib/relationships/types"

/** Any DRM configure code gets you onto the screen; the table decides what you may do once there. */
const TYPE_ADMIN_PERMISSIONS = [
  PermissionCodes.DRM_TYPES_CREATE,
  PermissionCodes.DRM_TYPES_UPDATE,
  PermissionCodes.DRM_TYPES_DELETE,
]

/**
 * Relationship Types - the kinds of link people can make between documents.
 * The built-in ten can be renamed and switched off, never deleted; disabling a
 * type only stops new links of it, the existing ones keep working.
 */
export function RelationshipTypesContent() {
  usePageTitle("Relationship Types")

  const { hasPermission } = usePermissions()
  const canCreate = hasPermission(PermissionCodes.DRM_TYPES_CREATE)
  const canUpdate = hasPermission(PermissionCodes.DRM_TYPES_UPDATE)
  const canDelete = hasPermission(PermissionCodes.DRM_TYPES_DELETE)

  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<RelationshipTypeDto | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [deleting, setDeleting] = useState<RelationshipTypeDto | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const { data, isPending, isError, error, refetch } = useAllRelationshipTypes()

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["relationship-types"] })
  }

  const toggleEnabled = useMutation({
    mutationFn: (type: RelationshipTypeDto) => updateRelationshipType(type.relationshipTypeId, { enabled: !type.enabled }),
    onSuccess: (updated) => {
      invalidate()
      toast.success(updated.enabled ? "Type enabled." : "Type disabled. Existing links keep working.")
    },
    onError: (failure) => toast.error(friendlyMessage(failure, "Could not update the type.")),
  })

  const remove = useMutation({
    mutationFn: (type: RelationshipTypeDto) => deleteRelationshipType(type.relationshipTypeId),
    onSuccess: () => {
      invalidate()
      setDeleting(null)
      toast.success("Type deleted.")
    },
    onError: (failure) => setDeleteError(friendlyMessage(failure, "Could not delete the type.")),
  })

  async function handleCreate(body: Parameters<typeof createRelationshipType>[0]) {
    await createRelationshipType(body)
    invalidate()
    toast.success("Type created.")
  }

  async function handleUpdate(id: string, body: Parameters<typeof updateRelationshipType>[1]) {
    await updateRelationshipType(id, body)
    invalidate()
    toast.success("Type saved.")
  }

  return (
    <DashboardShell initialActiveLabel="Relationship Types">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Relationship Types" }]}
      />
      <div className="mb-[22px] flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Relationship Types</h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            The kinds of link people can make between documents. Built-in types can be renamed or switched off
            but not deleted; switching a type off only stops new links of that type - existing ones stay visible
            and keep working.
          </p>
        </div>
        {canCreate && (
          <Button
            type="button"
            onClick={() => {
              setEditing(null)
              setFormOpen(true)
            }}
          >
            <Plus size={14} aria-hidden="true" />
            New type
          </Button>
        )}
      </div>

      <PermissionGate permission={TYPE_ADMIN_PERMISSIONS}>
        {isPending ? (
          <LoadingState message="Loading relationship types…" />
        ) : isError ? (
          isNetworkError(error) ? (
            <BackendUnavailable onRetry={refetch} />
          ) : (
            <LoadFailed error={error} fallback="Could not load relationship types." />
          )
        ) : (
          <RelationshipTypesTable
            rows={data ?? []}
            canUpdate={canUpdate}
            canDelete={canDelete}
            onEdit={(type) => {
              setEditing(type)
              setFormOpen(true)
            }}
            onToggleEnabled={(type) => toggleEnabled.mutate(type)}
            onDelete={(type) => {
              setDeleteError(null)
              setDeleting(type)
            }}
          />
        )}
      </PermissionGate>

      {formOpen && (
        <RelationshipTypeForm
          key={editing?.relationshipTypeId ?? "new"}
          type={editing}
          open={formOpen}
          onOpenChange={(next) => {
            setFormOpen(next)
            if (!next) setEditing(null)
          }}
          onCreate={handleCreate}
          onUpdate={handleUpdate}
        />
      )}

      <AlertDialog
        open={deleting !== null}
        onOpenChange={(next) => {
          if (remove.isPending) return
          if (!next) {
            setDeleting(null)
            setDeleteError(null)
          }
        }}
      >
        <AlertDialogPortal>
          <AlertDialogPopup className="max-w-[560px]">
            <span className="mx-auto mb-4 flex size-11 items-center justify-center rounded-md bg-[var(--rust-tint)] text-[var(--rust)]">
              <Trash2 size={20} />
            </span>
            <AlertDialogTitle>Delete this type?</AlertDialogTitle>
            <AlertDialogDescription>
              <strong className="font-semibold text-foreground">{deleting?.label}</strong> has never been used, so it
              can be removed. A type that has been used can only be switched off.
            </AlertDialogDescription>
            {deleteError && (
              <p className="mb-4 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-center text-[12.5px] text-[var(--rust)]">
                {deleteError}
              </p>
            )}
            <div className="flex items-center justify-center gap-2.5">
              <AlertDialogClose
                disabled={remove.isPending}
                className="border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
              >
                Cancel
              </AlertDialogClose>
              <button
                type="button"
                onClick={() => deleting && remove.mutate(deleting)}
                disabled={remove.isPending}
                className="border border-[var(--rust)] bg-[var(--rust)] text-white disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
              >
                {remove.isPending ? "Deleting…" : "Delete type"}
              </button>
            </div>
          </AlertDialogPopup>
        </AlertDialogPortal>
      </AlertDialog>
    </DashboardShell>
  )
}
