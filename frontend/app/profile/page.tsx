"use client"

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
      <div className="mx-auto w-full max-w-[640px]">
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            My Profile
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Your account information, role, and tenant.
          </p>
        </div>
        <ProfileOverview />
      </div>
    </DashboardShell>
  )
}
