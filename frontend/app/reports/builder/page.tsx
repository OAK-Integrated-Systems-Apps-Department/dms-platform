"use client"

import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { ReportBuilder } from "@/components/reports/builder/report-builder"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/** A new report: the guided builder with the organisation's default columns. */
export default function NewReportPage() {
  usePageTitle("Build a report")
  return (
    <DashboardShell initialActiveLabel="Reports">
      <PermissionGate permission={PermissionCodes.CRG_BUILD}>
        <ReportBuilder />
      </PermissionGate>
    </DashboardShell>
  )
}
