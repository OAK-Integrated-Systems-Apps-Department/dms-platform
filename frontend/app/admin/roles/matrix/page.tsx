"use client"

import Link from "next/link"
import { ChevronLeft } from "lucide-react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { PermissionMatrix } from "@/components/roles/permission-matrix"

/**
 * Permission Matrix (R5) — administrative Roles × Permissions grid.
 * Static route, sibling of the `[roleId]` dynamic segment; Next.js resolves
 * `/admin/roles/matrix` against this literal folder before it would ever
 * match `[roleId]`, so no route conflict.
 */
export default function PermissionMatrixPage() {
  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[920px]">
        <Link
          href="/admin/roles"
          className="mb-3.5 inline-flex items-center gap-1 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft size={14} />
          Back to Role List
        </Link>
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Permission Matrix
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            See which role can perform each DMS action, grouped by functional
            area.
          </p>
        </div>
        <PermissionMatrix />
      </div>
    </DashboardShell>
  )
}
