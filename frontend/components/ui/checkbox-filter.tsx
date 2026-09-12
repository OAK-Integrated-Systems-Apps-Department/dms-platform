"use client"

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import { createPortal } from "react-dom"
import { Check, ChevronDown, Search, X } from "lucide-react"

const POPOVER_MAX_HEIGHT = 420
const VIEWPORT_MARGIN = 12
const MIN_POPOVER_HEIGHT = 200

export interface CheckboxFilterProps {
  /** Label shown on the trigger button and used in placeholder text, e.g. "Module". */
  label: string
  /** Full list of selectable values. */
  options: string[]
  /** Currently selected values. An empty array means "no filter applied". */
  selected: string[]
  /** Called with the next selection whenever the user toggles, selects all, or clears. */
  onChange: (next: string[]) => void
  /** Optional fixed width for the trigger button. Defaults to 190px. */
  triggerWidth?: number
  /** Optional minimum width for the popover. Defaults to 240px. */
  popoverMinWidth?: number
  /**
   * Optional display label for an option value, e.g. mapping a raw
   * "MANAGE_ROLES_AND_PERMISSIONS" code to "Manage Roles & Permissions".
   * Used for the option list, the trigger's single-selection summary, the
   * selected-value chips, and search matching — `selected`/`onChange`
   * still deal exclusively in the raw `options` values, so filtering
   * keys off the underlying value regardless of what's displayed.
   * Defaults to the identity function (show the value as-is).
   */
  getLabel?: (value: string) => string
}

interface PopoverPosition {
  top?: number
  bottom?: number
  left: number
  width: number
  maxHeight: number
}

/**
 * A dropdown filter trigger that opens a popover containing its own search
 * box plus a checkbox list, so multiple values can be selected at once.
 * `selected = []` is treated as "all" (no filter applied) by the caller.
 *
 * The popover is rendered via a portal to `document.body` and positioned
 * with `position: fixed` coordinates from the trigger's bounding rect, so
 * it can't inflate the page's scroll height. Its overall height is capped
 * and it flips above the trigger when there isn't enough room below. The
 * options list and the selected-chips tray each scroll independently
 * within that fixed budget, so a large selection never grows the popover
 * itself.
 *
 * Position is only ever recalculated in response to scrolling/resizing of
 * ancestors outside the popover — internal scrolling of the options list
 * or the chips tray is explicitly ignored, otherwise reaching the bottom
 * of a long list would re-trigger positioning mid-interaction and could
 * flash the popover to a zero-height / blank state.
 */
