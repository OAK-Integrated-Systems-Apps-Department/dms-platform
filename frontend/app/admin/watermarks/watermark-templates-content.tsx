"use client"

import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Plus, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
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
import { WatermarkDownloadDefaultsCard } from "@/components/watermarks/watermark-download-defaults-card"
import {
  WatermarkTemplateForm,
  type FormFailure,
} from "@/components/watermarks/watermark-template-form"
import { WatermarkTemplatesTable } from "@/components/watermarks/watermark-templates-table"
import { ApiError, isNetworkError } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"
import {
  createWatermarkTemplate,
  deleteWatermarkTemplate,
  fetchWatermarkTemplates,
  updateWatermarkTemplate,
} from "@/lib/watermarks/api"

import type { WatermarkTemplateDto, WatermarkTemplateRequestDto } from "@/lib/watermarks/api"
import type { WatermarkTemplateFormValues } from "@/components/watermarks/schema"

/** Either DW code gets you onto the screen; the table decides what you may do once there. */
const WATERMARK_ADMIN_PERMISSIONS = [
  PermissionCodes.DW_WATERMARK_TEMPLATE_CREATE,
  PermissionCodes.DW_WATERMARK_TEMPLATE_UPDATE,
]

function toRequest(values: WatermarkTemplateFormValues): WatermarkTemplateRequestDto {
  return {
    label: values.label.trim(),
    defaultText: values.defaultText.trim(),
    active: values.active,
    fontSize: values.fontSize,
    opacity: values.opacity,
    rotation: values.rotation,
    color: values.color,
    placement: values.placement,
    pageScope: values.pageScope,
  }
}

/**
 * Watermark Templates - the named texts and styles this tenant's users can
 * pick when asking for a watermarked copy.
 *
 * The four built-in templates are listed alongside the tenant's own,
 * read-only. They are shown rather than hidden because an administrator
 * deciding whether to add "Client copy" needs to see that "CONFIDENTIAL"
 * already exists.
 *
 * Controls the caller can't use are not rendered, but every mutation still
 * handles a 403 from the server: hiding a button is a courtesy, and the
 * backend's `@PreAuthorize` is what actually decides.
 */
