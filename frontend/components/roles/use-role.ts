"use client"

import { useEffect, useState } from "react"

import { useRolesStore } from "@/stores/roles-store"

import type { RoleRecord } from "./types"

interface UseRoleResult {
  role: RoleRecord | undefined
  loading: boolean
  /** True only for a genuine 404 — the role doesn't exist / isn't accessible. */
  notFound: boolean
  /**
   * Non-null when the fetch failed for a reason *other* than "this role
   * doesn't exist" — most notably a network failure (backend unreachable).
   * Distinguishing this from `notFound` is the whole point: a caller
   * should never call Next's `notFound()` for this case, since the role
   * may well exist — its status is just unknown right now. Render the
   * shared `BackendUnavailable` component instead.
   */
  error: string | null
  /** Re-runs the fetch — wire to `BackendUnavailable`'s `onRetry`. */
  retry: () => void
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
 * Now this always re-fetches on mount / roleId change. A second bug in
 * that same fix (also since corrected — see `loading`'s own comment
 * below) let `loading` go straight to `false` whenever a cached copy
 * existed, so callers rendered against that stale, permission-less copy
 * instead of waiting for the fetch above to resolve. `loading` is now the
 * single source of truth for "is `role` current enough to build a form
 * from" — every consumer should gate on it rather than on `role` being
 * non-null.
 */
export function useRole(roleId: string): UseRoleResult {
  const role = useRolesStore((state) =>
    state.roles.find((r) => r.id === roleId)
  )
  const fetchRole = useRolesStore((state) => state.fetchRole)
  // Always starts true and is cleared only once fetchRole (which also
  // resolves this role's real permissions) has completed for the CURRENT
  // mount — never inferred from whether a role object already exists in
  // the store. A role visited via Role List is cached there already, but
  // list-fetched rows never carry permissions (fetchRoles() intentionally
  // omits them — see fromBackend's doc comment). Treating "role object
  // exists" as "fully loaded" let RoleForm/RolePermissionConfig seed their
  // editable permission state from that stale, permission-less copy
  // before this fetch ever resolved — silently discarding every real
  // grant the next time someone saved. See role-form.tsx /
  // role-permission-config.tsx for the consuming side of this fix.
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Bumped by `retry()` to re-run the effect below without changing roleId.
  const [retryToken, setRetryToken] = useState(0)

  function resetFetchState() {
    setLoading(true)
    setNotFound(false)
    setError(null)
  }

  // Navigating to a different role resets during render (against the
  // last-seen id) rather than in the effect below, which only fetches.
  const [prevRoleId, setPrevRoleId] = useState(roleId)
  if (roleId !== prevRoleId) {
    setPrevRoleId(roleId)
    resetFetchState()
  }

  useEffect(() => {
    let cancelled = false

    fetchRole(roleId).then((result) => {
      if (cancelled) return
      setLoading(false)
      setNotFound(result.notFound)
      setError(result.error)
    })

    return () => {
      cancelled = true
    }
    // Deliberately excludes `role`/`fetchRole` — this should only re-run
    // when navigating to a different role id (or retry() is called), not
    // on every store update.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roleId, retryToken])

  function retry() {
    resetFetchState()
    setRetryToken((token) => token + 1)
  }

  return { role, loading, notFound, error, retry }
}
