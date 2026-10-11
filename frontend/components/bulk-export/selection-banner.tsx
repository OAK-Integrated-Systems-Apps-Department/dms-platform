interface SelectionBannerProps {
  pageCount: number
  totalMatching: number
  allOnPageSelected: boolean
  selectingAllMatching: boolean
  onSelectAllMatching: () => void
  onClear: () => void
}

const LINK = "font-semibold text-primary underline-offset-2 hover:underline"

/**
 * Gmail-style escalation from "this page" to "everything matching": once
 * the whole page is ticked and more match, offer to select by filter
 * instead - the export is then defined by the filters, not by ids.
 */
export function SelectionBanner({
  pageCount,
  totalMatching,
  allOnPageSelected,
  selectingAllMatching,
  onSelectAllMatching,
  onClear,
}: SelectionBannerProps) {
  if (!selectingAllMatching && !(allOnPageSelected && totalMatching > pageCount)) return null

  return (
    <div
      role="status"
      className="mb-3 flex flex-wrap items-center justify-center gap-x-2 gap-y-1 rounded-btn border border-primary/30 bg-primary/5 px-3.5 py-2.5 text-[13px] text-foreground"
    >
      {selectingAllMatching ? (
        <>
          <span>All {totalMatching.toLocaleString()} matching documents are selected.</span>
          <button type="button" onClick={onClear} className={LINK}>
            Clear selection
          </button>
        </>
      ) : (
        <>
          <span>All {pageCount.toLocaleString()} on this page are selected.</span>
          <button type="button" onClick={onSelectAllMatching} className={LINK}>
            Select all {totalMatching.toLocaleString()} matching
          </button>
        </>
      )}
    </div>
  )
}
