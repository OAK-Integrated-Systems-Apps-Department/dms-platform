"use client"

import { useState } from "react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Switch } from "@/components/ui/switch"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import type { RetentionSettingsDto } from "@/lib/retention/api"
import { useRetentionSettings, useRunRetention, useSaveRetentionSettings } from "@/lib/retention/use-retention"

/**
 * The organisation's retention settings (CRM-NFR-006): whether the automatic job runs, which rule wins when several
 * cover one document, what happens to a document no rule covers, and whether a status change needs a reason - plus a
 * button to apply the rules now. Anyone who can see the rules can look; changing needs the configure permission.
 *
 * Fetching lives in the outer component; the form mounts once the settings have loaded so its draft is seeded from props.
 */
export function RetentionSettingsCard() {
  const { data, isPending, isError } = useRetentionSettings()
  const { hasPermission } = usePermissions()
  if (isPending) return <LoadingState message="Loading retention settings…" />
  if (isError || !data) return <p className="text-[13px] text-[var(--rust)]">Could not load the retention settings.</p>
  return <SettingsForm initial={data} canEdit={hasPermission(PermissionCodes.CRM_CONFIGURE_RETENTION)} />
}

function SettingsForm({ initial, canEdit }: { initial: RetentionSettingsDto; canEdit: boolean }) {
  const save = useSaveRetentionSettings()
  const run = useRunRetention()
  const [draft, setDraft] = useState<RetentionSettingsDto>(initial)
  const changed = JSON.stringify(draft) !== JSON.stringify(initial)
  const locked = !canEdit || save.isPending

  return (
    <div className="max-w-[760px]">
      <Panel title="Retention settings">
        <div className="flex flex-col gap-5 pt-3">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="max-w-[540px]">
              <h2 className="mb-1 text-[14.5px] font-semibold">Run retention automatically</h2>
              <p className="text-[12.5px] leading-[1.6] text-muted-foreground">
                A scheduled job moves documents to the archive when their active period ends and flags them for review when
                their total retention ends. Switch it off to pause all automatic changes; documents under legal hold are
                never touched either way.
              </p>
            </div>
            <label className="flex shrink-0 items-center gap-2.5 text-[13px] font-medium">
              <Switch
                checked={draft.automationEnabled}
                onCheckedChange={(v) => setDraft({ ...draft, automationEnabled: v })}
                disabled={locked}
                aria-label="Run retention automatically"
              />
              {draft.automationEnabled ? "On" : "Off"}
            </label>
          </div>

          <div className="border-t border-border pt-4">
            <h2 className="mb-1 text-[14.5px] font-semibold">When rules overlap</h2>
            <p className="mb-3 max-w-[540px] text-[12.5px] leading-[1.6] text-muted-foreground">
              A document can be covered by several rules (its folder, its type, its owner&apos;s department, the organisation
              default). <strong>Longest retention</strong> applies the rule that keeps it the longest.{" "}
              <strong>Most specific</strong> applies folder first, then document type, then department, then the default.
            </p>
            <SegmentedControl
              value={draft.precedence}
              onChange={(precedence) => setDraft({ ...draft, precedence })}
              disabled={locked}
              options={[
                { value: "longest", label: "Longest retention" },
                { value: "most_specific", label: "Most specific rule" },
              ]}
            />
          </div>

          <div className="border-t border-border pt-4">
            <h2 className="mb-1 text-[14.5px] font-semibold">A document no rule covers</h2>
            <p className="mb-3 max-w-[540px] text-[12.5px] leading-[1.6] text-muted-foreground">
              Add an organisation-wide rule to cover everything. A document no rule covers can be flagged (once) so an
              administrator assigns a rule - this also applies before you have created any rule at all - or simply left
              alone.
            </p>
            <SegmentedControl
              value={draft.whenNoPolicy}
              onChange={(whenNoPolicy) => setDraft({ ...draft, whenNoPolicy })}
              disabled={locked}
              options={[
                { value: "flag", label: "Flag it for an administrator" },
                { value: "ignore", label: "Leave it alone" },
              ]}
            />
          </div>

          <div className="flex flex-wrap items-start justify-between gap-4 border-t border-border pt-4">
            <div className="max-w-[540px]">
              <h2 className="mb-1 text-[14.5px] font-semibold">Require a reason for status changes</h2>
              <p className="text-[12.5px] leading-[1.6] text-muted-foreground">
                When on, marking a document Active or Inactive needs a written reason, kept in the audit trail.
              </p>
            </div>
            <label className="flex shrink-0 items-center gap-2.5 text-[13px] font-medium">
              <Switch
                checked={draft.requireStatusReason}
                onCheckedChange={(v) => setDraft({ ...draft, requireStatusReason: v })}
                disabled={locked}
                aria-label="Require a reason for status changes"
              />
              {draft.requireStatusReason ? "Required" : "Optional"}
            </label>
          </div>

          <div className="border-t border-border pt-4">
            <h2 className="mb-1 text-[14.5px] font-semibold">Removed versions stay restorable for</h2>
            <p className="mb-3 max-w-[540px] text-[12.5px] leading-[1.6] text-muted-foreground">
              When a retention rule removes an old version it is only hidden at first. After this many days its files are
              destroyed for good. Until then an administrator can restore it (Records retention, Removed versions tab).
            </p>
            <div className="flex items-center gap-2">
              <Label htmlFor="grace-days" className="sr-only">
                Days a removed version stays restorable
              </Label>
              <Input
                id="grace-days"
                type="number"
                min={0}
                max={3650}
                className="h-9 w-28"
                value={draft.versionGraceDays}
                disabled={locked}
                onChange={(e) => setDraft({ ...draft, versionGraceDays: Number.parseInt(e.target.value, 10) || 0 })}
              />
              <span className="text-[13px] text-muted-foreground">days (0 = destroyed at the next run)</span>
            </div>
          </div>

          {!canEdit && (
            <p className="text-[12.5px] text-muted-foreground">
              You can see these settings but not change them - that needs the &quot;configure retention&quot; permission.
            </p>
          )}

          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" disabled={!canEdit || !changed || save.isPending} onClick={() => save.mutate(draft)}>
              {save.isPending ? "Saving…" : "Save"}
            </Button>
            {canEdit && (
              <Button type="button" variant="outline" disabled={run.isPending || changed} onClick={() => run.mutate()}
                title={changed ? "Save your changes first" : "Apply the rules to your documents now"}>
                {run.isPending ? "Running…" : "Apply rules now"}
              </Button>
            )}
          </div>
        </div>
      </Panel>
    </div>
  )
}
