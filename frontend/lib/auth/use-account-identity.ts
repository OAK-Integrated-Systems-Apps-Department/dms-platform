"use client"

import { useQuery } from "@tanstack/react-query"

import { initialsOf, useCurrentUser } from "@/lib/auth/use-current-user"
import { fetchMyIdentity } from "@/lib/users/api"

/**
 * Who to show in the account menu, for every kind of signed-in caller.
 *
 * `useCurrentUser` alone is not enough: it reads `GET /api/v1/users/me`,
 * which answers 401 TENANT_CONTEXT_MISSING for a platform administrator -
 * they belong to no tenant and so have no local user row. The account menu
 * then had nothing to render and fell back to a literal "…", so the one
 * account that administers the platform appeared as an ellipsis in its own
 * avatar and name.
 *
 * Falls back to `GET /api/v1/users/me/identity`, which reads the caller's
 * own token and needs no tenant context. The fallback only fires when the
 * local lookup actually failed, so an ordinary user makes no extra request.
 */
export function useAccountIdentity() {
  const { data: user, initials, isError: noLocalProfile } = useCurrentUser()

  const identity = useQuery({
    queryKey: ["current-user-identity"],
    queryFn: fetchMyIdentity,
    enabled: noLocalProfile,
    retry: false,
  })

  if (user) {
    return {
      displayName: user.displayName,
      initials,
      // The local row's lifecycle state ("active", "suspended"): a platform
      // admin has no such row, hence the role subtitle in the branch below.
      subtitle: user.status ?? "",
      isPlatformOnly: false,
    }
  }

  const me = identity.data
  if (!me) {
    return {
      displayName: undefined,
      initials: undefined,
      subtitle: "",
      isPlatformOnly: noLocalProfile,
    }
  }

  const displayName =
    [me.firstName, me.lastName].filter(Boolean).join(" ").trim() ||
    me.displayName ||
    me.username ||
    me.email ||
    "Account"

  return {
    displayName,
    initials: initialsOf(displayName),
    // Their role is the useful subtitle here - "no organisation" is what the
    // Profile page explains, and the menu has one short line to work with.
    subtitle: me.platformRoles.includes("PLATFORM_ADMIN")
      ? "Platform administrator"
      : "",
    isPlatformOnly: true,
  }
}
