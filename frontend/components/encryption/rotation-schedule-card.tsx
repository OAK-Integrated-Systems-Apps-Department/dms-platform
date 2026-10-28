"use client"

import { useState } from "react"
import { AlertTriangle, CalendarClock, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { StatTile, StepCard } from "@/components/ui/step-card"
import { FriendlyDate } from "@/components/documents/browser-items"
import { Button } from "@/components/ui/button"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { Switch } from "@/components/ui/switch"
import { friendlyMessage } from "@/lib/api-error"
import {
  saveRotationPolicy,
  type RotationPolicyDto,
} from "@/lib/encryption/api"

type Interval = "off" | "90" | "180" | "365"

const INTERVALS: { value: Interval; label: string }[] = [
  { value: "off", label: "Off" },
  { value: "90", label: "90 days" },
  { value: "180", label: "6 months" },
  { value: "365", label: "1 year" },
]

function toInterval(days: number | null): Interval {
  if (days === null) return "off"
  const match = INTERVALS.find((option) => option.value === String(days))
  return match ? match.value : "365"
}

/** A due date, which is usually in the future: "In 90 days · 31 Dec 2026", or "Overdue since …". */
function formatDueDate(iso: string): string {
  const at = new Date(iso)
  const days = Math.round((at.getTime() - Date.now()) / 86_400_000)
  const date = at.toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
    year: "numeric",
  })
  if (days < 0) return `Overdue since ${date}`
  if (days === 0) return `Today · ${date}`
  return `In ${days} ${days === 1 ? "day" : "days"} · ${date}`
}

function DueDate({ iso }: { iso: string }) {
  return <time dateTime={iso}>{formatDueDate(iso)}</time>
}

/**
 * The master-key rotation schedule (DE-FR-005): how often the key should be
 * rotated, whether that happens by itself (with re-wrapping) or as a
 * reminder, and when it is next due.
 */
export function RotationScheduleCard({
  policy,
  canEdit,
  onSaved,
}: {
  policy: RotationPolicyDto
  canEdit: boolean
  onSaved: () => void
}) {
  const [interval, setIntervalChoice] = useState<Interval>(
    toInterval(policy.intervalDays)
  )
  const [autoRotate, setAutoRotate] = useState(policy.autoRotate)
  const [saving, setSaving] = useState(false)
  const changed =
    interval !== toInterval(policy.intervalDays) ||
    autoRotate !== policy.autoRotate

  async function save() {
    setSaving(true)
    try {
      await saveRotationPolicy(
        interval === "off" ? null : Number(interval),
        interval !== "off" && autoRotate
      )
      toast.success("Rotation schedule saved.")
      onSaved()
    } catch (error) {
      toast.error(
        friendlyMessage(error, "Could not save the rotation schedule.")
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <StepCard
      icon={CalendarClock}
      title="Rotation schedule"
      description="How often your organisation's master key should be rotated. When it's due, it's either rotated and re-wrapped automatically, or recorded in the audit log as a reminder until someone rotates it."
    >
      {policy.due && (
        <p className="mb-4 flex items-start gap-2 rounded-lg border border-[var(--gold)] bg-[var(--gold-tint)] px-3.5 py-2.5 text-[12.5px] text-foreground">
          <AlertTriangle
            size={15}
            className="mt-px shrink-0 text-[var(--gold)]"
          />
          The key is due for rotation. Rotate it above, then re-wrap.
        </p>
      )}
      <dl className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatTile label="Key in use since">
          {policy.keySince ? (
            <FriendlyDate iso={policy.keySince} />
          ) : (
            "Not used yet"
          )}
        </StatTile>
        <StatTile label="Next rotation due">
          {policy.nextDueAt ? (
            <span className={policy.due ? "text-[var(--rust)]" : undefined}>
              <DueDate iso={policy.nextDueAt} />
            </span>
          ) : (
            "No schedule"
          )}
        </StatTile>
        <StatTile label="When due">
          {policy.intervalDays === null
            ? "Nothing"
            : policy.autoRotate
              ? "Rotated automatically"
              : "Reminder only"}
        </StatTile>
      </dl>
      {canEdit ? (
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <span className="text-[12.5px] font-medium">Rotate every</span>
            <SegmentedControl
              value={interval}
              onChange={setIntervalChoice}
              options={INTERVALS}
              disabled={saving}
            />
          </div>
          <label className="flex items-center gap-2.5 text-[13px]">
            <Switch
              checked={interval !== "off" && autoRotate}
              onCheckedChange={(checked) => setAutoRotate(checked)}
              disabled={saving || interval === "off"}
            />
            Rotate and re-wrap automatically when due
          </label>
          <div>
            <Button onClick={() => void save()} disabled={!changed || saving}>
              {saving && <Loader2 className="animate-spin" />}
              Save schedule
            </Button>
          </div>
        </div>
      ) : (
        <p className="text-[12.5px] text-muted-foreground">
          You can see the schedule but not change it.
        </p>
      )}
    </StepCard>
  )
}
