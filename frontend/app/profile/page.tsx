"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { ProfileOverview } from "@/components/profile/profile-overview"

/**
 * My Profile (S1) — end-user account overview. Top-level route
 * (`/profile`, not under `/admin`), matching the wireframe's Self-Service
 * group and reached from the account menu in the top bar rather than the
 * sidebar nav.
 */
export default function ProfilePage() {
  return (
    <DashboardShell>
      <AdminBreadcrumb
        className="mb-3"
        segments={[{ label: "Dashboard", href: "/" }, { label: "My Profile" }]}
      />
      <div className="mb-[22px] max-w-[640px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            My Profile
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Your account information, role, and tenant.
          </p>
        </div>
        <ProfileOverview />
    </DashboardShell>
  )
}