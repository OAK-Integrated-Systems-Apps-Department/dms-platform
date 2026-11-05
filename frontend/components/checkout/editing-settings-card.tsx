"use client"

import { useState } from "react"

import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Switch } from "@/components/ui/switch"
import type { EditingSettingsDto } from "@/lib/checkout/api"
import { useEditingSettings, useSaveEditingSettings } from "@/lib/checkout/use-checkout"

/**
 * This organisation's online-editing setting (CICO-FR-010): on or off, and exclusive or simultaneous. It is the
 * ONLY switch - there is no server environment variable to flip. Anyone with a cico.configure-editing-engine
 * permission can look; only select-provider can change it (the backend enforces both; `canEdit` just stops the
 * form offering what would be refused).
 *
 * Fetching lives in this outer component; the form mounts once the setting has loaded, so its draft is seeded
 * straight from props instead of copied in by an effect.
 */
export function EditingSettingsCard({ canEdit }: { canEdit: boolean }) {
  const { data, isPending, isError } = useEditingSettings()
  if (isPending) return <LoadingState message="Loading the online-editing setting…" />
  if (isError || !data) {
    return (
      <Panel title="Online editing">
        <p className="pt-3 text-[13px] text-[var(--rust)]">Could not load the online-editing setting.</p>
      </Panel>
    )
  }
  return <EditingSettingsForm initial={data} canEdit={canEdit} />
}

function EditingSettingsForm({ initial, canEdit }: { initial: EditingSettingsDto; canEdit: boolean }) {
  const save = useSaveEditingSettings()
  const [enabled, setEnabled] = useState(initial.enabled)
  const [mode, setMode] = useState<EditingSettingsDto["mode"]>(initial.mode)

  const changed = enabled !== initial.enabled || mode !== initial.mode
  const locked = !canEdit || save.isPending
  // Turning it ON needs an engine on the server; turning it OFF never does.
  const cannotEnable = !initial.engineConfigured && !initial.enabled

  return (
    <div>
      <Panel title="Online editing">
        <div className="flex flex-col gap-5 pt-3">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="max-w-[540px]">
              <h2 className="mb-1 text-[14.5px] font-semibold">Let people edit documents in the browser</h2>
              <p className="text-[12.5px] leading-[1.6] text-muted-foreground">
                Adds an <strong>Edit online</strong> button to each Word, Excel and PowerPoint document. Every save is a
                new, virus-scanned version, and signed or legal-hold documents can never be opened for editing. Off by
                default - while it is off, people use Check out, edit the download, and Check in.
              </p>
            </div>
            <label className="flex shrink-0 items-center gap-2.5 text-[13px] font-medium">
              <Switch
                checked={enabled}
                onCheckedChange={setEnabled}
                disabled={locked || (cannotEnable && !enabled)}
                aria-label="Online editing"
              />
              {enabled ? "On" : "Off"}
            </label>
          </div>

          {!initial.engineConfigured && (
            <p className="rounded-btn border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[12.5px] text-[var(--gold-foreground)]">
              This server has no editing engine set up, so online editing cannot be turned on. Ask whoever runs the
              server to configure one.
            </p>
          )}

          <div className="border-t border-border pt-4">
            <h2 className="mb-1 text-[14.5px] font-semibold">How people share a document</h2>
            <p className="mb-3 max-w-[540px] text-[12.5px] leading-[1.6] text-muted-foreground">
              <strong>One at a time</strong> checks the document out when someone opens the editor; everyone else sees who
              has it. <strong>Together</strong> lets everyone with edit access open it at once and shows who is present.
            </p>
            <SegmentedControl
              value={mode}
              onChange={setMode}
              disabled={locked}
              options={[
                { value: "exclusive", label: "One at a time" },
                { value: "simultaneous", label: "Together" },
              ]}
            />
          </div>

          {!canEdit && (
            <p className="text-[12.5px] text-muted-foreground">
              You can see this setting but not change it - that needs the &quot;select editing engine provider&quot;
              permission.
            </p>
          )}

          <div>
            <Button
              type="button"
              disabled={!canEdit || !changed || save.isPending}
              onClick={() => save.mutate({ enabled, mode })}
            >
              {save.isPending ? "Saving…" : "Save"}
            </Button>
          </div>
        </div>
      </Panel>
    </div>
  )
}
