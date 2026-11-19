"use client"

import { Suspense } from "react"

import { PermissionGate } from "@/components/admin/permission-gate"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { SearchView } from "@/components/search/search-view"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Search (Module DSR): keywords, phrases, details and the words inside documents, with filters and ranked, highlighted
 * results. Needs dsr.search-documents; the server also restricts every result to documents the caller may open.
 */
export default function SearchPage() {
  usePageTitle("Search")

  return (
    <DashboardShell initialActiveLabel="Search">
      <h1 className="mb-4 font-heading text-2xl font-semibold">Search</h1>
      <PermissionGate permission={PermissionCodes.DSR_SEARCH_DOCUMENTS}>
        <Suspense fallback={null}>
          <SearchView />
        </Suspense>
      </PermissionGate>
    </DashboardShell>
  )
}
