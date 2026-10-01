"use client"

import Link from "next/link"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { PermissionGate } from "@/components/admin/permission-gate"
import { ExportJobsTable } from "@/components/export-jobs/export-jobs-table"
import { NewExportForm } from "@/components/export-jobs/new-export-form"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { useMyExportJobs } from "@/lib/export-jobs/use-my-export-jobs"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Bulk Export (Module 2.1): start an export of many documents with their
 * metadata, then download it from the list once it's ready. Starting one
 * needs fie.bulk-export-documents.execute-export and docm.documents.view
 * (checked server-side too); every export is also listed in My Exports.
 */
export default function BulkExportPage() {
  usePageTitle("Bulk Export")

  return (
    <DashboardShell initialActiveLabel="Bulk Export">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Bulk Export" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Bulk Export</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Export many documents at once as a ZIP of their files and a sheet of their metadata. Exports are built in
          the background and kept for a limited time, then removed automatically.
        </p>
      </div>
      <PermissionGate permission={[PermissionCodes.FIE_BULK_EXPORT_EXECUTE]}>
        <div className="flex max-w-[960px] flex-col gap-5">
          <NewExportForm />
          <RecentExports />
        </div>
      </PermissionGate>
    </DashboardShell>
  )
}

function RecentExports() {
  const { data, isPending } = useMyExportJobs(undefined, 1, 5)
  return (
    <section>
      <div className="mb-2 flex items-baseline justify-between">
        <h2 className="font-heading text-[16px] font-semibold">Your recent exports</h2>
        <Link href="/exports" className="text-[12.5px] font-medium text-primary hover:underline">
          All exports
        </Link>
      </div>
      {isPending ? <LoadingState message="Loading your exports…" /> : <ExportJobsTable rows={data?.content ?? []} filtered={false} />}
    </section>
  )
}
