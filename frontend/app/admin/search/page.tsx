"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { SearchStatus } from "@/components/admin/search-status"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { useIsTenantAdmin } from "@/lib/auth/use-is-tenant-admin"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * Search administration for the organisation (Document Search & Retrieval): the engine and index state, the check that
 * compares the index with the database, a reindex, and the saved-presets limit. Tenant administrators only, the same
 * rule the endpoints enforce.
 */
export default function SearchAdminPage() {
  usePageTitle("Search Index")
  const { isTenantAdmin, isPending } = useIsTenantAdmin()
  return (
    <DashboardShell initialActiveLabel="Search Index">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Search Index" }]} />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">Search index</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          See whether search is healthy, rebuild the index, and set how many saved searches each person may keep.
        </p>
      </div>
      {isPending ? (
        <LoadingState message="Checking your access…" />
      ) : isTenantAdmin ? (
        <SearchStatus />
      ) : (
        <p className="rounded-lg border border-border bg-card px-5 py-8 text-center text-[14px] text-muted-foreground">
          Only your organisation&rsquo;s administrators can manage search.
        </p>
      )}
    </DashboardShell>
  )
}
