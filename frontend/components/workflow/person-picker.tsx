"use client"

import { useEffect, useRef, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Check, Lock, X } from "lucide-react"

import { SearchInput } from "@/components/ui/search-input"
import { cn } from "@/lib/utils"

import type { PersonOptionDto } from "@/lib/workflow/api"

interface PersonPickerProps {
  /** Distinguishes the cache of each picker (e.g. ["delegate", taskId]). */
  queryKey: unknown[]
  fetchPeople: (search: string) => Promise<PersonOptionDto[]>
  value: PersonOptionDto | null
  onChange: (person: PersonOptionDto | null) => void
  /** Explain that someone without document access can still be picked (information requests, mentions). */
  accessHint?: string
  placeholder?: string
  /**
   * Float the results under the search box (opens on focus, closes on an outside click) instead of laying them
   * out in the page. Use it in a filter bar, where an in-flow list would stretch the whole row.
   */
  popover?: boolean
}

/**
 * Search-and-pick one person, for delegation, reassignment and information
 * requests. The search is debounced and runs server side, so it also works
 * in tenants with many users.
 */
export function PersonPicker({ queryKey, fetchPeople, value, onChange, accessHint, placeholder = "Search by name or username", popover = false }: PersonPickerProps) {
  const [search, setSearch] = useState("")
  const [debounced, setDebounced] = useState("")
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!popover) return
    function onDown(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener("mousedown", onDown)
    return () => document.removeEventListener("mousedown", onDown)
  }, [popover])

  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(search), 250)
    return () => window.clearTimeout(id)
  }, [search])

  const people = useQuery({
    queryKey: ["people-picker", ...queryKey, debounced],
    queryFn: () => fetchPeople(debounced),
    enabled: value === null,
    staleTime: 30_000,
    retry: false,
  })

  if (value) {
    return (
      <div className="flex min-h-[38px] items-center justify-between gap-2 rounded-btn border border-primary bg-primary/5 px-3 py-1 text-[13px]">
        <span className="min-w-0">
          <span className="font-semibold text-foreground">{value.displayName ?? value.username ?? "Unnamed"}</span>
          {value.departmentName && <span className="ml-1.5 text-muted-foreground">· {value.departmentName}</span>}
          {value.hasDocumentAccess === false && accessHint && (
            <span className="mt-0.5 flex items-center gap-1 text-[12px] text-[var(--gold-foreground)]">
              <Lock size={11} /> {accessHint}
            </span>
          )}
        </span>
        <button
          type="button"
          onClick={() => onChange(null)}
          className="flex shrink-0 items-center justify-center text-muted-foreground hover:text-foreground size-8 rounded-btn"
          aria-label="Choose someone else"
        >
          <X size={14} />
        </button>
      </div>
    )
  }

  const rows = people.data ?? []
  return (
    <div
      ref={containerRef}
      className={cn("flex flex-col gap-2", popover && "relative")}
      onFocusCapture={popover ? () => setOpen(true) : undefined}
      onKeyDown={popover ? (e) => e.key === "Escape" && setOpen(false) : undefined}
    >
      <SearchInput value={search} onChange={setSearch} placeholder={placeholder} />
      <ul
        hidden={popover && !open}
        className={cn(
          "max-h-[220px] overflow-y-auto rounded-lg border border-border bg-card p-1.5",
          popover && "absolute top-[calc(100%+6px)] left-0 z-20 w-[380px] max-w-[85vw] shadow-[var(--ledger-shadow)]"
        )}
        role="listbox"
      >
        {people.isPending && <li className="px-3 py-2 text-[12.5px] text-muted-foreground">Searching…</li>}
        {people.isError && (
          <li className="px-3 py-2 text-[12.5px] text-[var(--rust)]">
            {people.error instanceof Error ? people.error.message : "Could not load people."}
          </li>
        )}
        {!people.isPending && !people.isError && rows.length === 0 && (
          <li className="px-3 py-2 text-[12.5px] text-muted-foreground">Nobody matches.</li>
        )}
        {rows.map((person) => (
          <li key={person.userId}>
            <button
              type="button"
              role="option"
              aria-selected={false}
              onClick={() => {
                setOpen(false)
                onChange(person)
              }}
              className={cn("flex w-full items-center justify-between gap-2 rounded-sm px-2.5 py-2 text-left text-[13px] hover:bg-muted")}
            >
              <span className="min-w-0">
                <span className="font-medium text-foreground">{person.displayName ?? person.username ?? "Unnamed"}</span>
                <span className="ml-1.5 text-[12px] text-muted-foreground">
                  {[person.username, person.departmentName].filter(Boolean).join(" · ")}
                </span>
              </span>
              {person.hasDocumentAccess === false ? (
                <span className="flex shrink-0 items-center gap-1 text-[11.5px] text-[var(--gold-foreground)]" title="Can't open documents">
                  <Lock size={11} /> No document access
                </span>
              ) : (
                <Check size={13} className="shrink-0 text-transparent" aria-hidden="true" />
              )}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
