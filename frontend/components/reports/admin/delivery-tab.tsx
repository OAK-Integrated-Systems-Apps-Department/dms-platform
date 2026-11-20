"use client"

import { ChoiceRow, MultiChoiceRow, NumberRow, Row, Section, SettingsShell, ToggleRow } from "./settings-ui"
import { AdminSchedules } from "./admin-schedules"
import { UserChips } from "./principal-picker"
import { useSettingsTab } from "./use-settings-tab"

const KEYS = [
  "allowedDeliveryChannels", "deliveryModeDefault", "allowAttachments", "maxAttachmentMb", "allowExternalRecipients",
  "externalDomainAllowlist", "maxRecipientsPerSchedule", "minScheduleInterval", "pauseAfterConsecutiveFailures",
  "catchUpWindowHours", "failureNotifyAdmins", "failureAdminUserIds", "skipEmptyScheduledReports",
] as const

const list = (v: unknown) => (Array.isArray(v) ? (v as string[]) : [])

/** How scheduled reports are delivered and what happens when one fails, then every schedule in the organisation. */
export function DeliveryTab({ canEdit = true }: { canEdit?: boolean }) {
  const tab = useSettingsTab(KEYS)
  const locked = !canEdit || tab.saving
  const p = tab.problems
  const domains = list(tab.value("externalDomainAllowlist"))
  const external = tab.value("allowExternalRecipients") === true
  const attachments = tab.value("allowAttachments") === true
  return (
    <div className="flex w-full flex-col gap-6">
      <SettingsShell tab={tab} canEdit={canEdit}>
        <Section title="Delivery" hint="How a finished scheduled report reaches people.">
          <MultiChoiceRow title="Channels" description="In-app is a notification with a link. Email sends the link or the file."
            value={list(tab.value("allowedDeliveryChannels"))} options={[{ value: "in_app", label: "In-app" }, { value: "email", label: "Email" }]}
            onChange={(v) => tab.set("allowedDeliveryChannels", v)} disabled={locked} problem={p.allowedDeliveryChannels} />
          <ToggleRow title="Allow attachments" description="Email the file itself. Otherwise people get a link and sign in to download."
            checked={attachments} onChange={(v) => tab.set("allowAttachments", v)} disabled={locked} problem={p.allowAttachments} />
          <ChoiceRow title="Default delivery" value={String(tab.value("deliveryModeDefault") ?? "link")}
            options={[{ value: "link", label: "Link" }, ...(attachments ? [{ value: "attachment", label: "Attachment" }] : [])]}
            onChange={(v) => tab.set("deliveryModeDefault", v)} disabled={locked} problem={p.deliveryModeDefault} />
          <NumberRow title="Largest attachment (MB)" description="A bigger file is sent as a link instead." value={tab.value("maxAttachmentMb")}
            ceiling={tab.ceilings.maxAttachmentMb} onChange={(v) => tab.set("maxAttachmentMb", v)} disabled={locked || !attachments} problem={p.maxAttachmentMb} />
          <NumberRow title="Recipients per schedule" value={tab.value("maxRecipientsPerSchedule")} ceiling={tab.ceilings.maxRecipientsPerSchedule}
            onChange={(v) => tab.set("maxRecipientsPerSchedule", v)} disabled={locked} problem={p.maxRecipientsPerSchedule} />
        </Section>
        <Section title="External recipients" hint="People outside the organisation get the file as an attachment, made with the schedule owner's access.">
          <ToggleRow title="Allow external email addresses" checked={external} onChange={(v) => tab.set("allowExternalRecipients", v)}
            disabled={locked} problem={p.allowExternalRecipients} />
          <Row title="Allowed domains" description="One per line, for example example.com. Leave empty to allow any domain." problem={p.externalDomainAllowlist}>
            <textarea aria-label="Allowed domains" className="min-h-[84px] w-[260px] max-w-full rounded-md border border-input bg-transparent px-3 py-2 text-[13px]"
              disabled={locked || !external} value={domains.join("\n")}
              onChange={(e) => tab.set("externalDomainAllowlist", e.target.value.split("\n").map((d) => d.trim()).filter(Boolean))} />
          </Row>
        </Section>
        <Section title="Timing and failures" hint="When a schedule runs late or keeps failing.">
          <ChoiceRow title="Shortest interval" value={String(tab.value("minScheduleInterval") ?? "daily")}
            options={[{ value: "daily", label: "Daily" }, { value: "hourly", label: "Hourly (if the platform allows)" }]}
            onChange={(v) => tab.set("minScheduleInterval", v)} disabled={locked} problem={p.minScheduleInterval} />
          <NumberRow title="Catch-up window (hours)" description="A schedule that missed its time by less than this still runs." value={tab.value("catchUpWindowHours")}
            ceiling={tab.ceilings.catchUpWindowHours} onChange={(v) => tab.set("catchUpWindowHours", v)} disabled={locked} problem={p.catchUpWindowHours} />
          <NumberRow title="Pause after failures" description="Consecutive failed runs before a schedule pauses itself." value={tab.value("pauseAfterConsecutiveFailures")}
            onChange={(v) => tab.set("pauseAfterConsecutiveFailures", v)} disabled={locked} problem={p.pauseAfterConsecutiveFailures} />
          <ToggleRow title="Skip empty scheduled reports" description="Send nothing when a scheduled report finds no documents."
            checked={tab.value("skipEmptyScheduledReports") === true} onChange={(v) => tab.set("skipEmptyScheduledReports", v)} disabled={locked}
            problem={p.skipEmptyScheduledReports} />
          <ToggleRow title="Tell administrators about failures" checked={tab.value("failureNotifyAdmins") === true}
            onChange={(v) => tab.set("failureNotifyAdmins", v)} disabled={locked} problem={p.failureNotifyAdmins} />
          <Row title="Failure alerts go to" description="These people are told when a schedule pauses itself." problem={p.failureAdminUserIds}>
            <UserChips value={list(tab.value("failureAdminUserIds"))} onChange={(v) => tab.set("failureAdminUserIds", v)} disabled={locked} />
          </Row>
        </Section>
      </SettingsShell>
      <AdminSchedules />
    </div>
  )
}
