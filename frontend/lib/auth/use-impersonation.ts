"use client"

import { useRouter } from "next/navigation"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { API_BASE_URL } from "@/lib/api-config"
import { apiFetch } from "@/lib/api-fetch"

async function startImpersonationRequest(targetUserId: string): Promise<void> {
  await apiFetch(
    `${API_BASE_URL}/api/v1/impersonation/start`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ targetUserId }),
    },
    "Could not start impersonating this user."
  )
}

async function stopImpersonationRequest(): Promise<void> {
  await apiFetch(
    `${API_BASE_URL}/api/v1/impersonation/stop`,
    { method: "POST" },
    "Could not end impersonation."
  )
}

/**
 * Start/stop for the "Act as User" flow (`UserImpersonateDialog` /
 * `ImpersonationBanner`). Both mutations wipe the ENTIRE React Query cache
 * on success, not just `["current-user"]` - the same reasoning as
 * `providers.tsx`'s session-expiry handler's `client.clear()`: virtually
 * everything on screen (documents, permissions, roles) is scoped to "who am
 * I", and that identity just changed underneath every one of those cached
 * queries. Redirecting to `/` afterward, same as login/logout, so the next
 * thing rendered is a fresh dashboard for whichever identity is now active
 * rather than whatever admin-only page the caller happened to be on.
 */
export function useImpersonation() {
  const router = useRouter()
  const queryClient = useQueryClient()

  function onIdentitySwitched() {
    queryClient.clear()
    router.push("/")
  }

  const startMutation = useMutation({
    mutationFn: (targetUserId: string) => startImpersonationRequest(targetUserId),
    onSuccess: () => {
      onIdentitySwitched()
      toast.success("You are now viewing as this user.")
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : "Could not start impersonating this user.")
    },
  })

  const stopMutation = useMutation({
    mutationFn: () => stopImpersonationRequest(),
    onSuccess: () => {
      onIdentitySwitched()
      toast.success("Returned to your own account.")
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : "Could not end impersonation.")
    },
  })

  return {
    startImpersonation: (targetUserId: string) => startMutation.mutate(targetUserId),
    stopImpersonation: () => stopMutation.mutate(),
    isStarting: startMutation.isPending,
    isStopping: stopMutation.isPending,
  }
}
