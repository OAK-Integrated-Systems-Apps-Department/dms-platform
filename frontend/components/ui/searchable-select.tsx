"use client"

import { useEffect, useRef, useState } from "react"
import { ChevronDown, Search } from "lucide-react"

import { cn } from "@/lib/utils"

export interface SearchableSelectOption {
  value: string
  label: string
}

interface SearchableSelectProps {
  options: SearchableSelectOption[]
  /** Selected option's value, or "" for the reset ("all") state. */
  selected: string
  onChange: (value: string) => void
  /** Label for the reset item and the trigger's own text when nothing is selected, e.g. "All modules". */
  allLabel: string
  searchPlaceholder?: string
  className?: string
  /** Hides the search box for a short, fixed option list (e.g. outcome/severity) where searching adds nothing. Defaults to true. */
  searchable?: boolean
  /**
   * Opens the list in normal page flow, full width of the trigger, instead of floating over the page. Use it
   * inside a dialog: a floating list is cropped by the dialog's edges (and fixed at 240px), so only the first
   * option or two are reachable. Inline, the dialog simply grows to show the list.
   */
  inline?: boolean
  /**
   * Shows `allLabel` as a pickable reset item. Turn it off for a field that must always hold one of `options`
   * (the trigger then shows the selected option, or `allLabel` as a placeholder only before a pick).
   */
  allowClear?: boolean
}

/**
 * Single-select searchable dropdown for a filter bar - a text-searchable
 * alternative to a plain native `<select>` for a filter with more options
 * than fit comfortably in an unfiltered list (e.g. the ~20 audit actions).
 * Same interaction pattern as `ActorFilterSelect`
 * (components/audit-log/actor-filter-select.tsx), generalized to plain
 * `{value, label}` options instead of actor ids specifically - that
 * component could reasonably be rebuilt on top of this one, but is left
 * as its own implementation for now to avoid touching working code.
 */
export function SearchableSelect({
  options,
  selected,
  onChange,
  allLabel,
  searchPlaceholder = "Search…",
  className,
  searchable = true,
  inline = false,
  allowClear = true,
}: SearchableSelectProps) {
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

  function selectOption(value: string) {
    onChange(value)
    setOpen(false)
  }

  function clearSelection() {
    onChange("")
    setOpen(false)
  }

  const filteredOptions = options.filter((option) =>
    option.label.toLowerCase().includes(query.trim().toLowerCase())
  )
  const showAllOption = allowClear && query.trim() === ""
  const selectedOption = options.find((option) => option.value === selected)
  const triggerLabel = selectedOption ? selectedOption.label : allLabel

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "flex h-[38px] w-full items-center justify-between rounded-btn border bg-muted px-3 text-left text-[13.5px] text-foreground transition-colors",
          open
            ? "border-primary bg-card ring-3 ring-primary/15"
            : "border-border"
        )}
      >
        <span className="truncate">{triggerLabel}</span>
        <ChevronDown
          size={14}
          className="pointer-events-none ml-2 shrink-0 text-[var(--ink-faint)]"
        />
      </button>

      {open && (
        // No onClick={stopPropagation}: the outside-click handler already
        // tests containerRef.contains(), and this panel is inside that
        // container - so the stop did nothing but make a plain <div> look
        // like a control to anything reading the markup.
        <div
          className={cn(
            "rounded-lg border border-border bg-card p-1.5 shadow-[var(--ledger-shadow)]",
            inline
              ? "mt-1.5 w-full"
              : "absolute top-[calc(100%+6px)] left-0 z-20 w-[240px]"
          )}
        >
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
                onClick={clearSelection}
                className={cn(
                  "w-full cursor-pointer truncate rounded-sm px-2.5 py-2 text-left text-[13.5px] hover:bg-muted",
                  selected === ""
                    ? "font-semibold text-primary"
                    : "text-foreground"
                )}
              >
                {allLabel}
              </button>
            )}

            {filteredOptions.length === 0 && !showAllOption ? (
              <p className="px-2.5 py-2.5 text-[13px] text-[var(--ink-faint)]">
                No matches for &quot;{query}&quot;
              </p>
            ) : (
              filteredOptions.map((option) => {
                const isSelected = selected === option.value
                return (
                  <button
                    key={option.value}
                    type="button"
                    aria-current={isSelected}
                    onClick={() => selectOption(option.value)}
                    className={cn(
                      "w-full cursor-pointer truncate rounded-sm px-2.5 py-2 text-left text-[13.5px] hover:bg-muted",
                      isSelected
                        ? "font-semibold text-primary"
                        : "text-foreground"
                    )}
                  >
                    {option.label}
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
