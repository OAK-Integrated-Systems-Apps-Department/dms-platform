"use client"

import { ChoiceRow, NumberRow, Section, SettingsShell } from "./settings-ui"
import { useSettingsTab } from "./use-settings-tab"

const NUMBERS: { key: string; title: string; description: string }[] = [
  { key: "maxRows", title: "Most rows in a report", description: "CSV and Excel files." },
  { key: "maxPdfRows", title: "Most rows in a PDF", description: "PDF has its own, lower limit because every page is laid out." },
  { key: "maxConcurrentRuns", title: "Reports running at once", description: "For the whole organisation. Others wait their turn." },
  { key: "fileRetentionDays", title: "Days a finished file is kept", description: "After this a file is deleted and the report must be run again." },
  { key: "maxTemplatesPerUser", title: "Saved reports per person", description: "Not counting official reports." },
  { key: "maxSchedulesPerUser", title: "Schedules per person", description: "Cancelled schedules do not count." },
  { key: "maxSchedulesPerTenant", title: "Schedules in the organisation", description: "All people together." },
]
const KEYS = [...NUMBERS.map((n) => n.key), "overLimitBehaviour"] as const

/** How big reports, files and schedules may get. Each number shows the platform ceiling it can never exceed. */
export function LimitsTab({ canEdit = true }: { canEdit?: boolean }) {
  const tab = useSettingsTab(KEYS)
  const locked = !canEdit || tab.saving
  return (
    <SettingsShell tab={tab} canEdit={canEdit}>
      <Section title="Limits" hint="You can set any of these lower than the platform ceiling, never higher.">
        {NUMBERS.map((n) => (
          <NumberRow key={n.key} title={n.title} description={n.description} value={tab.value(n.key)} ceiling={tab.ceilings[n.key]}
            onChange={(v) => tab.set(n.key, v)} disabled={locked} problem={tab.problems[n.key]} />
        ))}
        <ChoiceRow title="A report over the row limit" description="Refuse it and say how many rows it matched, or write the first rows and say the file is cut short."
          value={String(tab.value("overLimitBehaviour") ?? "fail")}
          options={[{ value: "fail", label: "Refuse it" }, { value: "truncate_and_mark", label: "Cut it short and mark the file" }]}
          onChange={(v) => tab.set("overLimitBehaviour", v)} disabled={locked} problem={tab.problems.overLimitBehaviour} />
      </Section>
    </SettingsShell>
  )
}
