"use client"

import { useState, type ReactNode } from "react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { LoadingState } from "@/components/ui/loading-state"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Switch } from "@/components/ui/switch"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { LoadFailed } from "@/components/ui/load-failed"
import type { RetentionSettingsDto } from "@/lib/retention/api"
import {
  useRetentionSettings,
  useRunRetention,
  useSaveRetentionSettings,
} from "@/lib/retention/use-retention"

/**
 * The organisation's retention settings (CRM-NFR-006): whether the automatic job runs, which rule wins when several
 * cover one document, what happens to a document no rule covers, whether a status change needs a reason, whether a
 * covered document can be deleted early and how long removed versions stay restorable - plus a button to apply the
 * rules now. Anyone who can see the rules can look; changing needs the configure permission.
 *
 * Fetching lives in the outer component; the form mounts once the settings have loaded so its draft is seeded from props.
 */
export function RetentionSettingsCard() {
  const { data, isPending, isError, error, refetch } = useRetentionSettings()
  const { hasPermission } = usePermissions()
  if (isPending) return <LoadingState message="Loading retention settings…" />
  if (isError || !data) {
    return <LoadFailed error={error} fallback="Could not load the retention settings." onRetry={() => void refetch()} />
  }
  return (
    <SettingsForm
      initial={data}
      canEdit={hasPermission(PermissionCodes.CRM_CONFIGURE_RETENTION)}
    />
  )
}

/** One titled group of related settings. */
function Section({
  title,
  hint,
  children,
}: {
  title: string
  hint: string
  children: ReactNode
}) {
  return (
    <section className="rounded-lg border border-border bg-card">
      <header className="border-b border-border px-5 py-3.5">
        <h2 className="text-[14.5px] font-semibold">{title}</h2>
        <p className="mt-0.5 text-[12.5px] text-muted-foreground">{hint}</p>
      </header>
      <div className="divide-y divide-border">{children}</div>
    </section>
  )
}

/** One setting: its name and a short explanation on the left, its control on the right. */
function Row({
  title,
  description,
  children,
}: {
  title: string
  description: ReactNode
  children: ReactNode
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 px-5 py-4">
      <div className="min-w-[220px] flex-1">
        <h3 className="text-[13.5px] font-medium">{title}</h3>
        <p className="mt-0.5 max-w-[560px] text-[12.5px] leading-[1.55] text-muted-foreground">
          {description}
        </p>
      </div>
      <div className="max-w-full">{children}</div>
    </div>
  )
}

function Toggle({
  checked,
  onChange,
  disabled,
  label,
  on,
  off,
}: {
  checked: boolean
  onChange: (value: boolean) => void
  disabled: boolean
  label: string
  on: string
  off: string
}) {
  return (
    <label className="flex items-center gap-2.5 text-[13px] font-medium">
      <Switch
        checked={checked}
        onCheckedChange={onChange}
        disabled={disabled}
        aria-label={label}
      />
      <span className="w-[62px]">{checked ? on : off}</span>
    </label>
  )
}

