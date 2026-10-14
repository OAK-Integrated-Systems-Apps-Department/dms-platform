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
  type PinsDto,
} from "@/lib/pins/api"

export const PINS_KEY = ["pins"] as const

/**
 * What can be pinned: a document list row, or - where only the id and title are known, such as the
 * document page - just those two. A full row lets the pinned list update at once; without one it
 * waits for the server's answer.
 */
export type PinTarget = Pick<DocumentListItemDto, "documentId" | "title"> &
  Partial<DocumentListItemDto>

/**
 * The signed-in user's pinned documents, shared by every place that shows a
 * pin: the dashboard section, the document list, the preview.
 *
 * One query serves all of them - a person's pins are bounded by the limit, so
 * "is this document pinned?" is a set lookup, never a request per row. Pinning
 * and unpinning update that list straight away and put it back if the server
 * refuses (PID-NFR-001: sub-second in the interface), then re-read it either way.
 */
export function usePins() {
  const queryClient = useQueryClient()
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
  const pinnedIds = useMemo(
    () => new Set(pins.map((p) => p.document.documentId)),
    [pins]
  )
  const limit = query.data?.limit ?? null

  const mutation = useMutation({
    mutationFn: ({ row, pin }: { row: PinTarget; pin: boolean }) =>
      pin ? pinDocument(row.documentId) : unpinDocument(row.documentId),
    onMutate: async ({ row, pin }) => {
      await queryClient.cancelQueries({ queryKey: PINS_KEY })
      const before = queryClient.getQueryData<PinsDto>(PINS_KEY)
      if (before) {
        const full = row.documentTypeId !== undefined
        const without = before.pins.filter(
          (p) => p.document.documentId !== row.documentId
        )
        queryClient.setQueryData<PinsDto>(PINS_KEY, {
          ...before,
          pins:
            pin && full
              ? [
                  {
                    document: row as DocumentListItemDto,
                    pinnedAt: new Date().toISOString(),
                    archived: false,
                  },
                  ...without,
                ]
              : without,
        })
      }
      return { before }
    },
    onError: (error, { pin }, context) => {
      if (context?.before) queryClient.setQueryData(PINS_KEY, context.before)
      // The server's own words: at the limit it says how many you may pin.
      toast.error(
        friendlyMessage(
          error,
          pin ? "Could not pin the document." : "Could not unpin the document."
        )
      )
    },
    onSettled: () => void queryClient.invalidateQueries({ queryKey: PINS_KEY }),
  })

  const isPinned = (documentId: string) => pinnedIds.has(documentId)

  return {
    pins,
    limit,
    count: pins.length,
    atLimit: limit !== null && pins.length >= limit,
    isLoading: query.isPending && canView,
    isError: query.isError,
    refetch: query.refetch,
    isPinned,
    /** May the caller pin this one, or - if it is pinned - unpin it? */
    canToggle: (documentId: string) =>
      isPinned(documentId) ? canUnpin : canPin,
    canPin,
    canUnpin,
    toggle: (row: PinTarget) =>
      mutation.mutateAsync({ row, pin: !isPinned(row.documentId) }).catch(() => {
        // Already reported by onError.
      }),
  }
}
