"use client"

import { toast } from "sonner"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { CategoryGroups } from "@/components/notifications/category-groups"
import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage } from "@/lib/api-error"
import type { PreferenceDto } from "@/lib/notifications/api"
import { usePreferences, useUpdatePreference } from "@/lib/notifications/use-notifications"
import { usePageTitle } from "@/lib/use-page-title"

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

  return (
    <DashboardShell initialActiveLabel="Profile">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Profile", href: "/profile" }, { label: "Notifications" }]}
      />
      <div className="mb-5 max-w-[680px]">
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
        <CategoryGroups
          items={(data ?? []).map((p) => ({ ...p, enabled: p.emailEnabled }))}
          unit="by email"
          disabled={update.isPending}
          onToggle={(item) => {
            const p = (data ?? []).find((d) => d.categoryCode === item.categoryCode)
            if (p) toggle(p)
          }}
        />
      )}
    </DashboardShell>
  )
}
