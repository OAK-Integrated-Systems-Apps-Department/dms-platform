"use client"

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { friendlyMessage } from "@/lib/api-error"

import {
  checkInDocument,
  checkOutDocument,
  discardCheckout,
  fetchCheckoutStatus,
  fetchEditingConfig,
  fetchEditingSettings,
  fetchUserPreferences,
  forceReleaseCheckout,
  liftLegalHold,
  placeLegalHold,
  saveEditingSettings,
  saveUserPreferences,
} from "./api"

const key = (documentId: string) => ["document-checkout", documentId] as const

/** How often an open document page re-asks for its lock state. CICO-NFR-001 wants changes visible within 1-2 s. */
export const CHECKOUT_POLL_MS = 2_000

/**
 * A document's lock state. Polled (not pushed) every {@link CHECKOUT_POLL_MS} so a check-out or check-in by
 * someone else shows up without a reload. react-query pauses it while the tab is hidden (refetchIntervalInBackground
 * is off), so an idle background tab costs the server nothing. One cheap indexed read per tick.
 */
export function useCheckoutStatus(documentId: string | null) {
  return useQuery({
    queryKey: key(documentId ?? ""),
    queryFn: () => fetchCheckoutStatus(documentId as string),
    enabled: Boolean(documentId),
    refetchInterval: CHECKOUT_POLL_MS,
    refetchIntervalInBackground: false,
  })
}

/** The check-out actions. Each refreshes the lock status, the version list and the document itself. */
export function useCheckoutActions(documentId: string) {
  const queryClient = useQueryClient()
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: key(documentId) })
    void queryClient.invalidateQueries({ queryKey: ["document", documentId] })
    void queryClient.invalidateQueries({ queryKey: ["documents", "versions", documentId] })
    // The document list's lock icon reads the same state.
    void queryClient.invalidateQueries({ queryKey: ["documents"] })
  }

  const checkOut = useMutation({
    mutationFn: () => checkOutDocument(documentId),
    onSuccess: () => {
      refresh()
      toast.success("Checked out. Nobody else can change it until you check it in.")
    },
    // A lost race or a frozen document: the 409 message already says who or why. Refresh so the panel shows it too.
    onError: (error) => {
      refresh()
      toast.error(friendlyMessage(error, "Could not check this document out."))
    },
  })

  const checkIn = useMutation({
    mutationFn: ({ file, comment }: { file: File; comment?: string }) => checkInDocument(documentId, file, comment),
    onSuccess: () => {
      refresh()
      toast.success("Checked in. Your file is being scanned and becomes the new version when it is clean.")
    },
    onError: (error) => {
      refresh()
      toast.error(friendlyMessage(error, "Could not check this document in."))
    },
  })

  const discard = useMutation({
    mutationFn: () => discardCheckout(documentId),
    onSuccess: () => {
      refresh()
      toast.success("Check-out discarded.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not discard the check-out.")),
  })

  const forceRelease = useMutation({
    mutationFn: (reason: string) => forceReleaseCheckout(documentId, reason),
    onSuccess: () => {
      refresh()
      toast.success("Lock released.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not release the lock.")),
  })

  const hold = useMutation({
    mutationFn: ({ place, reason }: { place: boolean; reason: string }) =>
      place ? placeLegalHold(documentId, reason) : liftLegalHold(documentId, reason),
    onSuccess: (_, { place }) => {
      refresh()
      toast.success(place ? "Legal hold placed." : "Legal hold lifted.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not change the legal hold.")),
  })

  return { checkOut, checkIn, discard, forceRelease, hold }
}

/** Whether online editing is on, in which mode, and for which file types. Cached for a minute - it rarely changes. */
export function useEditingConfig() {
  return useQuery({
    queryKey: ["editing-config"],
    queryFn: fetchEditingConfig,
    staleTime: 60_000,
    retry: false,
  })
}

const SETTINGS_KEY = ["editing-settings"] as const

/** This organisation's online-editing setting, for the admin page. */
export function useEditingSettings() {
  return useQuery({ queryKey: SETTINGS_KEY, queryFn: fetchEditingSettings, retry: false })
}

/** Saves it, then refreshes everything that reads it - including whether "Edit online" is offered on documents. */
export function useSaveEditingSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: saveEditingSettings,
    onSuccess: (saved) => {
      queryClient.setQueryData(SETTINGS_KEY, saved)
      void queryClient.invalidateQueries({ queryKey: ["editing-config"] })
      toast.success(saved.enabled ? "Online editing is on for your organisation." : "Online editing is off for your organisation.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not save the online-editing setting.")),
  })
}

const PREFERENCES_KEY = ["user-preferences"] as const

/** The signed-in user's own interface preferences (where the editor opens). */
export function useUserPreferences() {
  return useQuery({ queryKey: PREFERENCES_KEY, queryFn: fetchUserPreferences, staleTime: 60_000, retry: false })
}

/** Saves the choice and puts the saved value straight into the cache so the next page already follows it. */
export function useSaveUserPreferences() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: saveUserPreferences,
    onSuccess: (saved) => {
      queryClient.setQueryData(PREFERENCES_KEY, saved)
      toast.success("Preference saved.")
    },
    onError: (error) => toast.error(friendlyMessage(error, "Could not save your preference.")),
  })
}
