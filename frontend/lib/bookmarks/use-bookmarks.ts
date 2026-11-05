"use client"

import { useMemo } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { friendlyMessage } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import {
  addBookmark,
  fetchBookmarks,
  fetchBookmarkStatus,
  removeBookmark,
  updateBookmarkNote,
  type BookmarkSortKey,
  type BookmarksPageDto,
  type SortDirection,
} from "@/lib/bookmarks/api"
import { useBookmarkStatusStore } from "@/stores/bookmark-status-store"

/** Every active query under this prefix is invalidated whenever a bookmark changes. */
const BOOKMARKS_PREFIX = ["bookmarks"] as const
const STATUS_KEY = (ids: string[]) => [...BOOKMARKS_PREFIX, "status", [...ids].sort().join(",")] as const
const LIST_KEY = (params: {
  page: number
  size: number
  sort: BookmarkSortKey
  dir: SortDirection
  q: string
}) => [...BOOKMARKS_PREFIX, "list", params] as const

/**
 * Which of `documentIds` the signed-in user has bookmarked. Called once per list-rendering component
 * (the document browser, search results, Shared with me) with all of that page's currently-visible ids,
 * not once per row - the `status` endpoint exists precisely so this stays one request per page instead
 * of one per row or a full reload of up to 500 bookmarks.
 *
 * Merges in this session's own optimistic overrides (`useBookmarkStatusStore`), so a toggle anywhere
 * else in the app is reflected here immediately, before this query's own refetch lands.
 */
export function useBookmarkStatus(documentIds: string[]) {
  const { hasPermission } = usePermissions()
  const canView = hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)
  const overrides = useBookmarkStatusStore((state) => state.overrides)
  const ids = useMemo(() => [...documentIds].sort(), [documentIds])

  const query = useQuery({
    queryKey: STATUS_KEY(ids),
    queryFn: () => fetchBookmarkStatus(ids),
    enabled: canView && ids.length > 0,
    staleTime: 15_000,
  })

  const bookmarked = useMemo(() => {
    const base = query.data ?? new Set<string>()
    const merged = new Set(base)
    for (const id of ids) {
      if (id in overrides) {
        if (overrides[id]) merged.add(id)
        else merged.delete(id)
      }
    }
    return merged
  }, [query.data, overrides, ids])

  return {
    isBookmarked: (documentId: string) => bookmarked.has(documentId),
    /** The merged set itself, for a consumer (e.g. `BrowserList`) that wants to pass it on in bulk. */
    bookmarkedIds: bookmarked,
    isLoading: query.isPending && canView,
  }
}

/**
 * Add/remove a bookmark from anywhere - the button, a row's menu, the Bookmarks page itself. Optimistic:
 * the status override flips immediately and every `useBookmarkStatus`/list query is invalidated once the
 * server confirms, so every instance converges on the same answer without each one polling.
 */
export function useBookmarkToggle() {
  const queryClient = useQueryClient()
  const setOverride = useBookmarkStatusStore((state) => state.setOverride)
  const clearOverride = useBookmarkStatusStore((state) => state.clearOverride)

  function invalidateAll() {
    void queryClient.invalidateQueries({ queryKey: BOOKMARKS_PREFIX })
  }

  const addMutation = useMutation({
    mutationFn: ({ documentId, note }: { documentId: string; note?: string }) =>
      addBookmark(documentId, note),
    onMutate: ({ documentId }) => setOverride(documentId, true),
    onSuccess: (result) => {
      if (result.created) toast.success("Added to your bookmarks")
      else toast.message("Already in your bookmarks")
    },
    onError: (error, { documentId }) => {
      clearOverride(documentId)
      toast.error(friendlyMessage(error, "Could not bookmark the document."))
    },
    onSettled: (_data, _error, { documentId }) => {
      clearOverride(documentId)
      invalidateAll()
    },
  })

  const removeMutation = useMutation({
    mutationFn: (documentId: string) => removeBookmark(documentId),
    onMutate: (documentId) => setOverride(documentId, false),
    onSuccess: () => toast.success("Removed from bookmarks"),
    onError: (error, documentId) => {
      clearOverride(documentId)
      toast.error(friendlyMessage(error, "Could not remove the bookmark."))
    },
    onSettled: (_data, _error, documentId) => {
      clearOverride(documentId)
      invalidateAll()
    },
  })

  return {
    add: (documentId: string, note?: string) => addMutation.mutateAsync({ documentId, note }),
    remove: (documentId: string) => removeMutation.mutateAsync(documentId),
    /** Bookmarks if not already bookmarked, otherwise removes - for a single-click toggle control. */
    toggle: (documentId: string, currentlyBookmarked: boolean) =>
      currentlyBookmarked ? removeMutation.mutateAsync(documentId) : addMutation.mutateAsync({ documentId }),
    isSaving: addMutation.isPending || removeMutation.isPending,
  }
}

/**
 * The My Bookmarks page: the caller's own available bookmarks, server-paged/sorted/searched (BD-FR-003).
 * Note edits go through the same invalidation as add/remove, so the status icons elsewhere pick up
 * nothing stale (a note isn't part of the status, but the list and its own cache must still refresh).
 */
export function useBookmarksList(params: {
  page: number
  size: number
  sort: BookmarkSortKey
  dir: SortDirection
  q: string
}) {
  const queryClient = useQueryClient()
  const queryKey = LIST_KEY(params)

  const query = useQuery({
    queryKey,
    queryFn: () => fetchBookmarks(params),
    placeholderData: (previous) => previous,
  })

  const noteMutation = useMutation({
    mutationFn: ({ documentId, note }: { documentId: string; note: string | null }) =>
      updateBookmarkNote(documentId, note),
    onMutate: async ({ documentId, note }) => {
      await queryClient.cancelQueries({ queryKey })
      const before = queryClient.getQueryData<BookmarksPageDto>(queryKey)
      if (before) {
        queryClient.setQueryData<BookmarksPageDto>(queryKey, {
          ...before,
          items: before.items.map((item) =>
            item.document.documentId === documentId ? { ...item, note } : item
          ),
        })
      }
      return { before }
    },
    onError: (error, _vars, context) => {
      if (context?.before) queryClient.setQueryData(queryKey, context.before)
      toast.error(friendlyMessage(error, "Could not save the note."))
    },
    onSettled: () => void queryClient.invalidateQueries({ queryKey: BOOKMARKS_PREFIX }),
  })

  const removeMutation = useMutation({
    mutationFn: (documentId: string) => removeBookmark(documentId),
    onSuccess: () => toast.success("Removed from bookmarks"),
    onError: (error) => toast.error(friendlyMessage(error, "Could not remove the bookmark.")),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: BOOKMARKS_PREFIX }),
  })

  return {
    page: query.data,
    isLoading: query.isPending,
    isError: query.isError,
    refetch: query.refetch,
    updateNote: (documentId: string, note: string | null) =>
      noteMutation.mutateAsync({ documentId, note }),
    remove: (documentId: string) => removeMutation.mutateAsync(documentId),
    isSaving: noteMutation.isPending || removeMutation.isPending,
  }
}
