"use client"

import { Lock } from "lucide-react"
import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage } from "@/lib/api-error"
import type { PreferenceDto } from "@/lib/notifications/api"
import { usePreferences, useUpdatePreference } from "@/lib/notifications/use-notifications"
import { usePageTitle } from "@/lib/use-page-title"

const TIER_NOTE: Record<string, string> = {
  critical: "Always on - these security and escalation alerts can't be turned off.",
  standard: "On by default. Turn email off to get these in the inbox only.",
  optional: "Off by default. Turn email on to also be emailed.",
}

/** The caller's notification email preferences (3.2). In-app is always on; this is email only. */
export default function NotificationPreferencesPage() {
  usePageTitle("Notification preferences")
  const { data, isPending, isError, error } = usePreferences()
  const update = useUpdatePreference()

  function toggle(p: PreferenceDto) {
    if (p.locked) return
    update.mutate(
      { categoryCode: p.categoryCode, emailEnabled: !p.emailEnabled },
      { onError: (e) => toast.error(friendlyMessage(e, "Could not save your preference.")) }
    )
  }

  const groups = (data ?? []).reduce<Record<string, PreferenceDto[]>>((acc, p) => {
    ;(acc[p.module] ??= []).push(p)
    return acc
  }, {})

  return (
    <DashboardShell initialActiveLabel="Profile">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Profile", href: "/profile" }, { label: "Notifications" }]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Notification preferences</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Choose which notifications also reach you by email. You always see every notification in your inbox.
        </p>
      </div>

      {isPending ? (
        <LoadingState message="Loading your preferences…" />
      ) : isError ? (
        <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)]">
          {error instanceof Error ? error.message : "Could not load your preferences."}
        </div>
      ) : (
        <div className="flex max-w-[720px] flex-col gap-6">
          {Object.entries(groups).map(([module, prefs]) => (
            <section key={module}>
              <h2 className="mb-2 font-heading text-[15px] font-semibold capitalize">{module}</h2>
              <ul className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
                {prefs.map((p) => (
                  <li key={p.categoryCode} className="flex items-center gap-4 border-b border-border px-5 py-3.5 last:border-b-0">
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 text-[13.5px] font-semibold text-foreground">
                        {p.displayName}
                        {p.locked && (
                          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-px text-[10.5px] font-semibold text-muted-foreground">
                            <Lock size={10} /> Always on
                          </span>
                        )}
                      </div>
                      {p.description && <p className="text-[12px] text-muted-foreground">{p.description}</p>}
                      <p className="mt-0.5 text-[11px] text-[var(--ink-faint)]">{TIER_NOTE[p.tier]}</p>
                    </div>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={p.emailEnabled}
                      disabled={p.locked || update.isPending}
                      onClick={() => toggle(p)}
                      className={`relative h-6 w-11 shrink-0 rounded-full transition-colors ${
                        p.emailEnabled ? "bg-primary" : "bg-[var(--border-strong)]"
                      } ${p.locked ? "cursor-not-allowed opacity-60" : ""}`}
                    >
                      <span
                        className={`absolute top-0.5 size-5 rounded-full bg-white transition-all ${
                          p.emailEnabled ? "left-[22px]" : "left-0.5"
                        }`}
                      />
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </DashboardShell>
  )
}
