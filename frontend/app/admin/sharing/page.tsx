"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { SharingAdmin } from "@/components/sharing/sharing-admin"
import { LoadingState } from "@/components/ui/loading-state"
import { useIsTenantAdmin } from "@/lib/auth/use-is-tenant-admin"
import { usePageTitle } from "@/lib/use-page-title"

/**
 * External sharing (1.5): the organisation's policy, every external link with the "needs review" queue, and guest
 * accounts. Tenant administrators only - the backend enforces it; this just stops the page offering what it would refuse.
 */
export default function ExternalSharingPage() {
  usePageTitle("External Sharing")
  const { isTenantAdmin, isPending } = useIsTenantAdmin()
  return (
    <DashboardShell initialActiveLabel="External Sharing">
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "External Sharing" }]}
      />
      <div className="mb-5">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          External sharing
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Control how documents and folders are shared with people outside your organisation, and
          review every link and guest account.
        </p>
      </div>
      {isPending ? (
        <LoadingState message="Checking your access…" />
      ) : isTenantAdmin ? (
        <SharingAdmin />
      ) : (
        <p className="rounded-btn border border-border bg-card p-6 text-[13.5px] text-muted-foreground">
          Only organisation administrators can manage external sharing.
        </p>
      )}
    </DashboardShell>
  )
}
