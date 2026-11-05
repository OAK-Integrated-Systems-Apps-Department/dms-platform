"use client"

import { useEffect, useRef, useState } from "react"
import { Check, ChevronDown, Loader2, Search, X } from "lucide-react"

import { cn } from "@/lib/utils"

interface RoleMultiSelectProps {
  options: string[]
  selected: string[]
  onChange: (roles: string[]) => void
  /**
   * True while `options` is still loading (the tenant role list hasn't
   * been fetched yet). Shows the trigger disabled with a small inline
   * spinner in place of the normal placeholder/chips, rather than the
   * shared full-card `LoadingState` — this is a ~44px form control, not a
   * content area, so swapping in a `py-16` card here would blow out the
   * surrounding form's layout far more than the missing options do.
   */
  loading?: boolean
}

/**
 * "Select one or more roles…" chip picker from dms-add-user.html,
 * rebuilt as controlled React instead of the mockup's manual DOM/Set
 * manipulation. `options` should be the live tenant role list — pass
 * `useRolesStore().roles.map(r => r.name)`, after making sure the store
 * has been fetched (`fetchRoles()`), not the hardcoded
 * `components/roles/data.ts` catalog. This component only knows role
 * names, not ids; the caller is responsible for resolving a selected
 * name back to a `roleId` when actually granting it (see
 * `syncUserRoles` / `assignUserRole` in `lib/users/api.ts`).
 *
 * The open panel includes a search box that filters `options` by
 * substring (case-insensitive) rather than just listing every role —
 * matters once a tenant has more than a handful of roles. Search text
 * resets whenever the panel closes so re-opening always starts from the
 * full list.
 */
export function RoleMultiSelect({
  options,
  selected,
  onChange,
  loading,
}: RoleMultiSelectProps) {
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

  if (loading) {
    return (
      <div className="flex min-h-11 w-full items-center gap-2 rounded-btn border border-[var(--border-strong)] bg-muted py-2 pr-9 pl-2.5 text-[13.5px] text-[var(--ink-faint)]">
        <Loader2 size={13} className="animate-spin" />
        Loading roles…
      </div>
    )
  }

  function toggleRole(role: string) {
    onChange(
      selected.includes(role)
        ? selected.filter((r) => r !== role)
        : [...selected, role]
    )
  }

  function removeRole(role: string, event: React.MouseEvent) {
    event.stopPropagation()
    onChange(selected.filter((r) => r !== role))
  }

  const filteredOptions = options.filter((role) =>
    role.toLowerCase().includes(query.trim().toLowerCase())
  )

  return (
    <div ref={containerRef} className="relative">
      {/* This box cannot itself be a <button>: it holds the chips, and each
          chip has its own remove button - a control may not contain another.
          So the box stays a plain <div> and an invisible button fills it,
          sitting UNDER the chips in paint order so their remove buttons
          still take the click. Clicking a chip's body no longer toggles the
          panel, which is the one behaviour this costs. */}
      <div
        className={cn(
          "relative flex min-h-11 w-full flex-wrap items-center gap-1.5 rounded-btn border bg-muted py-2 pr-9 pl-2.5",
          "transition-colors",
          open
            ? "border-primary bg-card ring-3 ring-primary/15"
            : "border-[var(--border-strong)]"
        )}
      >
        <button
          type="button"
          aria-label="Select one or more roles"
          aria-expanded={open}
          aria-haspopup="listbox"
          onClick={() => setOpen((v) => !v)}
          className="absolute inset-0 cursor-pointer rounded-btn"
        />
        {selected.length === 0 && (
          <span className="px-0.5 py-[3px] text-[13.5px] text-[var(--ink-faint)]">
            Select one or more roles…
          </span>
        )}
        {selected.map((role) => (
          <span
            key={role}
            className="bg-primary-tint relative inline-flex items-center gap-1.5 rounded-btn border border-primary py-[3px] pr-1.5 pl-2.5 text-[12px] font-semibold text-[var(--primary-dark,var(--primary))]"
          >
            {role}
            <button
              type="button"
              onClick={(e) => removeRole(role, e)}
              className="flex text-current"
            >
              <X size={11} strokeWidth={3} />
            </button>
          </span>
        ))}
        <ChevronDown
          size={14}
          className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
        />
      </div>

      {open && (
        // No onClick={stopPropagation}: the outside-click handler already
        // tests containerRef.contains(), and this panel is inside it.
        <div className="absolute top-[calc(100%+6px)] right-0 left-0 z-20 rounded-btn border border-border bg-card p-1.5 shadow-[var(--ledger-shadow)]">
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
            {filteredOptions.length === 0 ? (
              <p className="px-2.5 py-2.5 text-[13px] text-[var(--ink-faint)]">
                No roles match &ldquo;{query}&rdquo;
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
