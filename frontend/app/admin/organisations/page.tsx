"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { Organisations } from "@/components/admin/organisations"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { useIsPlatformAdmin } from "@/lib/auth/use-is-platform-admin"
import { usePageTitle } from "@/lib/use-page-title"

/** Platform admins only: every organisation, and suspending, deactivating or crypto-shredding one. */
export default function OrganisationsPage() {
  usePageTitle("Organisations")
  const { isPlatformAdmin, isPending } = useIsPlatformAdmin()

  return (
    <DashboardShell initialActiveLabel="Organisations">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "Organisations" },
        ]}
      />
      <div className="mb-5 max-w-[760px]">
        <h1 className="mb-1.5 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          Organisations
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Every organisation on the platform. Suspending one signs its users out
          at once; a deactivated one can be reactivated until its encryption
          keys are destroyed.
        </p>
      </div>
      {isPending ? (
        <LoadingState message="Checking your access…" />
      ) : isPlatformAdmin ? (
        <Organisations />
      ) : (
        <p className="text-[13px] text-muted-foreground">
          Only platform administrators can manage organisations.
        </p>
      )}
    </DashboardShell>
  )
}
