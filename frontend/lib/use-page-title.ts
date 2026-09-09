"use client"

import { useEffect } from "react"

import { APP_NAME } from "@/lib/app-config"

/**
 * Sets `document.title` for the current page, as `"{title} — {APP_NAME}"`.
 * Needed because every page under `app/admin/...` is a Client Component
 * (they read from client-side data hooks like `useUser`/`useRole`), so
 * they can't export the server-only `metadata` object Next.js normally
 * uses for `<title>`.
 *
 *
 * Restores whatever title was set before this component mounted when it
 * unmounts, so navigating away doesn't leave a stale title behind for
 * whatever renders next before its own `usePageTitle` call fires.
 */
export function usePageTitle(title: string) {
  useEffect(() => {
    const previous = document.title
    document.title = `${title} - ${APP_NAME}`
    return () => {
      document.title = previous
    }
  }, [title])
}

