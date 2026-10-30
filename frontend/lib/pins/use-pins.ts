"use client"

import { useMemo } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import type { DocumentListItemDto } from "@/lib/documents/api"
import {
  fetchPins,
  pinDocument,
  unpinDocument,
  type PinAudienceInput,
  type PinnedDocumentDto,
  type PinsDto,
} from "@/lib/pins/api"
import { usePinAudienceStore } from "@/stores/pin-audience-store"

export const PINS_KEY = ["pins"] as const

/**
 * What can be pinned: a document list row, or - where only the id and title are known, such as the
 * document page - just those two.
 */
export type PinTarget = Pick<DocumentListItemDto, "documentId" | "title"> &
  Partial<DocumentListItemDto>

/**
 * The pins the signed-in user can see, shared by every place that shows one: the Pinned documents
 * page, the document list, the preview.
 *
 * A pin has an audience. The list holds the pins this person placed (`mine`) and the ones placed for
 * them by someone else - the second kind can't be changed or removed from here, only by whoever pinned
 * it. One query serves every screen, so "is this pinned?" is a set lookup, never a request per row.
 *
 * Pinning goes through the audience sheet (`openPin`), which calls `pin` once the person has chosen who
 * sees it. Unpinning updates the list straight away and puts it back if the server refuses
 * (PID-NFR-001: sub-second in the interface), then re-reads it either way.
 */
export function usePins() {
  const queryClient = useQueryClient()
  const openSheet = usePinAudienceStore((state) => state.open)
  const { hasPermission } = usePermissions()
  const canView = hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)
  const canPin = hasPermission(PermissionCodes.PID_PIN)
  const canUnpin = hasPermission(PermissionCodes.PID_UNPIN)

  const query = useQuery({
    queryKey: PINS_KEY,
    queryFn: fetchPins,
    enabled: canView,
    staleTime: 30_000,
  })

  const pins = useMemo(() => query.data?.pins ?? [], [query.data])
  const byDocument = useMemo(
    () => new Map(pins.map((p) => [p.document.documentId, p])),
    [pins]
  )
  const mine = useMemo(() => pins.filter((p) => p.mine), [pins])
  const limit = query.data?.limit ?? null

  const pinMutation = useMutation({
    mutationFn: ({
      row,
      audience,
    }: {
      row: PinTarget
      audience: PinAudienceInput
    }) => pinDocument(row.documentId, audience),
    // No toast: pinning only happens in the audience sheet, which shows the server's own words
    // (the limit, an audience that can't work) beside the form. A toast on top would repeat them.
    onSettled: () => void queryClient.invalidateQueries({ queryKey: PINS_KEY }),
  })

  const unpinMutation = useMutation({
    mutationFn: (row: PinTarget) => unpinDocument(row.documentId),
    onMutate: async (row) => {
      await queryClient.cancelQueries({ queryKey: PINS_KEY })
      const before = queryClient.getQueryData<PinsDto>(PINS_KEY)
      if (before) {
        queryClient.setQueryData<PinsDto>(PINS_KEY, {
          ...before,
          // Only the caller's own pin goes; someone else's stays, as it does on the server.
          pins: before.pins.filter(
            (p) => !(p.document.documentId === row.documentId && p.mine)
          ),
        })
      }
      return { before }
    },
    onError: (error, _row, context) => {
      if (context?.before) queryClient.setQueryData(PINS_KEY, context.before)
      toast.error(friendlyMessage(error, "Could not unpin the document."))
    },
    onSettled: () => void queryClient.invalidateQueries({ queryKey: PINS_KEY }),
  })

  const pinOf = (documentId: string): PinnedDocumentDto | undefined =>
    byDocument.get(documentId)

  return {
    /** Every pin the caller can see - theirs and other people's. */
    pins,
    /** Only the ones the caller placed. */
    mine,
    limit,
    /** How many documents the caller has pinned (what the limit counts). */
    count: mine.length,
    atLimit: limit !== null && mine.length >= limit,
    isLoading: query.isPending && canView,
    isError: query.isError,
    refetch: query.refetch,
    pinOf,
    /** Is it pinned for the caller, by anyone? */
    isPinned: (documentId: string) => byDocument.has(documentId),
    /** Did the caller pin it themselves? Only then can they change or remove it. */
    isMine: (documentId: string) => byDocument.get(documentId)?.mine === true,
    /** May the caller pin this one, or - if it is theirs - change or unpin it? */
    canToggle: (documentId: string) =>
      canPin || (byDocument.get(documentId)?.mine === true && canUnpin),
    canPin,
    canUnpin,
    /** Opens the audience sheet: choose who sees the pin, or change it, or unpin. */
    openPin: (row: PinTarget) => openSheet(row),
    pin: (row: PinTarget, audience: PinAudienceInput) =>
      pinMutation.mutateAsync({ row, audience }),
    unpin: (row: PinTarget) => unpinMutation.mutateAsync(row),
    isSaving: pinMutation.isPending || unpinMutation.isPending,
  }
}
