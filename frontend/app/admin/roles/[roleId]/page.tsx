"use client"

import Link from "next/link"
import { notFound, useParams } from "next/navigation"
import { ChevronLeft } from "lucide-react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RoleDetails } from "@/components/roles/role-details"
import { useRolesStore } from "@/stores/roles-store"

/**
 * Role Details / Assigned Users (R4) — read-only view of a role's
 * definition, permission summary, and assigned users.
 */
export default function RoleDetailsPage() {
  const params = useParams<{ roleId: string }>()
  const role = useRolesStore((state) =>
    state.roles.find((r) => r.id === params.roleId)
  )

  if (!role) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[760px]">
        <Link
          href="/admin/roles"
          className="mb-3.5 inline-flex items-center gap-1 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft size={14} />
          Back to Role List
        </Link>
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Role Details
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Definition, permissions, and users currently assigned to this role.
          </p>
        </div>
        <RoleDetails role={role} />
      </div>
    </DashboardShell>
  )
}
