"use client"

import { useEffect, useRef, useState } from "react"
import { Check, ChevronDown, X } from "lucide-react"

import { cn } from "@/lib/utils"

interface RoleMultiSelectProps {
  options: string[]
  selected: string[]
  onChange: (roles: string[]) => void
}

/**
 * "Select one or more roles…" chip picker from dms-add-user.html,
 * rebuilt as controlled React instead of the mockup's manual DOM/Set
 * manipulation. `options` is meant to be sourced from
 * components/roles/data.ts (initialRoles.map(r => r.name)) — same single
 * source of truth already used for the role filter on the User List page.
 */
export function RoleMultiSelect({ options, selected, onChange }: RoleMultiSelectProps) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener("click", handleClickOutside)
    return () => document.removeEventListener("click", handleClickOutside)
  }, [])

  function toggleRole(role: string) {
    onChange(selected.includes(role) ? selected.filter((r) => r !== role) : [...selected, role])
  }

  function removeRole(role: string, event: React.MouseEvent) {
    event.stopPropagation()
    onChange(selected.filter((r) => r !== role))
  }

  return (
    <div ref={containerRef} className="relative">
      <div
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "flex min-h-11 w-full flex-wrap items-center gap-1.5 rounded-[9px] border bg-muted py-2 pr-9 pl-2.5",
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
        <div className="absolute top-[calc(100%+6px)] right-0 left-0 z-20 rounded-[10px] border border-border bg-card p-1.5 shadow-[var(--ledger-shadow)]">
          {options.map((role) => {
            const isSelected = selected.includes(role)
            return (
              <div
                key={role}
                onClick={() => toggleRole(role)}
                className="flex cursor-pointer items-center gap-2.5 rounded-[7px] px-2.5 py-2 text-[13.5px] hover:bg-muted"
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
          })}
        </div>
      )}
    </div>
  )
}