"use client"

import { useEffect, useRef, useState } from "react"
import { Check, ChevronDown, Search, X } from "lucide-react"

import { cn } from "@/lib/utils"

interface RoleFilterSelectProps {
  /** Tenant role names. */
  options: string[]
  /** Currently selected role names — an empty array means "All roles" (no filter). */
  selected: string[]
  onChange: (selected: string[]) => void
  className?: string
}

/**
 * Role filter for the User List toolbar — a searchable multi-select
 * dropdown, so an admin can filter for users holding *any* of several
 * roles at once (e.g. "Reviewer" or "Approver"), not just one at a time.
 * Mirrors `RoleMultiSelect`'s (the Add/Edit User role picker's) checkbox
 * list and "stays open across picks" behavior — the earlier version of
 * this component closed on the first pick, which is what made it look
 * like only one role could ever be checked.
 *
 * An empty `selected` array is the "All roles" state; picking "All
 * roles" from the list clears the selection rather than being one more
 * item that can be toggled alongside real roles, since "no filter" and
 * "filter to nothing" need to stay distinct — the latter would silently
 * hide every user, which isn't a state this filter should be able to
 * reach.
 */
export function RoleFilterSelect({
  options,
  selected,
  onChange,
  className,
}: RoleFilterSelectProps) {
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

  // Search text resets on close - adjusted during render against the
  // last-seen `open`; the effect below only handles focus.
  const [prevOpen, setPrevOpen] = useState(open)
  if (open !== prevOpen) {
    setPrevOpen(open)
    if (!open) setQuery("")
  }

  useEffect(() => {
    if (open) searchInputRef.current?.focus()
  }, [open])

  function toggleRole(role: string) {
    onChange(
      selected.includes(role)
        ? selected.filter((r) => r !== role)
        : [...selected, role]
    )
  }

  function clearAll() {
    onChange([])
    setOpen(false)
  }

  const filteredOptions = options.filter((role) =>
    role.toLowerCase().includes(query.trim().toLowerCase())
  )
  // "All roles" is a reset action, not a role someone would search for by name.
  const showAllRolesOption = query.trim() === ""

  const triggerLabel =
    selected.length === 0
      ? "All roles"
      : selected.length === 1
        ? selected[0]
        : `${selected.length} roles`

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      {/* Clear sits OUTSIDE this button, overlapping its right edge. It was
          a <span role="button"> nested inside, which no keyboard could reach
          and which is invalid HTML besides - a button may not contain
          another control. */}
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
          aria-label="Clear all roles"
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
        // No onClick={stopPropagation}: the outside-click handler already
        // tests containerRef.contains(), and this panel is inside it.
        <div className="absolute top-[calc(100%+6px)] left-0 z-20 w-[240px] rounded-md border border-border bg-card p-1.5 shadow-[var(--ledger-shadow)]">
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
              placeholder="Search roles…"
              className="w-full rounded-sm border border-[var(--border-strong)] bg-muted py-2 pr-2.5 pl-8 text-[13px] text-foreground placeholder:text-[var(--ink-faint)] focus:border-primary focus:bg-card focus:outline-none"
            />
          </div>

          <div className="max-h-56 overflow-y-auto">
            {showAllRolesOption && (
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
                All roles
              </button>
            )}

            {filteredOptions.length === 0 && !showAllRolesOption ? (
              <p className="px-2.5 py-2.5 text-[13px] text-[var(--ink-faint)]">
                No roles match &quot;{query}&quot;
              </p>
            ) : (
              filteredOptions.map((role) => {
                const isSelected = selected.includes(role)
                return (
                  <button
                    key={role}
                    type="button"
                    role="checkbox"
                    aria-checked={isSelected}
                    onClick={() => toggleRole(role)}
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
                    {role}
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
