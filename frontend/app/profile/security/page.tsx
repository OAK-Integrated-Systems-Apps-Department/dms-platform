"use client"

import Link from "next/link"
import { ChevronLeft } from "lucide-react"

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
        <Link
          href="/profile"
          className="mb-3.5 inline-flex items-center gap-1 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft size={14} />
          Back to My Profile
        </Link>
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