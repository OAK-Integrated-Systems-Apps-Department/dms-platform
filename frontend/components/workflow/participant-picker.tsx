"use client"

import { useState } from "react"
import { Building2, Loader2, Plus, Shield, User, X, type LucideIcon } from "lucide-react"

import { SearchInput } from "@/components/ui/search-input"
import { workflowErrorMessage } from "@/lib/workflow/workflow-errors"
import { useDepartmentOptions, useRoleOptions, useUserOptions } from "@/lib/workflow/use-participant-options"
import { cn } from "@/lib/utils"

import type { AssigneeType, LookupOption } from "@/lib/workflow/api"

/** One stage participant entry as the stage card edits it. displayName is null when the target no longer exists. */
export interface PickedParticipant {
  assigneeType: AssigneeType
  assigneeId: string
  displayName: string | null
}

const TABS: { value: AssigneeType; label: string; icon: LucideIcon; help: string }[] = [
  { value: "user", label: "User", icon: User, help: "A named person. Their task is assigned to them directly." },
  {
    value: "role",
    label: "Role",
    icon: Shield,
    help: "A pool: anyone holding the role can claim the task. It counts as ONE entry toward the completion rule.",
  },
  {
    value: "department",
    label: "Department",
    icon: Building2,
    help: "A pool: any member of the department can claim the task. It counts as ONE entry toward the completion rule.",
  },
]

export const ASSIGNEE_TYPE_ICON: Record<AssigneeType, LucideIcon> = { user: User, role: Shield, department: Building2 }

interface ParticipantPickerProps {
  value: PickedParticipant[]
  onChange: (value: PickedParticipant[]) => void
  disabled?: boolean
}

/**
 * Who acts on a stage: a list of entries, each a named user or a role/
 * department pool, added from three tabs. Each tab's option list loads only
 * once that tab is opened. Already-picked entries are hidden from the list,
 * since the backend rejects a duplicate participant.
 */
export function ParticipantPicker({ value, onChange, disabled }: ParticipantPickerProps) {
  const [tab, setTab] = useState<AssigneeType>("user")
  const [query, setQuery] = useState("")

  const users = useUserOptions(!disabled && tab === "user")
  const roles = useRoleOptions(!disabled && tab === "role")
  const departments = useDepartmentOptions(!disabled && tab === "department")
  const active = tab === "user" ? users : tab === "role" ? roles : departments

  const picked = new Set(value.map((p) => `${p.assigneeType}:${p.assigneeId}`))
  const q = query.trim().toLowerCase()
  const options = (active.data ?? []).filter(
    (option) =>
      !picked.has(`${tab}:${option.value}`) &&
      (!q || option.label.toLowerCase().includes(q) || option.hint?.toLowerCase().includes(q))
  )

  function add(option: LookupOption) {
    onChange([...value, { assigneeType: tab, assigneeId: option.value, displayName: option.label }])
  }

  function remove(index: number) {
    onChange(value.filter((_, i) => i !== index))
  }

  return (
    <div>
      {value.length === 0 ? (
        <p className="mb-3 text-[12.5px] text-muted-foreground">No participants yet.</p>
      ) : (
        <ul className="mb-3 flex flex-wrap gap-2">
          {value.map((participant, index) => {
            const Icon = ASSIGNEE_TYPE_ICON[participant.assigneeType]
            return (
              <li
                key={`${participant.assigneeType}:${participant.assigneeId}`}
                className={cn(
                  "flex items-center gap-1.5 rounded-btn border px-2.5 py-1.5 text-[12.5px]",
                  participant.displayName ? "border-[var(--border-strong)] bg-muted" : "border-[var(--rust)] bg-[var(--rust-tint)] text-[var(--rust)]"
                )}
              >
                <Icon size={13} className="shrink-0 text-muted-foreground" aria-hidden="true" />
                <span className="text-[10.5px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
                  {participant.assigneeType}
                </span>
                <span className="font-medium">{participant.displayName ?? "Unknown (deleted)"}</span>
                {!disabled && (
                  <button
                    type="button"
                    onClick={() => remove(index)}
                    aria-label={`Remove ${participant.displayName ?? "participant"}`}
                    className="ml-0.5 flex size-4 items-center justify-center rounded-full text-[var(--ink-faint)] hover:bg-border hover:text-foreground"
                  >
                    <X size={12} />
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {!disabled && (
        <div className="rounded-btn border border-border bg-card">
          <div className="flex items-center gap-1 border-b border-border px-2" role="tablist">
            {TABS.map((t) => {
              const Icon = t.icon
              return (
                <button
                  key={t.value}
                  type="button"
                  role="tab"
                  aria-selected={tab === t.value}
                  onClick={() => {
                    setTab(t.value)
                    setQuery("")
                  }}
                  className={cn(
                    "flex items-center gap-1.5 border-b-2 px-3 py-2 text-[12.5px] font-semibold transition-colors",
                    tab === t.value ? "border-primary text-primary" : "border-transparent text-muted-foreground hover:text-foreground"
                  )}
                >
                  <Icon size={13} />
                  {t.label}
                </button>
              )
            })}
          </div>
          <div className="p-2.5">
            <p className="mb-2 text-[12px] leading-[1.5] text-muted-foreground">{TABS.find((t) => t.value === tab)?.help}</p>
            <SearchInput value={query} onChange={setQuery} placeholder={`Search ${tab}s…`} className="mb-2" />
            <div className="max-h-48 overflow-y-auto">
              {active.isPending ? (
                <p className="flex items-center gap-2 px-2 py-2 text-[12.5px] text-muted-foreground">
                  <Loader2 size={13} className="animate-spin" /> Loading…
                </p>
              ) : active.isError ? (
                <p className="px-2 py-2 text-[12.5px] text-[var(--rust)]">
                  {workflowErrorMessage(active.error, `Could not load ${tab}s.`)}
                </p>
              ) : options.length === 0 ? (
                <p className="px-2 py-2 text-[12.5px] text-[var(--ink-faint)]">
                  {q ? `No matches for "${query}".` : `No more ${tab}s to add.`}
                </p>
              ) : (
                options.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => add(option)}
                    className="flex w-full items-center justify-between gap-2 rounded-sm px-2 py-1.5 text-left text-[13px] hover:bg-muted"
                  >
                    <span className="min-w-0 truncate">
                      {option.label}
                      {option.hint && <span className="ml-2 text-[11.5px] text-muted-foreground">{option.hint}</span>}
                    </span>
                    <Plus size={14} className="shrink-0 text-primary" />
                  </button>
                ))
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
