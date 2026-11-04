"use client"

import { UserCog } from "lucide-react"

import { useCurrentUser } from "@/lib/auth/use-current-user"
import { useImpersonation } from "@/lib/auth/use-impersonation"

/**
 * Persistent, sticky strip shown on every admin page for the duration of an
 * "Act as User" session — the "clearly indicate this is an impersonated
 * session" requirement. Reads `impersonating`/`impersonatorDisplayName`
 * straight off `useCurrentUser()` (`GET /api/v1/users/me`), which is itself
 * derived server-side from the trusted session context (see backend
 * UserResponse's own doc comment) — there is nothing here for the frontend
 * to forge or misrepresent, it's purely a display of what the backend has
 * already decided.
 *
 * Same warning-toned palette as the "Pending" user badge (`--gold`/
 * `--gold-tint`/`--gold-foreground`) rather than an error color — being
 * impersonated is an expected, intentional admin action, not a fault
 * condition.
 */
export function ImpersonationBanner() {
  const { data: user } = useCurrentUser()
  const { stopImpersonation, isStopping } = useImpersonation()

  if (!user?.impersonating) {
    return null
  }

  return (
    <div className="flex items-center justify-center gap-2.5 border-b border-[var(--gold)] bg-[var(--gold-tint)] px-4 py-2 text-[13px] text-[var(--gold-foreground)]">
      <UserCog size={15} className="shrink-0" />
      <span>
        Viewing as <strong className="font-semibold">{user.displayName}</strong>
        {user.impersonatorDisplayName && (
          <> - impersonated by {user.impersonatorDisplayName}</>
        )}
      </span>
      <button
        type="button"
        onClick={() => stopImpersonation()}
        disabled={isStopping}
        className="ml-1 shrink-0 border border-[var(--gold-foreground)] text-[var(--gold-foreground)] transition-colors hover:bg-[var(--gold)]/20 disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] font-semibold inline-flex items-center justify-center"
      >
        {isStopping ? "Returning…" : "Return to Admin"}
      </button>
    </div>
  )
}
