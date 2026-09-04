"use client"

import Link from "next/link"
import { ArrowRight } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { useCurrentUser } from "@/lib/auth/use-current-user"

import { profileSupplement } from "./data"

/**
 * My Profile (S1) — end-user, read-mostly view of their own account:
 * name, login/email, tenant, role(s), and account/security info. Unlike
 * the admin User Detail screen, this reads the self-service
 * `/api/v1/users/me` (`useCurrentUser`) rather than the admin API — a
 * regular user's token has no access to the admin endpoints used
 * elsewhere in Role/User Management.
 *
 * Corner radius is fixed at 10px across every surface here (card, avatar,
 * badges, button), matching Role Details and the Permission Matrix rather
 * than the app-wide 9px/14px mix used elsewhere.
 */
export function ProfileOverview() {
  const { data: user, initials, isLoading } = useCurrentUser()

  return (
    <div className="rounded-[10px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Identity */}
      <div className="mb-6 flex items-center gap-4">
        <span className="flex size-14 shrink-0 items-center justify-center rounded-[10px] bg-primary font-heading text-[18px] font-bold text-primary-foreground">
          {initials ?? "…"}
        </span>
        <div>
          <p className="mb-0.5 font-heading text-[17px] font-semibold">
            {isLoading ? "Loading…" : (user?.displayName ?? "—")}
          </p>
          <p className="font-mono text-[12px] text-muted-foreground">
            {user?.email ?? "—"}
          </p>
        </div>
      </div>

      {/* Key/value grid */}
      <div className="grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-4">
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Tenant
          </p>
          <p className="font-mono text-[12.5px] text-foreground">
            {user?.tenantId ?? "—"}
          </p>
        </div>
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Role(s)
          </p>
          <div className="flex flex-wrap gap-1.5">
            {profileSupplement.roles.map((role) => (
              <Badge key={role} variant="outline" className="rounded-[10px]">
                {role}
              </Badge>
            ))}
          </div>
        </div>
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Account Created
          </p>
          <p className="font-mono text-[12.5px] text-foreground">
            {user?.createdAt ? user.createdAt.slice(0, 10) : "—"}
          </p>
        </div>
        <div>
          <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
            Last Login
          </p>
          <p className="font-mono text-[12.5px] text-foreground">
            {profileSupplement.lastLogin}
          </p>
        </div>
      </div>

      <hr className="my-6 border-dashed border-border" />

      {/* Actions */}
      <div className="flex justify-end">
        <Link
          href="/profile/security"
          className="flex items-center gap-1.5 rounded-[10px] border border-[var(--border-strong)] bg-card px-4 py-2.5 text-[13px] font-semibold text-foreground"
        >
          Go to Security Settings
          <ArrowRight size={14} />
        </Link>
      </div>

      <p className="mt-3.5 text-[12px] text-muted-foreground">
        Read-mostly screen — identity fields follow the same Keycloak-managed
        rules as the admin Edit User screen.
      </p>
    </div>
  )
}
