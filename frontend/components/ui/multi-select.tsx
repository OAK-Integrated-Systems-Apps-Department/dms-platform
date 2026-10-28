"use client"

import { useEffect, useRef, useState } from "react"
import { Check, ChevronDown, Search, X } from "lucide-react"

import { cn } from "@/lib/utils"

export interface MultiSelectOption {
  value: string
  label: string
}

interface MultiSelectProps {
  options: MultiSelectOption[]
  /** Currently selected values - an empty array means "all" (no filter). */
  selected: string[]
  onChange: (selected: string[]) => void
  /** Label for the reset ("all") row and the trigger's own text when nothing is selected, e.g. "All modules". */
  allLabel: string
  searchPlaceholder?: string
  className?: string
  /** Hides the search box for a short, fixed option list. Defaults to true. */
  searchable?: boolean
}

/**
 * Multi-select searchable dropdown for a filter bar - the multi-select
 * counterpart to `SearchableSelect` (same file's single-select sibling),
 * generalizing `RoleFilterSelect`'s (components/users/role-filter-select.tsx)
 * checkbox-list pattern to plain `{value, label}` options instead of role
 * names specifically. An event matches if its own value is ANY one of the
 * selected options (an OR), same as that component's role filter.
 *
 * An empty `selected` array is the "all" state; picking the reset row
 * clears the whole selection rather than being one more item that can be
 * toggled alongside real options, since "no filter" and "filter to
 * nothing" need to stay distinct - the latter would silently hide every
 * row, which isn't a state this filter should be able to reach.
 */
export function MultiSelect({
  options,
  selected,
  onChange,
  allLabel,
  searchPlaceholder = "Search…",
  className,
  searchable = true,
}: MultiSelectProps) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState("")
  const containerRef = useRef<HTMLDivElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        containerRef.current &&
        !containerRef.current.contains(event.target as Node)
      ) {
        setOpen(false)
      }
    }
    document.addEventListener("click", handleClickOutside)
    return () => document.removeEventListener("click", handleClickOutside)
  }, [])

  // Search text resets whenever the search box goes away (panel closed) -
  // adjusted during render against the last-seen state; the effect below
  // only handles focus.
  const searchActive = open && searchable
  const [prevSearchActive, setPrevSearchActive] = useState(searchActive)
  if (searchActive !== prevSearchActive) {
    setPrevSearchActive(searchActive)
    if (!searchActive) setQuery("")
  }

  useEffect(() => {
    if (searchActive) searchInputRef.current?.focus()
  }, [searchActive])

  function toggleValue(value: string) {
    onChange(
      selected.includes(value)
        ? selected.filter((v) => v !== value)
        : [...selected, value]
    )
  }

  function clearAll() {
    onChange([])
    setOpen(false)
  }

  const filteredOptions = options.filter((option) =>
    option.label.toLowerCase().includes(query.trim().toLowerCase())
  )
  const showAllOption = query.trim() === ""

  const triggerLabel =
    selected.length === 0
      ? allLabel
      : selected.length === 1
        ? (options.find((option) => option.value === selected[0])?.label ??
          selected[0])
        : `${selected.length} selected`

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      {/* Clear sits OUTSIDE this button, overlapping its right edge. It was
          a <span role="button"> nested inside, which no keyboard could reach
          and which is invalid HTML besides - a button may not contain
          another control. The padding below keeps the label clear of both
          overlapping icons. */}
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "flex h-[38px] w-full items-center rounded-btn border bg-muted pl-3 text-left text-[13.5px] text-foreground transition-colors",
          selected.length > 0 ? "pr-14" : "pr-9",
          open
            ? "border-primary bg-card ring-3 ring-primary/15"
            : "border-border"
        )}
      >
        <span className="truncate">{triggerLabel}</span>
      </button>

      {selected.length > 0 && (
        <button
          type="button"
          aria-label={`Clear ${allLabel.toLowerCase()}`}
          onClick={() => onChange([])}
          className="absolute top-1/2 right-8 flex size-4 -translate-y-1/2 items-center justify-center rounded-full text-[var(--ink-faint)] hover:bg-border hover:text-foreground"
        >
          <X size={12} />
        </button>
      )}
      <ChevronDown
        size={14}
        className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
      />

      {open && (
        // No onClick={stopPropagation} here: the outside-click handler above
        // already tests containerRef.contains(), and this panel is inside
        // that container - so the stop was doing nothing except making a
        // plain <div> look like a control.
        <div className="absolute top-[calc(100%+6px)] left-0 z-20 w-[240px] rounded-md border border-border bg-card p-1.5 shadow-[var(--ledger-shadow)]">
          {searchable && (
            <div className="relative mb-1.5">
              <Search
                size={13}
                className="pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2 text-[var(--ink-faint)]"
              />
              <input
                ref={searchInputRef}
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={searchPlaceholder}
                className="w-full rounded-sm border border-[var(--border-strong)] bg-muted py-2 pr-2.5 pl-8 text-[13px] text-foreground placeholder:text-[var(--ink-faint)] focus:border-primary focus:bg-card focus:outline-none"
              />
            </div>
          )}

          <div className="max-h-56 overflow-y-auto">
            {showAllOption && (
              <button
                type="button"
                onClick={clearAll}
                className="flex w-full cursor-pointer items-center gap-2.5 rounded-sm px-2.5 py-2 text-left text-[13.5px] hover:bg-muted"
              >
                <span
                  className={cn(
                    "flex size-4 shrink-0 items-center justify-center rounded-[4px] border",
                    selected.length === 0
                      ? "border-primary bg-primary"
                      : "border-[var(--border-strong)]"
                  )}
                >
                  {selected.length === 0 && (
                    <Check
                      size={11}
                      strokeWidth={3}
                      className="text-primary-foreground"
                    />
                  )}
                </span>
                {allLabel}
              </button>
            )}

            {filteredOptions.length === 0 && !showAllOption ? (
              <p className="px-2.5 py-2.5 text-[13px] text-[var(--ink-faint)]">
                No matches for &quot;{query}&quot;
              </p>
            ) : (
              filteredOptions.map((option) => {
                const isSelected = selected.includes(option.value)
                return (
                  <button
                    key={option.value}
                    type="button"
                    role="checkbox"
                    aria-checked={isSelected}
                    onClick={() => toggleValue(option.value)}
                    className="flex w-full cursor-pointer items-center gap-2.5 rounded-sm px-2.5 py-2 text-left text-[13.5px] hover:bg-muted"
                  >
                    <span
                      className={cn(
                        "flex size-4 shrink-0 items-center justify-center rounded-[4px] border",
                        isSelected
                          ? "border-primary bg-primary"
                          : "border-[var(--border-strong)]"
                      )}
                    >
                      {isSelected && (
                        <Check
                          size={11}
                          strokeWidth={3}
                          className="text-primary-foreground"
                        />
                      )}
                    </span>
                    <span className="truncate">{option.label}</span>
                  </button>
                )
              })
            )}
          </div>
        </div>
      )}
    </div>
  )
}
