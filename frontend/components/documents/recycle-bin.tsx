"use client"

import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { FileText, Lock, RotateCcw, Trash2 } from "lucide-react"
import { toast } from "sonner"

import { ConfirmActionDialog } from "@/components/bulk-import/confirm-action-dialog"
import { FriendlyDate } from "@/components/documents/browser-items"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import {
  fetchRecycleBin,
  purgeFromRecycleBin,
  restoreFromRecycleBin,
  saveRecycleBinRetention,
  type RecycleBinItemDto,
} from "@/lib/recycle-bin/api"

const KEY = ["recycle-bin"] as const

/**
 * The recycle bin (RBR): deleted documents the caller can still open, kept
 * for the organisation's retention period, then deleted for good - files
 * and encryption keys destroyed. They can be restored until then, or deleted
 * permanently straight away.
 */
export function RecycleBin() {
  const queryClient = useQueryClient()
  const { hasPermission } = usePermissions()
  const canRestore = hasPermission(PermissionCodes.RBR_RESTORE)
  const canPurge = hasPermission(PermissionCodes.RBR_PURGE)
  const canSetRetention = hasPermission(PermissionCodes.RBR_RETENTION)
  const bin = useQuery({ queryKey: KEY, queryFn: fetchRecycleBin })
  const [purging, setPurging] = useState<RecycleBinItemDto | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: KEY })
    void queryClient.invalidateQueries({ queryKey: ["documents"] })
  }

  async function restore(item: RecycleBinItemDto) {
    setBusy(item.documentId)
    try {
      await restoreFromRecycleBin(item.documentId)
      toast.success(`"${item.title}" was restored.`)
      refresh()
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not restore the document."))
    } finally {
      setBusy(null)
    }
  }

  if (bin.isPending) return <LoadingState message="Loading the recycle bin…" />
  if (bin.isError) {
    return (
      <p className="text-[13px] text-[var(--rust)]">
        {friendlyMessage(bin.error, "Could not load the recycle bin.")}
      </p>
    )
  }
  const { items, retentionDays } = bin.data

  return (
    <div className="flex max-w-[1100px] flex-col gap-5">
      <RetentionBar
        days={retentionDays}
        canEdit={canSetRetention}
        onSaved={refresh}
      />

      {items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-border p-10 text-center">
          <Trash2 className="mx-auto mb-2 text-muted-foreground" size={22} />
          <p className="text-[13.5px] font-medium">The recycle bin is empty</p>
          <p className="mt-1 text-[12.5px] text-muted-foreground">
            Deleted documents wait here for {retentionDays} days before
            they&apos;re deleted for good.
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-border bg-card">
          <table className="w-full min-w-[760px] text-[13px]">
            <thead className="border-b border-border bg-muted/30 text-left text-[11.5px] tracking-[0.04em] text-muted-foreground uppercase">
              <tr>
                <th className="px-4 py-2.5 font-semibold">Document</th>
                <th className="px-4 py-2.5 font-semibold">Was in</th>
                <th className="px-4 py-2.5 font-semibold">Deleted</th>
                <th className="px-4 py-2.5 font-semibold">Deleted for good</th>
                <th className="px-4 py-2.5" />
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr
                  key={item.documentId}
                  className="border-b border-border last:border-0"
                >
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2.5">
                      <FileText size={16} className="shrink-0 text-primary" />
                      <div className="min-w-0">
                        <p className="truncate font-medium">{item.title}</p>
                        <p className="truncate text-[12px] text-muted-foreground">
                          {item.filename ?? "—"} · {formatBytes(item.sizeBytes)}
                        </p>
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">
                    {item.folderGone
                      ? "A deleted folder"
                      : (item.folderName ?? "Top level")}
                  </td>
                  <td className="px-4 py-3">
                    <FriendlyDate iso={item.deletedAt} />
                    {item.deletedByName && (
                      <span className="block text-[12px] text-muted-foreground">
                        by {item.deletedByName}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    {item.protectedVersion ? (
                      <span className="inline-flex items-center gap-1 text-[12.5px] text-muted-foreground">
                        <Lock size={13} /> Kept - a version is protected
                      </span>
                    ) : (
                      new Date(item.purgeAt).toLocaleDateString(undefined, {
                        day: "numeric",
                        month: "short",
                        year: "numeric",
                      })
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex justify-end gap-2">
                      {canRestore && (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy === item.documentId || item.folderGone}
                          title={
                            item.folderGone
                              ? "Its folder was deleted - ask an administrator"
                              : undefined
                          }
                          onClick={() => void restore(item)}
                        >
                          <RotateCcw /> Restore
                        </Button>
                      )}
                      {canPurge && !item.protectedVersion && (
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-[var(--rust)]"
                          disabled={busy === item.documentId}
                          onClick={() => setPurging(item)}
                        >
                          <Trash2 /> Delete forever
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <ConfirmActionDialog
        open={purging !== null}
        onOpenChange={(open) => !open && setPurging(null)}
        title={purging ? `Delete "${purging.title}" forever?` : ""}
        description="Every version's file and its encryption key are destroyed. This can't be undone. The audit trail keeps a record that it existed."
        confirmLabel="Delete forever"
        pendingLabel="Deleting…"
        onConfirm={async () => {
          if (!purging) return null
          try {
            await purgeFromRecycleBin(purging.documentId)
            toast.success(`"${purging.title}" was deleted for good.`)
            refresh()
            return null
          } catch (error) {
            return friendlyMessage(error, "Could not delete the document.")
          }
        }}
      />
    </div>
  )
}

function RetentionBar({
  days,
  canEdit,
  onSaved,
}: {
  days: number
  canEdit: boolean
  onSaved: () => void
}) {
  const [value, setValue] = useState(String(days))
  const [saving, setSaving] = useState(false)
  const parsed = Number(value)
  const valid = Number.isInteger(parsed) && parsed >= 1 && parsed <= 3650

  async function save() {
    setSaving(true)
    try {
      await saveRecycleBinRetention(parsed)
      toast.success(`Deleted documents are now kept for ${parsed} days.`)
      onSaved()
    } catch (error) {
      toast.error(
        friendlyMessage(error, "Could not save the retention period.")
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card px-4 py-3 text-[13px]">
      <span className="text-muted-foreground">
        Deleted documents are kept for
      </span>
      {canEdit ? (
        <>
          <Input
            type="number"
            min={1}
            max={3650}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            className="h-8 w-24"
            aria-label="Days deleted documents are kept"
          />
          <span className="text-muted-foreground">
            days, then deleted for good.
          </span>
          <Button
            size="sm"
            variant="outline"
            disabled={!valid || parsed === days || saving}
            onClick={() => void save()}
          >
            Save
          </Button>
        </>
      ) : (
        <span>
          <strong>{days} days</strong>, then deleted for good.
        </span>
      )}
    </section>
  )
}
