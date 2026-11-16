"use client"

import { useState } from "react"
import { Pencil, RefreshCw, Trash2 } from "lucide-react"
import { toast } from "sonner"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { Input } from "@/components/ui/input"
import { LoadFailed } from "@/components/ui/load-failed"
import { ApiError, friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { criteriaFromFilters, hasPresetCriteria, PRESET_ERRORS, type PresetListItemDto } from "@/lib/search/presets"
import { usePresetMutations, usePresets } from "@/lib/search/use-presets"
import type { SearchFilters } from "@/lib/search/types"

/** Rename, update to the current criteria, or delete the caller's presets. */
export function ManagePresetsDialog({
  open,
  onOpenChange,
  filters,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  filters: SearchFilters
}) {
  const { hasPermission } = usePermissions()
  const canUpdate = hasPermission(PermissionCodes.DSR_MANAGE_PRESETS_UPDATE)
  const canDelete = hasPermission(PermissionCodes.DSR_MANAGE_PRESETS_DELETE)
  const presets = usePresets(open)
  const { update, remove, refresh } = usePresetMutations()
  const [renaming, setRenaming] = useState<{ id: string; name: string } | null>(null)
  const [renameError, setRenameError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<PresetListItemDto | null>(null)
  const hasCriteria = hasPresetCriteria(filters)

  async function rename() {
    if (!renaming || !renaming.name.trim()) return
    setRenameError(null)
    try {
      await update.mutateAsync({ id: renaming.id, name: renaming.name.trim() })
      setRenaming(null)
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        setRenaming(null)
        void refresh()
        toast.error("That preset no longer exists.")
      } else if (error instanceof ApiError && error.code === PRESET_ERRORS.nameExists) {
        setRenameError("You already have a preset with that name.")
      } else {
        setRenameError(friendlyMessage(error, "Could not rename the preset."))
      }
    }
  }

  async function applyCurrent(preset: PresetListItemDto) {
    try {
      await update.mutateAsync({ id: preset.presetId, criteria: criteriaFromFilters(filters) })
      toast.success(`“${preset.name}” now uses the current search.`)
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        void refresh()
        toast.error("That preset no longer exists.")
      } else if (error instanceof ApiError && error.code === PRESET_ERRORS.noCriteria) {
        toast.error("Add a keyword or a filter first.")
      } else {
        toast.error(friendlyMessage(error, "Could not update the preset."))
      }
    }
  }

  return (
    <>
      <AlertDialog open={open} onOpenChange={onOpenChange}>
        <AlertDialogPortal>
          <AlertDialogPopup className="max-w-[560px]">
            <AlertDialogTitle>Saved presets</AlertDialogTitle>
            <AlertDialogDescription>
              Only you can see these. Rename one, point it at the search on screen, or delete it.
            </AlertDialogDescription>
            <div className="mb-4 max-h-[50vh] overflow-y-auto text-left">
              {presets.isError && (
                <LoadFailed error={presets.error} fallback="Could not load your saved presets." onRetry={() => presets.refetch()} />
              )}
              {presets.data && presets.data.items.length === 0 && (
                <p className="py-6 text-center text-[13px] text-muted-foreground">You have no saved presets yet.</p>
              )}
              {presets.data && presets.data.items.length > 0 && (
                <>
                  <p className="mb-2 text-[12px] text-muted-foreground">
                    {presets.data.count} of {presets.data.limit} presets used
                  </p>
                  <ul className="grid gap-2">
                    {presets.data.items.map((p) => (
                      <li key={p.presetId} className="rounded-lg border border-border p-3">
                        {renaming?.id === p.presetId ? (
                          <div className="grid gap-2">
                            <Input
                              aria-label={`New name for ${p.name}`}
                              value={renaming.name}
                              maxLength={100}
                              onChange={(e) => setRenaming({ id: p.presetId, name: e.target.value })}
                              onKeyDown={(e) => e.key === "Enter" && void rename()}
                              autoFocus
                            />
                            {renameError && (
                              <p role="alert" className="text-[12px] text-destructive">
                                {renameError}
                              </p>
                            )}
                            <div className="flex gap-2">
                              <Button type="button" size="xs" disabled={!renaming.name.trim() || update.isPending} onClick={() => void rename()}>
                                Save name
                              </Button>
                              <Button type="button" size="xs" variant="outline" onClick={() => setRenaming(null)}>
                                Cancel
                              </Button>
                            </div>
                          </div>
                        ) : (
                          <div className="flex flex-wrap items-center gap-2">
                            <div className="min-w-0 flex-1">
                              <p className="truncate text-[14px] font-semibold">{p.name}</p>
                              <p className="truncate text-[12px] text-muted-foreground">{p.summary}</p>
                              {!p.runnable && (
                                <p className="text-[12px] text-destructive">
                                  This preset was saved by a newer version and cannot be run.
                                </p>
                              )}
                            </div>
                            <Button
                              type="button"
                              size="xs"
                              variant="outline"
                              disabled={!canUpdate}
                              title={canUpdate ? undefined : "You don’t have permission to rename presets."}
                              aria-label={`Rename ${p.name}`}
                              onClick={() => {
                                setRenameError(null)
                                setRenaming({ id: p.presetId, name: p.name })
                              }}
                            >
                              <Pencil size={13} /> Rename
                            </Button>
                            <Button
                              type="button"
                              size="xs"
                              variant="outline"
                              disabled={!canUpdate || !hasCriteria || update.isPending}
                              title={
                                !canUpdate
                                  ? "You don’t have permission to change presets."
                                  : !hasCriteria
                                    ? "Add a keyword or a filter to the search first."
                                    : undefined
                              }
                              aria-label={`Update ${p.name} to the current search`}
                              onClick={() => void applyCurrent(p)}
                            >
                              <RefreshCw size={13} /> Use current search
                            </Button>
                            <Button
                              type="button"
                              size="xs"
                              variant="destructive"
                              disabled={!canDelete}
                              title={canDelete ? undefined : "You don’t have permission to delete presets."}
                              aria-label={`Delete ${p.name}`}
                              onClick={() => setDeleting(p)}
                            >
                              <Trash2 size={13} /> Delete
                            </Button>
                          </div>
                        )}
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </div>
            <div className="flex justify-end">
              <AlertDialogClose className="inline-flex h-[38px] items-center rounded-btn border border-[var(--border-strong)] bg-card px-4 text-[13px] font-semibold">
                Done
              </AlertDialogClose>
            </div>
          </AlertDialogPopup>
        </AlertDialogPortal>
      </AlertDialog>

      <ConfirmActionDialog
        open={deleting !== null}
        onOpenChange={(next) => !next && setDeleting(null)}
        title="Delete this preset?"
        description={deleting ? `“${deleting.name}” will be removed. Your documents are not affected.` : ""}
        confirmLabel="Delete preset"
        pendingLabel="Deleting…"
        cancelLabel="Keep it"
        onConfirm={async () => {
          if (!deleting) return null
          try {
            await remove.mutateAsync(deleting.presetId)
            return null
          } catch (error) {
            // A preset someone removed elsewhere is already gone: refresh and treat it as done.
            if (error instanceof ApiError && error.status === 404) {
              void refresh()
              return null
            }
            return friendlyMessage(error, "Could not delete the preset.")
          }
        }}
      />
    </>
  )
}
