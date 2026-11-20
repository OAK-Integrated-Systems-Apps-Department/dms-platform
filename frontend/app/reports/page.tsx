"use client"

import { useSearchParams } from "next/navigation"
import { Suspense, useState } from "react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { MyReportsTab } from "@/components/reports/my-reports-tab"
import { RunHistoryTab } from "@/components/reports/run-history-tab"
import { SchedulesTab } from "@/components/reports/schedules-tab"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"

type Tab = "mine" | "history" | "schedules"

/**
 * Reports (5.3): the reports the person owns or was given, the files they have asked for or been sent, and their schedules. Each tab
 * shows only what their permissions allow; the backend enforces them all again.
 */
export default function ReportsPage() {
  return (
    <Suspense fallback={null}>
      <ReportsContent />
    </Suspense>
  )
}

function ReportsContent() {
  usePageTitle("Reports")
  const params = useSearchParams()
  const scheduleTemplateId = params.get("schedule")
  const requested = params.get("tab")
  const { hasAnyPermission } = usePermissions()
  const canSchedule = hasAnyPermission(PermissionCodes.CRG_SCHEDULE_CREATE, PermissionCodes.CRG_SCHEDULE_UPDATE_CANCEL)
  const tabs: { value: Tab; label: string }[] = [
    { value: "mine", label: "My reports" },
    { value: "history", label: "Run history" },
    ...(canSchedule ? ([{ value: "schedules", label: "Schedules" }] as const) : []),
  ]
  const [chosen, setChosen] = useState<Tab>(requested === "history" ? "history" : requested === "schedules" ? (scheduleTemplateId ? "mine" : "schedules") : "mine")
  const tab: Tab = tabs.some((t) => t.value === chosen) ? chosen : "mine"

  return (
    <DashboardShell initialActiveLabel="Reports" fillHeight>
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Reports" }]} />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">Reports</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Build a report on your documents, run it as a CSV, Excel or PDF file, and have it sent on a schedule. A report only ever
          contains the documents you have access to.
        </p>
      </div>
      <PermissionGate permission={[PermissionCodes.CRG_RUN, PermissionCodes.CRG_BUILD]}>
        <div className="mb-4">
          <SegmentedControl value={tab} onChange={setChosen} options={tabs} aria-label="Reports sections" />
        </div>
        <div className="flex min-h-0 flex-1 flex-col [&>div]:flex-1">
          {tab === "mine" && <MyReportsTab onViewHistory={() => setChosen("history")} scheduleTemplateId={scheduleTemplateId} />}
          {tab === "history" && <RunHistoryTab />}
          {tab === "schedules" && canSchedule && <SchedulesTab />}
        </div>
      </PermissionGate>
    </DashboardShell>
  )
}