function SettingsForm({
  initial,
  canEdit,
}: {
  initial: RetentionSettingsDto
  canEdit: boolean
}) {
  const save = useSaveRetentionSettings()
  const run = useRunRetention()
  const [draft, setDraft] = useState<RetentionSettingsDto>(initial)
  const changed = JSON.stringify(draft) !== JSON.stringify(initial)
  const locked = !canEdit || save.isPending

  return (
    <div className="flex w-full flex-col gap-4">
      <div className="grid gap-4 lg:grid-cols-2">
        <Section
          title="Automation"
          hint="When the schedule moves documents on."
        >
          <Row
            title="Run retention automatically"
            description="A scheduled job archives documents when their active period ends and flags them for review at the end of retention. Legal hold is never touched."
          >
            <Toggle
              checked={draft.automationEnabled}
              onChange={(v) => setDraft({ ...draft, automationEnabled: v })}
              disabled={locked}
              label="Run retention automatically"
              on="On"
              off="Off"
            />
          </Row>
        </Section>

        <Section
          title="Deleting documents"
          hint="Guards against deleting too early."
        >
          <Row
            title="Protect documents from deletion during retention"
            description="Nobody, administrators included, can delete a document a rule still covers until its retention ends. Legal hold always blocks deletion."
          >
            <Toggle
              checked={draft.protectFromDeletion}
              onChange={(v) => setDraft({ ...draft, protectFromDeletion: v })}
              disabled={locked}
              label="Protect documents from deletion during retention"
              on="On"
              off="Off"
            />
          </Row>
          <Row
            title="Require a reason for status changes"
            description="Marking a document Active or Inactive needs a written reason, kept in the audit trail."
          >
            <Toggle
              checked={draft.requireStatusReason}
              onChange={(v) => setDraft({ ...draft, requireStatusReason: v })}
              disabled={locked}
              label="Require a reason for status changes"
              on="Required"
              off="Optional"
            />
          </Row>
        </Section>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Section
          title="Which rule applies"
          hint="How a document finds its rule."
        >
          <Row
            title="When rules overlap"
            description={
              <>
                <strong>Longest retention</strong> keeps the document the
                longest. <strong>Most specific</strong> goes folder, then
                document type, then department, then the organisation default.
              </>
            }
          >
            <SegmentedControl
              value={draft.precedence}
              onChange={(precedence) => setDraft({ ...draft, precedence })}
              disabled={locked}
              options={[
                { value: "longest", label: "Longest retention" },
                { value: "most_specific", label: "Most specific rule" },
              ]}
            />
          </Row>
          <Row
            title="A document no rule covers"
            description="Flag it once so an administrator assigns a rule, or leave it alone. An organisation-wide rule covers everything."
          >
            <SegmentedControl
              value={draft.whenNoPolicy}
              onChange={(whenNoPolicy) => setDraft({ ...draft, whenNoPolicy })}
              disabled={locked}
              options={[
                { value: "flag", label: "Flag it for an administrator" },
                { value: "ignore", label: "Leave it alone" },
              ]}
            />
          </Row>
        </Section>

        <Section
          title="Old versions"
          hint="What happens to versions a rule removes."
        >
          <Row
            title="Removed versions stay restorable for"
            description="A removed version is hidden first. After this many days its files are destroyed for good; until then an administrator can restore it. 0 means destroyed at the next run."
          >
            <div className="flex items-center gap-2">
              <Label htmlFor="grace-days" className="sr-only">
                Days a removed version stays restorable
              </Label>
              <Input
                id="grace-days"
                type="number"
                min={0}
                max={3650}
                className="h-9 w-24"
                value={draft.versionGraceDays}
                disabled={locked}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    versionGraceDays: Number.parseInt(e.target.value, 10) || 0,
                  })
                }
              />
              <span className="text-[13px] text-muted-foreground">days</span>
            </div>
          </Row>
        </Section>
      </div>

      <div className="sticky bottom-0 flex flex-wrap items-center gap-2 rounded-lg border border-border bg-card/95 px-4 py-3 backdrop-blur">
        <Button
          type="button"
          disabled={!canEdit || !changed || save.isPending}
          onClick={() => save.mutate(draft)}
        >
          {save.isPending ? "Saving…" : "Save"}
        </Button>
        {canEdit && (
          <Button
            type="button"
            variant="outline"
            disabled={run.isPending || changed}
            onClick={() => run.mutate()}
            title={
              changed
                ? "Save your changes first"
                : "Apply the rules to your documents now"
            }
          >
            {run.isPending ? "Running…" : "Apply rules now"}
          </Button>
        )}
        {changed && canEdit && (
          <Button
            type="button"
            variant="ghost"
            onClick={() => setDraft(initial)}
          >
            Discard changes
          </Button>
        )}
        {!canEdit && (
          <p className="text-[12.5px] text-muted-foreground">
            You can see these settings but not change them - that needs the
            &quot;configure retention&quot; permission.
          </p>
        )}
      </div>
    </div>
  )
}