export function CheckboxFilter({
  label,
  options,
  selected,
  onChange,
  triggerWidth = 190,
  popoverMinWidth = 240,
  getLabel = (value) => value,
}: CheckboxFilterProps) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [mounted, setMounted] = useState(false)
  const [position, setPosition] = useState<PopoverPosition | null>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const popoverRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setMounted(true)
  }, [])

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      const target = event.target as Node
      if (
        triggerRef.current &&
        !triggerRef.current.contains(target) &&
        popoverRef.current &&
        !popoverRef.current.contains(target)
      ) {
        setOpen(false)
      }
    }
    if (open) {
      document.addEventListener("mousedown", handleClickOutside)
      return () => document.removeEventListener("mousedown", handleClickOutside)
    }
  }, [open])

  useLayoutEffect(() => {
    if (!open) {
      setSearch("")
      setPosition(null)
      return
    }

    function updatePosition() {
      const rect = triggerRef.current?.getBoundingClientRect()
      if (!rect) return

      const spaceBelow = window.innerHeight - rect.bottom - VIEWPORT_MARGIN
      const spaceAbove = rect.top - VIEWPORT_MARGIN
      const openUpward = spaceBelow < POPOVER_MAX_HEIGHT && spaceAbove > spaceBelow

      const available = openUpward ? spaceAbove : spaceBelow
      const maxHeight = Math.min(POPOVER_MAX_HEIGHT, Math.max(MIN_POPOVER_HEIGHT, available))

      setPosition({
        top: openUpward ? undefined : rect.bottom + 6,
        bottom: openUpward ? window.innerHeight - rect.top + 6 : undefined,
        left: rect.left,
        width: rect.width,
        maxHeight,
      })
    }

    // Ignore scroll events that originate from inside the popover itself
    // (the options list, the chips tray) — only reposition in response to
    // scrolling/resizing that could move the trigger button.
    function handleScroll(event: Event) {
      if (popoverRef.current && event.target instanceof Node && popoverRef.current.contains(event.target)) {
        return
      }
      updatePosition()
    }

    updatePosition()
    window.addEventListener("scroll", handleScroll, true)
    window.addEventListener("resize", updatePosition)
    return () => {
      window.removeEventListener("scroll", handleScroll, true)
      window.removeEventListener("resize", updatePosition)
    }
    // Selecting/clearing an option can itself move the trigger — its own
    // label text changes length, and the sibling "Clear filters" button
    // (rendered by the parent) appears/disappears the moment
    // `selected.length` crosses 0, which can reflow the whole filter
    // toolbar row. Neither of those fires a scroll or resize event, so
    // without `selected`/`options` here the popover would stay anchored
    // to wherever the trigger was BEFORE that reflow — still rendering,
    // just no longer aligned with the (now-moved) trigger button.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, selected, options])

  const filteredOptions = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return options
    return options.filter((option) => getLabel(option).toLowerCase().includes(q))
  }, [options, search, getLabel])

  function toggleOption(option: string) {
    if (selected.includes(option)) {
      onChange(selected.filter((value) => value !== option))
    } else {
      onChange([...selected, option])
    }
  }

  function selectAllFiltered() {
    const merged = new Set([...selected, ...filteredOptions])
    onChange(Array.from(merged))
  }

  function clearFiltered() {
    onChange(selected.filter((value) => !filteredOptions.includes(value)))
  }

  const summary =
    selected.length === 0
      ? `All ${label.toLowerCase()}s`
      : selected.length === 1
        ? getLabel(selected[0])
        : `${selected.length} ${label.toLowerCase()}s`

  return (
    <div className="relative">
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        style={{ width: triggerWidth }}
        className="flex h-[38px] items-center justify-between gap-2 rounded-btn border border-border bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
      >
        <span className="truncate">
          <span className="text-[var(--ink-faint)]">{label}: </span>
          {summary}
        </span>
        <ChevronDown size={14} className="shrink-0 text-[var(--ink-faint)]" />
      </button>

      {open &&
        mounted &&
        position &&
        createPortal(
          <div
            ref={popoverRef}
            style={{
              position: "fixed",
              top: position.top,
              bottom: position.bottom,
              left: position.left,
              width: Math.max(position.width, popoverMinWidth),
              maxHeight: position.maxHeight,
            }}
            className="z-50 flex flex-col overflow-hidden rounded-md border border-border bg-card shadow-[var(--ledger-shadow)]"
          >
            <div className="shrink-0 border-b border-border p-2">
              <div className="relative">
                <Search
                  size={13}
                  className="pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2 text-[var(--ink-faint)]"
                />
                <input
                  autoFocus
                  type="text"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  placeholder={`Search ${label.toLowerCase()}s…`}
                  className="h-[30px] w-full rounded-sm border border-border bg-muted pr-2 pl-7 text-[12.5px] text-foreground outline-none placeholder:text-[var(--ink-faint)] focus-visible:border-primary"
                />
              </div>
            </div>

            <div className="flex shrink-0 items-center justify-between border-b border-border px-2.5 py-1.5 text-[11.5px]">
              <button
                type="button"
                onClick={selectAllFiltered}
                className="font-semibold text-primary hover:underline"
              >
                Select all
              </button>
              <button
                type="button"
                onClick={clearFiltered}
                className="font-semibold text-muted-foreground hover:underline"
              >
                Clear
              </button>
            </div>

            <div className="min-h-[100px] flex-1 overflow-y-auto p-1.5">
              {filteredOptions.length === 0 ? (
                <p className="px-2 py-3 text-center text-[12px] text-muted-foreground">
                  No matches
                </p>
              ) : (
                filteredOptions.map((option) => {
                  const checked = selected.includes(option)
                  return (
                    <label
                      key={option}
                      // The default browser behavior for clicking a <label>
                      // is to focus (and, since the input is visually
                      // hidden via sr-only below, auto-SCROLL to reveal)
                      // its associated <input>. That native scroll was
                      // the real cause of the popover's height blowing up
                      // when picking an option further down the list —
                      // it happened right as updatePosition() re-measured
                      // the trigger's position, catching it mid-scroll.
                      // Handling the toggle here ourselves and preventing
                      // the label's default action avoids that scroll
                      // entirely; keyboard/screen-reader users tabbing
                      // directly to the input below are unaffected.
                      onClick={(event) => {
                        event.preventDefault()
                        toggleOption(option)
                      }}
                      className="flex cursor-pointer items-center gap-2 rounded-[6px] px-2 py-1.5 text-[12.5px] text-foreground hover:bg-muted"
                    >
                      <span
                        className={`flex h-[15px] w-[15px] shrink-0 items-center justify-center rounded-[4px] border ${
                          checked
                            ? "border-primary bg-primary text-primary-foreground"
                            : "border-[var(--border-strong)] bg-card"
                        }`}
                      >
                        {checked && <Check size={11} strokeWidth={3} />}
                      </span>
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggleOption(option)}
                        className="sr-only"
                      />
                      <span className="truncate">{getLabel(option)}</span>
                    </label>
                  )
                })
              )}
            </div>

            {selected.length > 0 && (
              <div className="flex max-h-[110px] shrink-0 flex-wrap gap-1 overflow-y-auto border-t border-border p-2">
                {selected.map((value) => (
                  <span
                    key={value}
                    className="flex items-center gap-1 rounded-full border border-border bg-muted px-2 py-0.5 text-[11px] text-foreground"
                  >
                    {getLabel(value)}
                    <button
                      type="button"
                      onClick={() => toggleOption(value)}
                      className="text-[var(--ink-faint)] hover:text-foreground"
                    >
                      <X size={10} />
                    </button>
                  </span>
                ))}
              </div>
            )}
          </div>,
          document.body
        )}
    </div>
  )
}