"use client"

import { useState } from "react"
import { Bookmark, ChevronDown, Settings2, Save } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { Dropdown } from "@/components/ui/dropdown"
import { ApiError, friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { filtersFromCriteria, PRESET_ERRORS } from "@/lib/search/presets"
import { usePresetMutations, usePresets } from "@/lib/search/use-presets"
import type { SearchFilters, SearchNoticeDto } from "@/lib/search/types"

import { ManagePresetsDialog } from "./manage-presets-dialog"
import { SavePresetDialog } from "./save-preset-dialog"

/**
 * Saved presets, next to the search box: open the menu (one click), pick a preset (two). Running one fills the keyword
 * and the filters through the URL (the page keeps its URL-as-state pattern); filters that no longer exist are dropped
 * by the server and named in the warnings, which the page shows with its other notices.
 */
export function PresetBar({
  filters,
  activePresetId,
  onApply,
}: {
  filters: SearchFilters
  /** The preset the page was last filled from (shown as a chip; editing any filter detaches it). */
  activePresetId: string | null
  /** The resolved criteria as page filters, the preset that was run, and the warnings about dropped filters. */
  onApply: (next: SearchFilters, preset: { presetId: string; name: string } | null, warnings: SearchNoticeDto[]) => void
}) {
  const { hasPermission } = usePermissions()
  const canSearch = hasPermission(PermissionCodes.DSR_SEARCH_DOCUMENTS)
  const canCreate = hasPermission(PermissionCodes.DSR_MANAGE_PRESETS_CREATE)
  const canUpdate = hasPermission(PermissionCodes.DSR_MANAGE_PRESETS_UPDATE)
  const canManageAny = canCreate || canUpdate || hasPermission(PermissionCodes.DSR_MANAGE_PRESETS_DELETE)
  const presets = usePresets(canSearch)
  const { run, refresh } = usePresetMutations()
  const [menuOpen, setMenuOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [managing, setManaging] = useState(false)

  if (!canSearch) return null

  async function runOne(id: string, name: string) {
    setMenuOpen(false)
    try {
      const result = await run.mutateAsync(id)
      onApply(filtersFromCriteria(result.criteria), result.preset, result.warnings)
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        void refresh()
        toast.error(`“${name}” no longer exists.`)
      } else if (error instanceof ApiError && error.code === PRESET_ERRORS.versionUnsupported) {
        toast.error("This preset was saved by a newer version and cannot be run.")
      } else {
        toast.error(friendlyMessage(error, "Could not run the preset."))
      }
    }
  }

  const items = presets.data?.items ?? []
  const activeName = items.find((p) => p.presetId === activePresetId)?.name ?? null

  return (
    <div className="flex items-center gap-2">
      <div className="relative">
        <Button
          type="button"
          variant="outline"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((v) => !v)}
        >
          <Bookmark size={16} />
          Saved presets{items.length > 0 ? ` (${items.length})` : ""}
          <ChevronDown size={14} />
        </Button>
        {menuOpen && (
          <Dropdown width={300} className="top-11 right-auto left-0">
            <div role="menu" aria-label="Saved presets">
              {presets.isError && <p className="px-3.5 py-2 text-[13px] text-destructive">Could not load your presets.</p>}
              {presets.data && items.length === 0 && (
                <p className="px-3.5 py-2 text-[13px] text-muted-foreground">No saved presets yet.</p>
              )}
              {items.map((p) => (
                <button
                  key={p.presetId}
                  type="button"
                  role="menuitem"
                  disabled={!p.runnable}
                  title={p.runnable ? p.summary : "Saved by a newer version; it cannot be run."}
                  onClick={() => void runOne(p.presetId, p.name)}
                  className="flex w-full flex-col px-3.5 py-2 text-left text-[13px] hover:bg-muted disabled:opacity-50"
                >
                  <span className="font-semibold">{p.name}</span>
                  <span className="truncate text-[12px] text-muted-foreground">{p.summary}</span>
                </button>
              ))}
              <div className="mt-1 border-t border-border pt-1">
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setMenuOpen(false)
                    setManaging(true)
                  }}
                  className="flex w-full items-center gap-2 px-3.5 py-2 text-left text-[13px] hover:bg-muted"
                >
                  <Settings2 size={14} /> Manage presets…
                </button>
              </div>
            </div>
          </Dropdown>
        )}
      </div>
      <Button
        type="button"
        variant="outline"
        disabled={!canCreate}
        title={canCreate ? undefined : "You don’t have permission to save presets."}
        onClick={() => setSaving(true)}
      >
        <Save size={16} /> Save as preset
      </Button>
      {activePresetId && activeName && (
        <span
          className="inline-flex h-8 items-center rounded-btn bg-primary/10 px-2.5 text-[12px] font-semibold text-primary"
          data-testid="active-preset"
        >
          {activeName}
        </span>
      )}
      {canManageAny && (
        <SavePresetDialog
          open={saving}
          onOpenChange={setSaving}
          filters={filters}
          canOverwrite={canUpdate}
          presets={items}
          activePresetId={activePresetId}
          onSaved={(presetId, name) => {
            toast.success(`Saved “${name}”.`)
            onApply(filters, { presetId, name }, [])
          }}
          onManage={() => setManaging(true)}
        />
      )}
      <ManagePresetsDialog open={managing} onOpenChange={setManaging} filters={filters} />
    </div>
  )
}
