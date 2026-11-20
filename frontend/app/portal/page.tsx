"use client"

import { Suspense, useCallback, useState, useSyncExternalStore } from "react"
import { useSearchParams } from "next/navigation"

import { PortalBrowser } from "@/components/sharing/portal-browser"
import { PortalSignIn } from "@/components/sharing/portal-sign-in"
import { GUEST_TOKEN_KEY } from "@/lib/sharing/api"

function readToken(): string | null {
  try {
    return sessionStorage.getItem(GUEST_TOKEN_KEY)
  } catch {
    return null
  }
}

// sessionStorage changes in this tab are announced by us (below), so the store only needs a manual subscription.
const listeners = new Set<() => void>()
function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
function notify() {
  listeners.forEach((l) => l())
}

function PortalContent() {
  const params = useSearchParams()
  const tenantId = params.get("org") ?? undefined
  const stored = useSyncExternalStore(subscribe, readToken, () => null)
  const [memoryToken, setMemoryToken] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const token = stored ?? memoryToken

  const clear = useCallback(() => {
    try {
      sessionStorage.removeItem(GUEST_TOKEN_KEY)
    } catch {
      // nothing stored to remove
    }
    setMemoryToken(null)
    notify()
  }, [])

  const expired = useCallback(() => {
    setNotice("Your session ended. Please sign in again.")
    clear()
  }, [clear])

  if (token) {
    return <PortalBrowser token={token} onSignOut={clear} onExpired={expired} />
  }
  return (
    <PortalSignIn
      tenantId={tenantId}
      notice={notice}
      onSignedIn={(t) => {
        setMemoryToken(t)
        notify()
      }}
    />
  )
}

/** Client portal for guest accounts (SEA-FR-004): public route, guest token in sessionStorage only. */
export default function PortalPage() {
  return (
    <Suspense fallback={null}>
      <PortalContent />
    </Suspense>
  )
}
