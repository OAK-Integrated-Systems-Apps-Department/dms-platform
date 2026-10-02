"use client"

import Link from "next/link"

import { PermissionGate } from "@/components/admin/permission-gate"
import { ExportJobsTable } from "@/components/export-jobs/export-jobs-table"
import { NewExportForm } from "@/components/export-jobs/new-export-form"
import { DocumentsHub } from "@/components/documents/documents-hub"
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
    <DashboardShell initialActiveLabel="My Documents">
      <DocumentsHub active="bulk-export">
        <PermissionGate permission={[PermissionCodes.FIE_BULK_EXPORT_EXECUTE]}>
          <div className="flex max-w-[960px] flex-col gap-5">
            <NewExportForm />
            <RecentExports />
          </div>
        </PermissionGate>
      </DocumentsHub>
    </DashboardShell>
  )
}

function RecentExports() {
  const { data, isPending } = useMyExportJobs(undefined, 1, 5)
  return (
    <section>
      <div className="mb-2 flex items-baseline justify-between">
        <h2 className="font-heading text-[16px] font-semibold">
          Your recent exports
        </h2>
        <Link
          href="/documents/exports"
          className="text-[12.5px] font-medium text-primary hover:underline"
        >
          All exports
        </Link>
      </div>
      {isPending ? (
        <LoadingState message="Loading your exports…" />
      ) : (
        <ExportJobsTable rows={data?.content ?? []} filtered={false} />
      )}
    </section>
  )
}
