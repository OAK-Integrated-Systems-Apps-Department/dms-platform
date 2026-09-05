"use client"

import { notFound, useParams, useRouter } from "next/navigation"
import Link from "next/link"
import { ChevronLeft, Loader2 } from "lucide-react"

import { DashboardShell } from "@/components/layout/dashboard-shell"
import { RolePermissionConfig } from "@/components/roles/role-permission-config"
import { useRole } from "@/components/roles/use-role"
import { useRolesStore } from "@/stores/roles-store"

/**
 * Role Permission Configuration (R3) — configure the permissions granted
 * by a single role, reached from the Edit Role screen's
 * "Configure Permissions" action.
 *
 * Uses `useRole` (fetches the single role individually if a direct link
 * lands here before the Role List has populated the store) rather than a
 * bare store lookup — same reasoning as Role Details / Edit Role.
 * `onSave` now calls `syncRolePermissions`, which reconciles the real
 * `role_permissions` grants against the desired set via
 * `/api/v1/roles/{roleId}/permissions` — see that function's doc comment
 * in `stores/roles-store.ts` for how the diff/grant/revoke works.
 */
export default function RolePermissionsPage() {
  const router = useRouter()
  const params = useParams<{ roleId: string }>()
  const { role, loading, notFound: roleNotFound } = useRole(params.roleId)
  const syncRolePermissions = useRolesStore((state) => state.syncRolePermissions)

  if (roleNotFound) {
    notFound()
  }

  async function handleSave(permissions: string[]): Promise<string | null> {
    if (!role) return "Role not loaded yet."
    const { error } = await syncRolePermissions(role.id, permissions)
    if (error) return error
    router.push(`/admin/roles/${role.id}/edit`)
    return null
  }

  return (
    <DashboardShell initialActiveLabel="Users & Roles">
      <div className="mx-auto w-full max-w-[760px]">
        <Link
          href={role ? `/admin/roles/${role.id}/edit` : "/admin/roles"}
          className="mb-3.5 inline-flex items-center gap-1 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft size={14} />
          {role ? `Back to ${role.name}` : "Back to Role List"}
        </Link>
        <div className="mb-[22px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">
            Role Permission Configuration
          </h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            {role
              ? <>Choose what {role.name} can do in the current tenant, grouped by functional area.</>
              : "Choose what this role can do in the current tenant, grouped by functional area."}
          </p>
        </div>

        {loading ? (
          <div className="flex items-center justify-center gap-2 rounded-[14px] border border-border bg-card py-16 text-[13.5px] text-muted-foreground shadow-[var(--ledger-shadow)]">
            <Loader2 size={16} className="animate-spin" />
            Loading role…
          </div>
        ) : role ? (
          <RolePermissionConfig role={role} onSave={handleSave} />
        ) : null}
      </div>
    </DashboardShell>
  )
}
