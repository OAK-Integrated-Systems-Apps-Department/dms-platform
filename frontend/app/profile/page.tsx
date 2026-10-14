"use client"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PlatformAdminProfile } from "@/components/profile/platform-admin-profile"
import { ProfileOverview } from "@/components/profile/profile-overview"
import { SecurityOverview } from "@/components/profile/security-overview"
import { ApiError } from "@/lib/api-error"
import { useCurrentUser } from "@/lib/auth/use-current-user"

/**
 * My Profile (S1) — end-user account overview. Top-level route
 * (`/profile`, not under `/admin`), matching the wireframe's Self-Service
 * group and reached from the account menu in the top bar rather than the
 * sidebar nav.
 *
 * Password and Multi-Factor Authentication (formerly the standalone My
 * Security Settings screen at `/profile/security`, S2) are rendered here
 * too, directly below the profile card — that route has been removed;
 * see `SecurityOverview`'s own doc comment.
 */
export default function ProfilePage() {
  // A platform administrator belongs to no tenant and so has no local user
  // row: `GET /api/v1/users/me` answers 401 TENANT_CONTEXT_MISSING for them
  // by design. Both cards below are built on that call, so without this
  // branch the one account that administers the platform sees two errors
  // where its profile should be.
  //
  // FOLLOW-UP: this has the same flash the home dashboard had. While
  // /users/me is still in flight there is no error yet, so `noLocalProfile`
  // is false and the tenant profile cards render first, then swap to
  // PlatformAdminProfile when the 401 arrives. `useDashboardScope` returns
  // the pending state explicitly for exactly this; the fix here is to wait
  // on it rather than to read the error alone. Left alone for now because
  // this page was not in scope for the dashboard fix.
  const { error } = useCurrentUser()
  const noLocalProfile =
    error instanceof ApiError && error.code === "TENANT_CONTEXT_MISSING"

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
          {noLocalProfile
            ? "Your account details, and where to change them."
            : "Your account information, role, and tenant."}
        </p>
      </div>
      {noLocalProfile ? (
        <PlatformAdminProfile />
      ) : (
        <div className="flex flex-col gap-5">
          <ProfileOverview />
          <SecurityOverview />
        </div>
      )}
    </DashboardShell>
  )
}
