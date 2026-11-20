"use client"

import { useState } from "react"
import { useQueries } from "@tanstack/react-query"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { ApiError, friendlyMessage } from "@/lib/api-error"
import { fetchPreset } from "@/lib/search/presets-api"
import { criteriaFromFilters, hasPresetCriteria, PRESET_ERRORS, sameCriteria } from "@/lib/search/presets"
import { usePresetMutations } from "@/lib/search/use-presets"
import type { SearchFilters } from "@/lib/search/types"

/**
 * "Save as preset": names the current keyword and filters. Every refusal the server can give has its own plain message:
 * no criteria, a name already used (with a choice to replace it), the per-person limit (with a way to make room).
 */
export function SavePresetDialog({
  open,
  onOpenChange,
  filters,
  canOverwrite,
  presets = [],
  activePresetId = null,
  onSaved,
  onManage,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  filters: SearchFilters
  /** Replacing an existing preset needs the update permission as well as create. */
  canOverwrite: boolean
  /** The caller's presets (names), to prefill the active one's name and to spot a search that is already saved. */
  presets?: Array<{ presetId: string; name: string }>
  /** The preset the page was filled from: its name is offered when the dialog opens. */
  activePresetId?: string | null
  onSaved: (presetId: string, name: string) => void
  onManage: () => void
}) {
  const { create } = usePresetMutations()
  const [name, setName] = useState("")
  const [message, setMessage] = useState<string | null>(null)
  const [limit, setLimit] = useState<number | null>(null)
  const [confirmReplace, setConfirmReplace] = useState(false)
  const hasCriteria = hasPresetCriteria(filters)
  const trimmed = name.trim()

  // Offer the active preset's name each time the dialog opens (state synced during render, not in an effect).
  const [wasOpen, setWasOpen] = useState(false)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) setName(presets.find((p) => p.presetId === activePresetId)?.name ?? "")
  }

  // Is this exact search already saved under another name? Criteria are fetched only while the dialog is open.
  const loaded = useQueries({
    queries: presets.map((p) => ({
      queryKey: ["search", "preset", p.presetId],
      queryFn: () => fetchPreset(p.presetId),
      enabled: open && hasCriteria,
      staleTime: 30_000,
      retry: false,
    })),
  })
  const current = criteriaFromFilters(filters)
  const alreadySaved = hasCriteria
    ? presets.find((p, i) => {
        const data = loaded[i]?.data
        return (
          data !== undefined &&
          p.name.trim().toLowerCase() !== trimmed.toLowerCase() &&
          sameCriteria(data.criteria as Record<string, unknown>, current as Record<string, unknown>)
        )
      })
    : undefined

  function close() {
    setName("")
    setMessage(null)
    setLimit(null)
    setConfirmReplace(false)
    onOpenChange(false)
  }

  async function save(overwrite: boolean) {
    setMessage(null)
    setLimit(null)
    try {
      const saved = await create.mutateAsync({ name: trimmed, criteria: criteriaFromFilters(filters), overwrite })
      onSaved(saved.presetId, saved.name)
      close()
    } catch (error) {
      setConfirmReplace(false)
      if (error instanceof ApiError && error.code === PRESET_ERRORS.nameExists) {
        if (canOverwrite) {
          setConfirmReplace(true)
        } else {
          setMessage(`You already have a preset named “${trimmed}”. Choose a different name.`)
        }
      } else if (error instanceof ApiError && error.code === PRESET_ERRORS.limitReached) {
        const max = (error.details as { limit?: number } | undefined)?.limit
        setLimit(typeof max === "number" ? max : 0)
      } else if (error instanceof ApiError && error.code === PRESET_ERRORS.noCriteria) {
        setMessage("Add a keyword or a filter first, then save it.")
      } else {
        setMessage(friendlyMessage(error, "Could not save the preset."))
      }
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={(next) => (next ? onOpenChange(true) : close())}>
      <AlertDialogPortal>
        <AlertDialogPopup className="max-w-[460px]">
          <AlertDialogTitle>{confirmReplace ? "Replace this preset?" : "Save as preset"}</AlertDialogTitle>
          {confirmReplace ? (
            <>
              <AlertDialogDescription>
                A preset named “{trimmed}” already exists. Replace its criteria with this search?
              </AlertDialogDescription>
              <div className="flex items-center justify-center gap-2.5">
                <Button type="button" variant="outline" onClick={() => setConfirmReplace(false)}>
                  Choose another name
                </Button>
                <Button type="button" disabled={create.isPending} onClick={() => void save(true)}>
                  Replace
                </Button>
              </div>
            </>
          ) : (
            <>
              <AlertDialogDescription>
                Keep this keyword and these filters so you can run the same search again in one click.
              </AlertDialogDescription>
              <form
                className="mb-4 grid gap-3 text-left"
                onSubmit={(e) => {
                  e.preventDefault()
                  if (trimmed && hasCriteria) void save(false)
                }}
              >
                <label className="grid gap-1 text-[13px] font-medium">
                  Name
                  <Input
                    value={name}
                    maxLength={100}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="For example: Contracts to renew"
                    aria-label="Preset name"
                    autoFocus
                  />
                </label>
                {!hasCriteria && (
                  <p role="alert" className="text-[13px] text-muted-foreground">
                    Add a keyword or a filter first. A preset needs something to search for.
                  </p>
                )}
                {alreadySaved && (
                  <p role="status" className="text-[13px] text-muted-foreground">
                    These settings are already saved as “{alreadySaved.name}”.
                  </p>
                )}
                {message && (
                  <p role="alert" className="text-[13px] text-destructive">
                    {message}
                  </p>
                )}
                {limit !== null && (
                  <p role="alert" className="text-[13px] text-destructive">
                    You have reached the limit of {limit} presets. Delete one to save a new one.{" "}
                    <button
                      type="button"
                      className="font-semibold underline"
                      onClick={() => {
                        close()
                        onManage()
                      }}
                    >
                      Manage presets
                    </button>
                  </p>
                )}
                <div className="flex items-center justify-end gap-2.5">
                  <AlertDialogClose className="inline-flex h-[38px] items-center rounded-btn border border-[var(--border-strong)] bg-card px-4 text-[13px] font-semibold">
                    Cancel
                  </AlertDialogClose>
                  <Button type="submit" disabled={!trimmed || !hasCriteria || create.isPending}>
                    {create.isPending ? "Saving…" : "Save preset"}
                  </Button>
                </div>
              </form>
            </>
          )}
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}
