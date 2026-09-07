"use client"

import { useEffect, useState } from "react"

import { usePermissionsStore } from "@/stores/permissions-store"

import type { PermissionRecord } from "./types"

interface UsePermissionResult {
  permission: PermissionRecord | undefined
  loading: boolean
  notFound: boolean
}

/**
 * Resolves a single permission by id for View Permission and Edit
 * Permission — same reasoning as `components/roles/use-role.ts`: a direct
 * link, bookmark, or refresh can land here before Permission List's
 * `fetchPermissions()` has ever populated the store, so this fetches the
 * one permission individually (`GET /api/v1/permissions/{id}`) in that
 * case instead of requiring a full list reload first.
 */
export function usePermission(permissionId: string): UsePermissionResult {
  const permission = usePermissionsStore((state) =>
    state.permissions.find((p) => p.id === permissionId)
  )
  const fetchPermission = usePermissionsStore((state) => state.fetchPermission)
  const [loading, setLoading] = useState(!permission)
  const [notFound, setNotFound] = useState(false)

  useEffect(() => {
    if (permission) {
      setLoading(false)
      return
    }

    let cancelled = false
    setLoading(true)
    setNotFound(false)

    fetchPermission(permissionId).then((result) => {
      if (cancelled) return
      setLoading(false)
      if (!result) setNotFound(true)
    })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [permissionId])

  return { permission, loading, notFound }
}
