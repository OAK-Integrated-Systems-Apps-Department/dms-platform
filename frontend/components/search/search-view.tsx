"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import { usePathname, useRouter, useSearchParams } from "next/navigation"
import { ArrowDown, ArrowUp, Info, SlidersHorizontal } from "lucide-react"

import { Button } from "@/components/ui/button"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useFolders } from "@/lib/folders/use-folders"
import {
  activeFilterCount,
  EMPTY_FILTERS,
  filtersFromParams,
  paramsFromFilters,
  searchSignature,
} from "@/lib/search/params"
import { useSearch, useSearchFilterOptions } from "@/lib/search/use-search"
import type { SearchFilters, SearchNoticeDto, SearchSort } from "@/lib/search/types"

import { SearchFilterPanel } from "./search-filters"
import { SearchResultCard } from "./search-results"

/** How long typing has to pause before the URL (and so the search) updates. */
const DEBOUNCE_MS = 350

const SORT_LABELS: Record<SearchSort, string> = {
  relevance: "Best match",
  title: "Title",
  created: "Date created",
  modified: "Last modified",
}

/** What to try when nothing matched: concrete, not a shrug. */
const NO_RESULT_TIPS = [
  "Check the spelling, or try fewer or different words.",
  "Remove a filter you don’t need.",
  "Take out the quotation marks to match the words anywhere, not as an exact phrase.",
  "Search for the start of a word: “contr” finds “contract”.",
]

function noticeText(n: SearchNoticeDto): string {
  switch (n.code) {
    case "FILTER_NO_LONGER_EXISTS":
      return `Some filters refer to something that no longer exists, so they were not applied (${n.details.join(", ")}). The rest of your criteria still apply.`
    case "FILTERS_NOT_APPLIED":
      return `These could not be applied in this mode: ${n.details.join(", ")}.`
    default:
      return n.message
  }
}

/**
 * The search page: a keyword box, a filter panel, ranked results with highlighted snippets, sorting and paging. All state
 * lives in the URL (so a search can be bookmarked or shared), and results only ever contain documents you may open.
 */
