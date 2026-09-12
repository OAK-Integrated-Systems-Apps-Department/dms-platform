"use client"

import { useEffect, useRef, useState } from "react"
import { Check, ChevronDown, Search, X } from "lucide-react"

import { cn } from "@/lib/utils"

interface RoleMultiSelectProps {
  options: string[]
  selected: string[]
  onChange: (roles: string[]) => void
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
export function RoleMultiSelect({ options, selected, onChange }: RoleMultiSelectProps) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState("")
  const containerRef = useRef<HTMLDivElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener("click", handleClickOutside)
    return () => document.removeEventListener("click", handleClickOutside)
  }, [])

  useEffect(() => {
    if (open) {
      searchInputRef.current?.focus()
    } else {
      setQuery("")
    }
  }, [open])

  function toggleRole(role: string) {
    onChange(selected.includes(role) ? selected.filter((r) => r !== role) : [...selected, role])
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
      <div
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "flex min-h-11 w-full flex-wrap items-center gap-1.5 rounded-btn border bg-muted py-2 pr-9 pl-2.5",
          "cursor-pointer transition-colors",
          open ? "border-primary bg-card ring-3 ring-primary/15" : "border-[var(--border-strong)]"
        )}
      >
        {selected.length === 0 && (
          <span className="px-0.5 py-[3px] text-[13.5px] text-[var(--ink-faint)]">Select one or more roles…</span>
        )}
        {selected.map((role) => (
          <span
            key={role}
            className="inline-flex items-center gap-1.5 rounded-[6px] border border-primary bg-primary-tint py-[3px] pr-1.5 pl-2.5 text-[12px] font-semibold text-[var(--primary-dark,var(--primary))]"
          >
            {role}
            <button type="button" onClick={(e) => removeRole(role, e)} className="flex text-current">
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
        <div
          className="absolute top-[calc(100%+6px)] right-0 left-0 z-20 rounded-md border border-border bg-card p-1.5 shadow-[var(--ledger-shadow)]"
          onClick={(e) => e.stopPropagation()}
        >
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
              <p className="px-2.5 py-2.5 text-[13px] text-[var(--ink-faint)]">No roles match "{query}"</p>
            ) : (
              filteredOptions.map((role) => {
            const isSelected = selected.includes(role)
            return (
              <div
                key={role}
                onClick={() => toggleRole(role)}
                className="flex cursor-pointer items-center gap-2.5 rounded-sm px-2.5 py-2 text-[13.5px] hover:bg-muted"
              >
                <span
                  className={cn(
                    "flex size-4 shrink-0 items-center justify-center rounded-[4px] border",
                    isSelected ? "border-primary bg-primary" : "border-[var(--border-strong)]"
                  )}
                >
                  {isSelected && <Check size={11} strokeWidth={3} className="text-primary-foreground" />}
                </span>
                {role}
              </div>
            )
              })
            )}
          </div>
        </div>
      )}
    </div>
  )
}