export function WatermarkTemplatesContent() {
  usePageTitle("Watermark Templates")

  const { hasPermission } = usePermissions()
  const canCreate = hasPermission(PermissionCodes.DW_WATERMARK_TEMPLATE_CREATE)
  const canEdit = hasPermission(PermissionCodes.DW_WATERMARK_TEMPLATE_UPDATE)

  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<WatermarkTemplateDto | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [deleting, setDeleting] = useState<WatermarkTemplateDto | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  // includeInactive: this is the admin screen, so retired templates have to
  // be visible - otherwise reactivating one would be impossible.
  //
  // retry only what might succeed on a second attempt: a 404 (feature off)
  // and a 403 (not yours to manage) are settled facts about this deployment
  // and this caller, and retrying them three times with backoff leaves the
  // page spinning for fifteen seconds before admitting what it knew at once.
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ["watermarks", "templates", "all"],
    queryFn: () => fetchWatermarkTemplates(true),
    retry: (failureCount, failure) =>
      !(failure instanceof ApiError && (failure.status === 404 || failure.status === 403)) &&
      failureCount < 3,
  })

  /** Off for this deployment: the page explains itself, and offers nothing. */
  const featureOff = error instanceof ApiError && error.status === 404

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ["watermarks"] })
  }

  const save = useMutation({
    mutationFn: (values: WatermarkTemplateFormValues) =>
      editing
        ? updateWatermarkTemplate(editing.templateId, toRequest(values))
        : createWatermarkTemplate(toRequest(values)),
    onSuccess: () => {
      invalidate()
      setFormOpen(false)
      setEditing(null)
      toast.success(editing ? "Template saved." : "Template created.")
    },
  })

  const toggleActive = useMutation({
    mutationFn: (template: WatermarkTemplateDto) =>
      updateWatermarkTemplate(template.templateId, {
        label: template.label,
        defaultText: template.defaultText,
        fontSize: template.fontSize,
        opacity: template.opacity,
        rotation: template.rotation,
        color: template.color,
        placement: template.placement,
        pageScope: template.pageScope,
        active: !template.active,
      }),
    onSuccess: (updated) => {
      invalidate()
      toast.success(updated.active ? "Template reactivated." : "Template retired.")
    },
    onError: (failure) =>
      toast.error(failure instanceof Error ? failure.message : "Could not update the template."),
  })

  const remove = useMutation({
    mutationFn: (template: WatermarkTemplateDto) => deleteWatermarkTemplate(template.templateId),
    onSuccess: () => {
      invalidate()
      setDeleting(null)
      toast.success("Template deleted.")
    },
    onError: (failure) =>
      setDeleteError(failure instanceof Error ? failure.message : "Could not delete the template."),
  })

  /**
   * Turns a save failure into something the form can place.
   *
   * WATERMARK_TEMPLATE_LABEL_TAKEN is the one the backend reports often
   * enough to belong under the field rather than in a banner - it names a
   * value the user can simply change. Everything else, including the 403
   * for a built-in template, is about the template as a whole and stays in
   * the banner.
   */
  async function handleSubmit(values: WatermarkTemplateFormValues): Promise<FormFailure | null> {
    try {
      await save.mutateAsync(values)
      return null
    } catch (failure) {
      if (failure instanceof ApiError) {
        if (failure.code === "WATERMARK_TEMPLATE_LABEL_TAKEN" || failure.status === 409) {
          return { message: failure.message, field: "label" }
        }
        return { message: failure.message }
      }
      return { message: "Could not save the template." }
    }
  }

  return (
    <DashboardShell initialActiveLabel="Watermark Templates">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Watermark Templates" }]}
      />
      <div className="mb-[22px] flex flex-wrap items-end justify-between gap-4">
        <div className="max-w-[680px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Watermark Templates
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Named watermark texts and styles your users can pick when they ask for a watermarked
            copy of a document. The built-in templates are available to every workspace and
            can&apos;t be changed.
          </p>
        </div>
        {canCreate && !featureOff && (
          <Button
            type="button"
            onClick={() => {
              setEditing(null)
              setFormOpen(true)
            }}
          >
            <Plus size={14} aria-hidden="true" />
            New template
          </Button>
        )}
      </div>

      {!featureOff && (canCreate || canEdit) && (
        <WatermarkDownloadDefaultsCard
          templates={(data ?? []).filter((template) => template.active)}
          canEdit={canCreate || canEdit}
        />
      )}

      <PermissionGate permission={WATERMARK_ADMIN_PERMISSIONS}>
        {isPending ? (
          <LoadingState message="Loading watermark templates…" />
        ) : isError ? (
          isNetworkError(error) ? (
            <BackendUnavailable onRetry={refetch} />
          ) : featureOff ? (
            <div className="rounded-lg border border-border bg-card p-[26px] text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
              Watermarking isn&apos;t switched on for this workspace, so there are no templates to
              manage.
            </div>
          ) : (
            <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
              {error instanceof Error ? error.message : "Could not load watermark templates."}
            </div>
          )
        ) : (
          <WatermarkTemplatesTable
            rows={data ?? []}
            canEdit={canEdit}
            onEdit={(template) => {
              setEditing(template)
              setFormOpen(true)
            }}
            onToggleActive={(template) => toggleActive.mutate(template)}
            onDelete={(template) => {
              setDeleteError(null)
              setDeleting(template)
            }}
          />
        )}
      </PermissionGate>

      {formOpen && (
        <WatermarkTemplateForm
          key={editing?.templateId ?? "new"}
          template={editing}
          open={formOpen}
          onOpenChange={(next) => {
            setFormOpen(next)
            if (!next) setEditing(null)
          }}
          onSubmit={handleSubmit}
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
            <AlertDialogTitle>Delete this template?</AlertDialogTitle>
            <AlertDialogDescription>
              <strong className="font-semibold text-foreground">{deleting?.label}</strong> will no
              longer be available when someone asks for a watermarked copy. Copies already made with
              it keep their own record of what they say, so nothing already produced changes.
            </AlertDialogDescription>

            {deleteError && (
              <p className="mb-4 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-center text-[12.5px] text-[var(--rust)]">
                {deleteError}
              </p>
            )}

            <div className="flex items-center justify-center gap-2.5">
              <AlertDialogClose
                disabled={remove.isPending}
                className="rounded-md border border-[var(--border-strong)] bg-card px-5 py-2.5 text-[13px] font-semibold text-foreground disabled:opacity-60"
              >
                Cancel
              </AlertDialogClose>
              <button
                type="button"
                onClick={() => deleting && remove.mutate(deleting)}
                disabled={remove.isPending}
                className="rounded-md border border-[var(--rust)] bg-[var(--rust)] px-5 py-2.5 text-[13px] font-bold tracking-[0.02em] text-white disabled:opacity-60"
              >
                {remove.isPending ? "Deleting…" : "Delete template"}
              </button>
            </div>
          </AlertDialogPopup>
        </AlertDialogPortal>
      </AlertDialog>
    </DashboardShell>
  )
}
