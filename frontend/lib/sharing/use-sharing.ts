"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import {
  clearLinkReview,
  createGuestGrant,
  createLink,
  deleteGuest,
  deleteGuestGrant,
  fetchAllLinks,
  fetchEligibility,
  fetchGuestGrants,
  fetchGuests,
  fetchLinks,
  fetchShareEvents,
  fetchSharingSettings,
  inviteGuest,
  revokeLink,
  saveSharingSettings,
  setConfidential,
  setGuestStatus,
  updateGuestGrant,
  type CreateGrantRequest,
  type CreateLinkRequest,
  type LinkListFilter,
  type ShareAccess,
  type ShareTargetType,
  type SharingSettings,
} from "@/lib/sharing/api"

export const SHARING_KEY = ["sharing"] as const

export function useSharingSettings() {
  return useQuery({
    queryKey: [...SHARING_KEY, "settings"],
    queryFn: fetchSharingSettings,
    staleTime: 60_000,
  })
}

export function useSaveSharingSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (settings: SharingSettings) => saveSharingSettings(settings),
    onSuccess: () => qc.invalidateQueries({ queryKey: SHARING_KEY }),
  })
}

export function useEligibility(type: ShareTargetType, id: string, enabled = true) {
  return useQuery({
    queryKey: [...SHARING_KEY, "eligibility", type, id],
    queryFn: () => fetchEligibility(type, id),
    enabled,
  })
}

export function useLinks(type: ShareTargetType, id: string, enabled = true) {
  return useQuery({
    queryKey: [...SHARING_KEY, "links", type, id],
    queryFn: () => fetchLinks(type, id),
    enabled,
  })
}

export function useAllLinks(
  status: LinkListFilter | undefined,
  page: number,
  size: number
) {
  return useQuery({
    queryKey: [...SHARING_KEY, "all-links", status ?? "all", page, size],
    queryFn: () => fetchAllLinks({ status, page, size }),
  })
}

export function useGuests(enabled = true) {
  return useQuery({
    queryKey: [...SHARING_KEY, "guests"],
    queryFn: fetchGuests,
    enabled,
  })
}

export function useGuestGrants(type: ShareTargetType, id: string, enabled = true) {
  return useQuery({
    queryKey: [...SHARING_KEY, "grants", type, id],
    queryFn: () => fetchGuestGrants(type, id),
    enabled,
  })
}

export function useShareEvents(
  type: ShareTargetType,
  id: string,
  page: number,
  enabled = true
) {
  return useQuery({
    queryKey: [...SHARING_KEY, "events", type, id, page],
    queryFn: () =>
      fetchShareEvents({ targetType: type, targetId: id, page, size: 20 }),
    enabled,
  })
}

/** Every mutation here refreshes everything under ["sharing"]: lists, counts and the activity feed all move together. */
function useSharingMutation<TArgs, TResult>(fn: (args: TArgs) => Promise<TResult>) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () => qc.invalidateQueries({ queryKey: SHARING_KEY }),
  })
}

export const useCreateLink = () =>
  useSharingMutation((r: CreateLinkRequest) => createLink(r))
export const useRevokeLink = () =>
  useSharingMutation((id: string) => revokeLink(id))
export const useClearReview = () =>
  useSharingMutation((id: string) => clearLinkReview(id))
export const useCreateGrant = () =>
  useSharingMutation((r: CreateGrantRequest) => createGuestGrant(r))
export const useUpdateGrant = () =>
  useSharingMutation(
    (a: { id: string; access?: ShareAccess; expiresAt?: string | null }) =>
      updateGuestGrant(a.id, { access: a.access, expiresAt: a.expiresAt })
  )
export const useDeleteGrant = () =>
  useSharingMutation((id: string) => deleteGuestGrant(id))
export const useInviteGuest = () =>
  useSharingMutation((a: { email: string; displayName?: string }) =>
    inviteGuest(a.email, a.displayName)
  )
export const useSetGuestStatus = () =>
  useSharingMutation((a: { id: string; status: "active" | "disabled" }) =>
    setGuestStatus(a.id, a.status)
  )
export const useDeleteGuest = () =>
  useSharingMutation((id: string) => deleteGuest(id))
export const useSetConfidential = () =>
  useSharingMutation(
    (a: {
      type: ShareTargetType
      id: string
      confidential: boolean
      reason?: string
    }) => setConfidential(a.type, a.id, a.confidential, a.reason)
  )
