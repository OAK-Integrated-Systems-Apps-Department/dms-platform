"use client"

import { useState } from "react"
import { toast } from "sonner"

import { fieldProblems } from "@/lib/reports/messages"
import { useAdminSettings, useSaveAdminSettings } from "@/lib/reports/use-reports"

/**
 * The settings the server accepts on PUT /admin/settings (ReportSettingsUpdate). The PUT REPLACES the whole object, so every tab sends all
 * of these - its own edits merged over what is currently stored - and saving one tab can never blank another's values.
 */
export const UPDATE_KEYS = [
  "enabled", "schedulingEnabled", "allowedFormats", "defaultFormat", "csvDelimiter", "csvIncludeBom", "dateFormat", "numberFormat",
  "defaultTimeZone", "defaultLocale", "headerText", "footerText", "confidentialityNotice", "showLogo", "showFilterSummary",
  "showGeneratedBy", "pdfPageSize", "pdfOrientation", "maxRows", "maxPdfRows", "overLimitBehaviour", "maxConcurrentRuns",
  "fileRetentionDays", "maxTemplatesPerUser", "maxSchedulesPerUser", "maxSchedulesPerTenant", "minScheduleInterval",
  "maxRecipientsPerSchedule", "allowedDeliveryChannels", "deliveryModeDefault", "allowAttachments", "maxAttachmentMb",
  "allowExternalRecipients", "externalDomainAllowlist", "failureNotifyAdmins", "failureAdminUserIds",
  "pauseAfterConsecutiveFailures", "catchUpWindowHours", "skipEmptyScheduledReports", "includeInactiveByDefault",
  "defaultColumns", "defaultSort",
] as const

type Settings = Record<string, unknown>

/** The whole object a save sends: the stored settings with the given edits on top, restricted to what the server accepts. */
export function mergedSettings(stored: Settings, edits: Settings): Settings {
  const merged = { ...stored, ...edits }
  const out: Settings = {}
  for (const key of UPDATE_KEYS) {
    if (key in merged) out[key] = merged[key]
  }
  return out
}

export interface Change {
  key: string
  from: unknown
  to: unknown
}

const same = (a: unknown, b: unknown) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null)

/**
 * The state of one settings tab: the stored settings, this tab's unsaved edits, what differs, per-field problems from the server, and a
 * save that merges into the full object. `keys` are the settings this tab owns (used for the change list and for discarding).
 */
export function useSettingsTab(keys: readonly string[]) {
  const query = useAdminSettings()
  const mutation = useSaveAdminSettings()
  const stored: Settings | undefined = query.data?.settings
  const ceilings = (query.data?.ceilings ?? {}) as Record<string, number | boolean>
  const [edits, setEdits] = useState<Settings>({})
  const [problems, setProblems] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState<Change[]>([])

  const value = (key: string): unknown => (key in edits ? edits[key] : stored?.[key])
  const changes: Change[] = keys
    .filter((key) => key in edits && !same(edits[key], stored?.[key]))
    .map((key) => ({ key, from: stored?.[key], to: edits[key] }))

  function set(key: string, next: unknown) {
    setEdits((current) => ({ ...current, [key]: next }))
    setSaved([])
    setProblems((current) => {
      if (!(key in current)) return current
      const rest = { ...current }
      delete rest[key]
      return rest
    })
  }

  function discard() {
    setEdits({})
    setProblems({})
  }

  async function save(): Promise<boolean> {
    if (!stored || changes.length === 0) return false
    const made = changes
    try {
      await mutation.mutateAsync(mergedSettings(stored, edits))
      setEdits({})
      setProblems({})
      setSaved(made)
      return true
    } catch (error) {
      const found = fieldProblems(error)
      setProblems(found)
      if (Object.keys(found).length > 0) toast.error("Some settings are not valid. See the messages next to them.")
      else toast.error("Could not save the report settings.")
      return false
    }
  }

  return {
    query,
    ready: Boolean(stored),
    value,
    set,
    changes,
    saved,
    problems,
    ceilings,
    save,
    discard,
    saving: mutation.isPending,
    /** The full settings as they would be saved right now - what the live sample is made from. */
    merged: (): Settings => (stored ? mergedSettings(stored, edits) : {}),
  }
}
