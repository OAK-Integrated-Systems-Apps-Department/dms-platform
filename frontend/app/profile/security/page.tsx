"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { SecurityOverview } from "@/components/profile/security-overview"

/**
 * My Security Settings (S2) — password and MFA status, deep-linking into
 * Keycloak's Account Console for the actual actions. Nested under
 * `app/profile/`, matching the wireframe's URL (`/profile/security`) and
 * this project's existing nested-route convention
 * (`app/admin/roles/[roleId]/edit`, `.../permissions`).
 */
export default function SecurityPage() {
  return (
    <DashboardShell>
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "My Profile", href: "/profile" },
          { label: "Security" },
        ]}
      />

      <div className="mb-[22px] max-w-[640px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
          My Security Settings
        </h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Password and multi-factor authentication for your account.
        </p>
      </div>
      <SecurityOverview />
    </DashboardShell>
  )
}
