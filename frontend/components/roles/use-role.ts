"use client"

import { useEffect, useState } from "react"

import { useRolesStore } from "@/stores/roles-store"

import type { RoleRecord } from "./types"

interface UseRoleResult {
  role: RoleRecord | undefined
  loading: boolean
  notFound: boolean
}

/**
 * Resolves a single role by id for Role Details, Edit Role, and
 * Permission Configuration.
 *
 * FIXED BUG: this used to skip fetching entirely whenever a role object
 * already existed in the store — which happens for every role after a
 * single visit to Role List, since that screen's `fetchRoles()` caches
 * every role right away. The problem: `fetchRoles()` deliberately never
 * populates `permissions` (see `stores/roles-store.ts`'s `fromBackend`
 * doc comment — doing so would mean an N+1 fetch across every role just
 * to render a list that doesn't even show permissions). Only the
 * single-role fetch below (`fetchRole` → `fetchRolePermissions`)
 * populates real data. So the old "already cached, skip fetching" check
 * left `permissions` stuck at its list-fetch placeholder (`[]`)
 * *forever*, on every role, the moment it had ever appeared in the list
 * — Role Details would show "No permissions granted yet." regardless of
 * what was actually granted in the database.
 *
 * Now this always re-fetches on mount / roleId change, using the
 * already-cached copy (if any) only to avoid a loading-spinner flash —
 * `role` still renders immediately from whatever's cached, and swaps to
 * the real, complete data as soon as the fetch resolves (zustand
 * re-renders the component automatically once the store updates).
 */
export function useRole(roleId: string): UseRoleResult {
  const role = useRolesStore((state) => state.roles.find((r) => r.id === roleId))
  const fetchRole = useRolesStore((state) => state.fetchRole)
  const [loading, setLoading] = useState(!role)
  const [notFound, setNotFound] = useState(false)

  useEffect(() => {
    let cancelled = false
    // Only show the spinner when there's nothing to render meanwhile —
    // if a cached (possibly partial) copy exists, keep it on screen
    // while this fetch refreshes it in the background instead of
    // blanking the page.
    if (!role) setLoading(true)
    setNotFound(false)

    fetchRole(roleId).then((result) => {
      if (cancelled) return
      setLoading(false)
      if (!result) setNotFound(true)
    })

    return () => {
      cancelled = true
    }
    // Deliberately excludes `role`/`fetchRole` — this should only re-run
    // when navigating to a different role id, not on every store update.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roleId])

  return { role, loading, notFound }
}