export function SearchView() {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const { hasPermission } = usePermissions()
  const canViewArchive = hasPermission(PermissionCodes.CRM_VIEW_ARCHIVE)

  const filters = useMemo(
    () => filtersFromParams(new URLSearchParams(searchParams.toString())),
    [searchParams]
  )
  const signature = searchSignature(filters)

  // Paging: the cursors visited so far. A new search (any criterion changed) starts again at the first page.
  const [cursors, setCursors] = useState<(string | null)[]>([null])
  const [seenSignature, setSeenSignature] = useState(signature)
  if (signature !== seenSignature) {
    setSeenSignature(signature)
    setCursors([null])
  }
  const cursor = cursors[cursors.length - 1]

  const results = useSearch(filters, cursor)
  const filterOptions = useSearchFilterOptions()
  const folders = useFolders()
  const [showFilters, setShowFilters] = useState(activeFilterCount(filters) > 0)

  // The keyword box is local while typing and written to the URL after a pause.
  const [text, setText] = useState(filters.q)
  const [seenQ, setSeenQ] = useState(filters.q)
  if (filters.q !== seenQ) {
    // The URL changed under us (a "Did you mean" click, back/forward, "See all results"): show it in the box.
    setSeenQ(filters.q)
    setText(filters.q)
  }
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    []
  )

  function apply(next: SearchFilters) {
    const qs = paramsFromFilters(next).toString()
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false })
  }

  function typing(value: string) {
    setText(value)
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => apply({ ...filters, q: value }), DEBOUNCE_MS)
  }

  const data = results.data
  const items = data?.items ?? []
  const filterCount = activeFilterCount(filters)
  const hasCriteria = filters.q.trim() !== "" || filterCount > 0
  const settled = !results.isPlaceholderData && !results.isFetching
  const notices = data?.notices ?? []

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput
          className="min-w-[260px] flex-1"
          value={text}
          onChange={typing}
          placeholder="Search by keyword, phrase, file name, details or the words inside documents…"
          aria-label="Search documents"
          inputProps={{
            onKeyDown: (e) => {
              if (e.key === "Enter") {
                if (timer.current) clearTimeout(timer.current)
                apply({ ...filters, q: text })
              }
            },
          }}
        />
        <Button
          type="button"
          variant="outline"
          aria-expanded={showFilters}
          onClick={() => setShowFilters((v) => !v)}
        >
          <SlidersHorizontal size={16} />
          Filters{filterCount > 0 ? ` (${filterCount})` : ""}
        </Button>
        <div className="flex items-center gap-1">
          <select
            aria-label="Sort by"
            value={filters.sort}
            onChange={(e) => apply({ ...filters, sort: e.target.value as SearchSort })}
            className="h-10 rounded-btn border border-input bg-background px-2 text-sm"
          >
            {(Object.keys(SORT_LABELS) as SearchSort[]).map((s) => (
              <option key={s} value={s}>
                {SORT_LABELS[s]}
              </option>
            ))}
          </select>
          <Button
            type="button"
            variant="outline"
            size="icon"
            aria-label={filters.dir === "asc" ? "Sorted ascending: switch to descending" : "Sorted descending: switch to ascending"}
            onClick={() => apply({ ...filters, dir: filters.dir === "asc" ? "desc" : "asc" })}
          >
            {filters.dir === "asc" ? <ArrowUp size={16} /> : <ArrowDown size={16} />}
          </Button>
        </div>
      </div>

      {showFilters && (
        <>
          <SearchFilterPanel
            filters={filters}
            onChange={apply}
            options={filterOptions.data}
            folders={folders.flat}
            canViewArchive={canViewArchive}
          />
          {filterCount > 0 && (
            <div>
              <Button type="button" variant="ghost" size="xs" onClick={() => apply({ ...EMPTY_FILTERS, q: filters.q })}>
                Clear all filters
              </Button>
            </div>
          )}
        </>
      )}

      {data?.mode === "basic" && (
        <Banner tone="info">
          Showing a basic title search because full search isn&rsquo;t available right now. Words inside documents aren&rsquo;t searched
          until it is back.
        </Banner>
      )}
      {notices
        .filter((n) => n.code !== "NO_RESULTS" && n.code !== "FALLBACK")
        .map((n) => (
          <Banner key={n.code} tone={n.code === "REFINE" ? "warn" : "info"}>
            {noticeText(n)}
          </Banner>
        ))}

      {results.isError && !data && (
        <LoadFailed error={results.error} fallback="Could not search just now." onRetry={() => results.refetch()} />
      )}
      {results.isPending && <LoadingState message="Searching…" />}

      {data && (
        <>
          <p className="text-[13px] text-muted-foreground" aria-live="polite">
            {items.length === 0
              ? ""
              : data.total.exact
                ? `${data.total.value} ${data.total.value === 1 ? "result" : "results"}`
                : `${data.total.value} or more results`}
            {cursors.length > 1 ? ` · page ${cursors.length}` : ""}
          </p>

          {items.length === 0 && settled ? (
            <div className="rounded-lg border border-border bg-card px-6 py-12 text-center shadow-[var(--ledger-shadow)]">
              <p className="text-[15px] font-semibold">No results found{hasCriteria ? "" : " yet"}</p>
              {data.didYouMean && (
                <p className="mt-2 text-[13.5px]">
                  Did you mean{" "}
                  <button
                    type="button"
                    className="font-semibold text-primary hover:underline"
                    onClick={() => apply({ ...filters, q: data.didYouMean ?? "" })}
                  >
                    {data.didYouMean}
                  </button>
                  ?
                </p>
              )}
              <ul className="mx-auto mt-4 max-w-md list-disc space-y-1 pl-5 text-left text-[13px] text-muted-foreground">
                {NO_RESULT_TIPS.map((tip) => (
                  <li key={tip}>{tip}</li>
                ))}
              </ul>
            </div>
          ) : (
            <ul className="grid gap-3" aria-label="Search results">
              {items.map((item) => (
                <SearchResultCard key={item.documentId} item={item} options={filterOptions.data} />
              ))}
            </ul>
          )}

          {(cursors.length > 1 || data.nextCursor) && (
            <div className="flex items-center justify-between">
              <Button
                type="button"
                variant="outline"
                disabled={cursors.length === 1}
                onClick={() => setCursors((c) => c.slice(0, -1))}
              >
                Previous
              </Button>
              <span className="text-[13px] text-muted-foreground">Page {cursors.length}</span>
              <Button
                type="button"
                variant="outline"
                disabled={!data.nextCursor}
                onClick={() => data.nextCursor && setCursors((c) => [...c, data.nextCursor])}
              >
                Next
              </Button>
            </div>
          )}
        </>
      )}

      <div className="flex items-start gap-2 text-[12px] text-muted-foreground">
        <Info size={14} className="mt-0.5 shrink-0" />
        <p>
          Results only include documents you can open. New or changed documents can take up to a minute to appear, and the words
          inside a new file are searchable a little after its name and details.
        </p>
      </div>
    </div>
  )
}

function Banner({ tone, children }: { tone: "info" | "warn"; children: React.ReactNode }) {
  return (
    <div
      role="status"
      className={
        tone === "warn"
          ? "rounded-lg border border-[var(--gold)]/40 bg-[var(--gold-tint)] px-4 py-2.5 text-[13px]"
          : "rounded-lg border border-border bg-muted px-4 py-2.5 text-[13px]"
      }
    >
      {children}
    </div>
  )
}
