"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { HelpCenter } from "@/components/help/help-center"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { usePageTitle } from "@/lib/use-page-title"

/** Help: answers about using the app, opened from the top bar's Help button. */
export default function HelpPage() {
  usePageTitle("Help")

  return (
    <DashboardShell initialActiveLabel="Help">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "Help" }]}
      />
      <HelpCenter />
    </DashboardShell>
  )
}
