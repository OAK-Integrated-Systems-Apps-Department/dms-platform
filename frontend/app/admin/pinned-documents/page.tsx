"use client"

import { useState } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { savePinLimit } from "@/lib/pins/api"
import { PINS_KEY, usePins } from "@/lib/pins/use-pins"
import { usePageTitle } from "@/lib/use-page-title"

const MIN = 1
const MAX = 200

/**
 * Pinned documents (Module PID): how many documents one person in this organisation may pin.
 * Whoever may pin chooses who sees each pin, and only they can unpin it; this is the one setting the
 * organisation controls, how many documents each person may pin (PID-FR-007).
 */
export default function PinnedDocumentsSettingsPage() {
  usePageTitle("Pin Limit")
  return (
    <DashboardShell initialActiveLabel="Pin Limit">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Pin Limit" },
        ]}
      />
      <div className="mb-5 max-w-[760px]">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Pin limit
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          People with the pin permission pin the documents that matter and
          choose who sees each pin, and those appear on the audience&apos;s
          Pinned documents page. Only the person who pinned a document can
          unpin it, and a pin never gives access to a document.
        </p>
      </div>
      <PermissionGate permission={PermissionCodes.PID_CONFIGURE_LIMITS}>
        <PinLimit />
      </PermissionGate>
    </DashboardShell>
  )
}

function PinLimit() {
  const queryClient = useQueryClient()
  const { limit, isLoading } = usePins()
  const [draft, setDraft] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  if (isLoading) return <LoadingState message="Loading the pin limit…" />

  const shown = draft ?? (limit !== null ? String(limit) : "")
  const value = Number(shown)
  const valid = Number.isInteger(value) && value >= MIN && value <= MAX
  const changed = valid && value !== limit

  async function save() {
    setSaving(true)
    try {
      await savePinLimit(value)
      toast.success(`People can now pin up to ${value} documents.`)
      setDraft(null)
      await queryClient.invalidateQueries({ queryKey: PINS_KEY })
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not save the pin limit."))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="max-w-[760px]">
      <Panel title="Pins per person">
        <form
          className="flex flex-wrap items-end gap-3 pt-3"
          onSubmit={(event) => {
            event.preventDefault()
            if (changed) void save()
          }}
        >
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="pin-limit">Most documents one person may pin</Label>
            <Input
              id="pin-limit"
              type="number"
              inputMode="numeric"
              min={MIN}
              max={MAX}
              value={shown}
              onChange={(event) => setDraft(event.target.value)}
              aria-invalid={shown !== "" && !valid}
              aria-describedby="pin-limit-help"
              className="w-32"
            />
          </div>
          <Button type="submit" disabled={!changed || saving}>
            {saving ? "Saving…" : "Save"}
          </Button>
        </form>
        <p
          id="pin-limit-help"
          className={`mt-2 text-[12.5px] ${
            shown !== "" && !valid
              ? "text-[var(--rust)]"
              : "text-muted-foreground"
          }`}
        >
          Between {MIN} and {MAX}. When someone reaches it they are told, and
          can unpin one to pin another. Lowering it never removes pins people
          already have - they just can&apos;t add more until they&apos;re under
          it.
        </p>
      </Panel>
    </div>
  )
}
