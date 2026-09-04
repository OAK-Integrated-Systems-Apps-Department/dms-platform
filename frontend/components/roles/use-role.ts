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
 * Roles reach the store two ways: already present, because the person
 * came from the Role List (which calls `fetchRoles()` on mount), or not
 * yet loaded — a direct link, a bookmark, or a page refresh landing
 * straight on `/admin/roles/{id}` before any list fetch has happened.
 * This hook covers the second case by fetching the one role individually
 * (`GET /api/v1/roles/{id}`) instead of requiring a full list reload.
 */
export function useRole(roleId: string): UseRoleResult {
  const role = useRolesStore((state) => state.roles.find((r) => r.id === roleId))
  const fetchRole = useRolesStore((state) => state.fetchRole)
  const [loading, setLoading] = useState(!role)
  const [notFound, setNotFound] = useState(false)

  useEffect(() => {
    if (role) {
      setLoading(false)
      return
    }

    let cancelled = false
    setLoading(true)
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
    // when navigating to a different role id, not on every store update
    // (which would refire the fetch as soon as the role dep changes from
    // undefined to defined).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roleId])

  return { role, loading, notFound }
}
