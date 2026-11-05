import { create } from "zustand"

/**
 * Optimistic overrides for "is this document bookmarked?", shared by every instance of
 * `useBookmarkStatus` across the app. Toggling a bookmark sets an override here immediately (before
 * the server round-trip resolves), so a list page and the document's own detail page stay in sync the
 * instant either one toggles - the same role `usePinAudienceStore` plays for pins, but for a value
 * rather than which sheet is open.
 *
 * Cleared once the underlying React Query caches are invalidated and refetched (see
 * `useBookmarkToggle`), so a stale override can never outlive the real answer for more than one
 * refetch.
 */
interface BookmarkStatusState {
  overrides: Record<string, boolean>
  setOverride: (documentId: string, bookmarked: boolean) => void
  clearOverride: (documentId: string) => void
}

export const useBookmarkStatusStore = create<BookmarkStatusState>((set) => ({
  overrides: {},
  setOverride: (documentId, bookmarked) =>
    set((state) => ({
      overrides: { ...state.overrides, [documentId]: bookmarked },
    })),
  clearOverride: (documentId) =>
    set((state) => {
      if (!(documentId in state.overrides)) return state
      const overrides = { ...state.overrides }
      delete overrides[documentId]
      return { overrides }
    }),
}))
