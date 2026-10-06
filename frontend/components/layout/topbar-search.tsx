"use client"

import { useEffect, useId, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import { useQuery } from "@tanstack/react-query"
import { FolderOpen, Loader2 } from "lucide-react"

import { fileKind, FriendlyDate } from "@/components/documents/browser-items"
import { SearchInput } from "@/components/ui/search-input"
import { fetchDocuments, type DocumentListItemDto } from "@/lib/documents/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

const RESULTS = 6
/** How long typing has to pause before the search runs. */
const DEBOUNCE_MS = 250

/**
 * The top bar's search: documents the user can see in any folder, by title,
 * as they type. Pick one (click, or the arrow keys and Enter) to preview it.
 */
export function TopbarSearch({
  className,
  onOpen,
}: {
  className?: string
  onOpen: (document: DocumentListItemDto) => void
}) {
  const router = useRouter()
  const listId = useId()
  const box = useRef<HTMLDivElement>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [query, setQuery] = useState("")
  const [term, setTerm] = useState("")
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)

  const results = useQuery({
    queryKey: ["documents", "topbar-search", term],
    queryFn: () =>
      fetchDocuments({
        folderId: null,
        all: true,
        q: term,
        page: 0,
        size: RESULTS,
      }),
    enabled: term.length >= 2,
    staleTime: 30_000,
  })
  const rows = term.length >= 2 ? (results.data?.content ?? []) : []
  const showList = open && query.trim().length >= 2

  useEffect(() => {
    function onPointerDown(event: MouseEvent) {
      if (box.current && !box.current.contains(event.target as Node))
        setOpen(false)
    }
    document.addEventListener("mousedown", onPointerDown)
    return () => {
      document.removeEventListener("mousedown", onPointerDown)
      if (timer.current) clearTimeout(timer.current)
    }
  }, [])

  function change(value: string) {
    setQuery(value)
    setOpen(true)
    setActive(0)
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => setTerm(value.trim()), DEBOUNCE_MS)
  }

  function pick(document: DocumentListItemDto) {
    setOpen(false)
    onOpen(document)
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      setOpen(false)
      return
    }
    if (!showList || rows.length === 0) return
    if (event.key === "ArrowDown") {
      event.preventDefault()
      setActive((i) => (i + 1) % rows.length)
    } else if (event.key === "ArrowUp") {
      event.preventDefault()
      setActive((i) => (i - 1 + rows.length) % rows.length)
    } else if (event.key === "Enter") {
      event.preventDefault()
      pick(rows[Math.min(active, rows.length - 1)])
    }
  }

  return (
    <div ref={box} className={cn("relative", className)}>
      <SearchInput
        value={query}
        onChange={change}
        placeholder="Search documents…"
        inputProps={{
          role: "combobox",
          "aria-expanded": showList,
          "aria-controls": listId,
          "aria-autocomplete": "list",
          "aria-activedescendant":
            showList && rows.length > 0 ? `${listId}-${active}` : undefined,
          onKeyDown,
          onFocus: () => setOpen(true),
        }}
      />
      {showList && (
        <div className="absolute top-[46px] left-0 z-40 w-full min-w-[340px] overflow-hidden rounded-xl border border-border bg-popover shadow-[var(--ledger-shadow)]">
          {results.isError ? (
            <p className="px-3.5 py-3 text-[12.5px] text-[var(--rust)]">
              Could not search just now - try again.
            </p>
          ) : results.isPending || term !== query.trim() ? (
            <p className="flex items-center gap-2 px-3.5 py-3 text-[12.5px] text-muted-foreground">
              <Loader2 size={14} className="animate-spin" /> Searching…
            </p>
          ) : rows.length === 0 ? (
            <p className="px-3.5 py-3 text-[12.5px] text-muted-foreground">
              No documents match &ldquo;{term}&rdquo;.
            </p>
          ) : (
            <ul
              id={listId}
              role="listbox"
              aria-label="Documents"
              className="py-1.5"
            >
              {rows.map((row, i) => {
                const kind = fileKind(row.fileName)
                return (
                  <li
                    key={row.documentId}
                    id={`${listId}-${i}`}
                    role="option"
                    aria-selected={i === active}
                    onMouseEnter={() => setActive(i)}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => pick(row)}
                    className={cn(
                      "flex cursor-pointer items-center gap-2.5 px-3.5 py-2",
                      i === active && "bg-muted"
                    )}
                  >
                    <span
                      className={cn(
                        "flex size-8 shrink-0 items-center justify-center rounded-lg",
                        kind.tone
                      )}
                    >
                      <kind.icon size={15} />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] font-medium">
                        {row.title}
                      </span>
                      <span className="block truncate text-[11px] text-muted-foreground">
                        {[
                          row.documentTypeName,
                          row.sizeBytes !== null
                            ? formatBytes(row.sizeBytes)
                            : null,
                        ]
                          .filter(Boolean)
                          .join(" · ")}
                        {" · "}
                        <FriendlyDate iso={row.createdAt} />
                      </span>
                    </span>
                  </li>
                )
              })}
            </ul>
          )}
          <button
            type="button"
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => {
              setOpen(false)
              router.push("/documents")
            }}
            className="flex w-full items-center gap-2 border-t border-border px-3.5 py-2.5 text-left text-[12.5px] font-semibold text-primary hover:bg-muted"
          >
            <FolderOpen size={14} />
            Browse My Documents
          </button>
        </div>
      )}
    </div>
  )
}
