"use client"

import { useParams } from "next/navigation"

import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { ReportBuilder } from "@/components/reports/builder/report-builder"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * A saved report. Someone who may change it gets the builder; someone who may only run it (shared with run access, or locked by its
 * publisher) gets the reduced "Run with options" view - which is why the run permission opens this page too.
 */
export default function EditReportPage() {
  usePageTitle("Edit report")
  const params = useParams<{ templateId: string }>()
  return (
    <DashboardShell initialActiveLabel="Reports">
      <PermissionGate permission={[PermissionCodes.CRG_BUILD, PermissionCodes.CRG_RUN]}>
        <ReportBuilder templateId={params.templateId} />
      </PermissionGate>
    </DashboardShell>
  )
}
