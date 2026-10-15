"use client"

import Link from "next/link"
import { Bell, ChevronRight } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { ProfileOverview } from "@/components/profile/profile-overview"
import { SecurityOverview } from "@/components/profile/security-overview"

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
        <div className="flex flex-col gap-5">
          <ProfileOverview />
          <SecurityOverview />
          <Link
            href="/profile/notifications"
            className="flex items-center gap-3 rounded-lg border border-border bg-card px-5 py-4 shadow-[var(--ledger-shadow)] hover:bg-muted"
          >
            <span className="flex size-9 items-center justify-center rounded-full bg-primary/10 text-primary">
              <Bell size={17} />
            </span>
            <span className="min-w-0 flex-1">
              <span className="block text-[14px] font-semibold text-foreground">Notification preferences</span>
              <span className="block text-[12.5px] text-muted-foreground">
                Choose which notifications also reach you by email.
              </span>
            </span>
            <ChevronRight size={16} className="text-muted-foreground" />
          </Link>
        </div>
    </DashboardShell>
  )
}