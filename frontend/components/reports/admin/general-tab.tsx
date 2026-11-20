"use client"

import { ChoiceRow, Section, SettingsShell, TextRow, ToggleRow } from "./settings-ui"
import { useSettingsTab } from "./use-settings-tab"

const KEYS = [
  "enabled", "schedulingEnabled", "defaultTimeZone", "defaultLocale", "dateFormat", "numberFormat", "includeInactiveByDefault",
] as const

const str = (v: unknown) => (typeof v === "string" ? v : "")

/** Reports on or off for the organisation, and how dates, numbers and time are shown by default. */
export function GeneralTab({ canEdit = true }: { canEdit?: boolean }) {
  const tab = useSettingsTab(KEYS)
  const locked = !canEdit || tab.saving
  const p = tab.problems
  return (
    <SettingsShell tab={tab} canEdit={canEdit}>
      <Section title="Reporting" hint="Switch the whole feature, or just automatic delivery, on or off.">
        <ToggleRow title="Enable reporting" description="When off, nobody in the organisation can build, run or schedule reports. Administrators can still change these settings."
          checked={tab.value("enabled") === true} onChange={(v) => tab.set("enabled", v)} disabled={locked} problem={p.enabled} />
        <ToggleRow title="Enable scheduling" description="Allow reports to be run and delivered automatically."
          checked={tab.value("schedulingEnabled") === true} onChange={(v) => tab.set("schedulingEnabled", v)} disabled={locked} problem={p.schedulingEnabled} />
      </Section>
      <Section title="Defaults" hint="What people see until they choose otherwise.">
        <TextRow title="Default time zone" description="An IANA name such as Asia/Colombo or Europe/London. Used for relative dates and printed times."
          value={str(tab.value("defaultTimeZone"))} onChange={(v) => tab.set("defaultTimeZone", v)} disabled={locked} problem={p.defaultTimeZone} />
        <TextRow title="Locale" description="A language tag such as en-GB or si-LK." value={str(tab.value("defaultLocale"))}
          onChange={(v) => tab.set("defaultLocale", v)} disabled={locked} problem={p.defaultLocale} />
        <TextRow title="Date format" description="For example yyyy-MM-dd or dd/MM/yyyy." value={str(tab.value("dateFormat"))}
          onChange={(v) => tab.set("dateFormat", v)} disabled={locked} problem={p.dateFormat} />
        <TextRow title="Number format" description="For example #,##0.00." value={str(tab.value("numberFormat"))}
          onChange={(v) => tab.set("numberFormat", v)} disabled={locked} problem={p.numberFormat} />
        <ChoiceRow title="Inactive and archived documents" description="Whether a new report includes them until the person changes it."
          value={tab.value("includeInactiveByDefault") === true ? "include" : "leave-out"}
          options={[{ value: "leave-out", label: "Leave out" }, { value: "include", label: "Include" }]}
          onChange={(v) => tab.set("includeInactiveByDefault", v === "include")} disabled={locked} problem={p.includeInactiveByDefault} />
      </Section>
    </SettingsShell>
  )
}
