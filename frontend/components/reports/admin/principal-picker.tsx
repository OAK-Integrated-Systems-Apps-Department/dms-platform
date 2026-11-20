"use client"

import { useEffect, useState } from "react"
import { X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { SearchableSelect } from "@/components/ui/searchable-select"
import type { Grant } from "@/lib/reports/api"

import { knownName, useDepartmentOptions, useRoleOptions, useUserOptions } from "./use-principals"

/** The text typed so far, applied to the server search a moment after typing stops. */
function useDebounced(value: string, ms = 250) {
  const [v, setV] = useState(value)
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms)
    return () => clearTimeout(id)
  }, [value, ms])
  return v
}

type Kind = Grant["principalType"]
const KINDS: { value: Kind; label: string }[] = [
  { value: "user", label: "A person" },
  { value: "role", label: "A role" },
  { value: "department", label: "A department" },
]

/** One person, chosen from a searchable list. */
export function UserSelect({
  value, onChange, label = "Choose a person", disabled,
}: { value: string; onChange: (id: string) => void; label?: string; disabled?: boolean }) {
  const [typed, setTyped] = useState("")
  const users = useUserOptions(true, useDebounced(typed))
  return (
    <div aria-label={label} className={disabled ? "pointer-events-none opacity-60" : undefined}>
      <Input aria-label={`Search ${label.toLowerCase()}`} className="mb-1.5" placeholder="Type a name or email to search…" value={typed} onChange={(e) => setTyped(e.target.value)} />
      <SearchableSelect inline options={users.data ?? []} selected={value} onChange={onChange} allLabel={users.isPending ? "Loading people…" : label}
        searchPlaceholder="Search people…" allowClear={false} />
    </div>
  )
}

/** People chosen from a list, shown as removable chips. */
export function UserChips({
  value, onChange, disabled,
}: { value: string[]; onChange: (ids: string[]) => void; disabled?: boolean }) {
  const users = useUserOptions()
  const names = new Map((users.data ?? []).map((u) => [u.value, u.label]))
  for (const id of value) if (!names.has(id)) { const n = knownName(id); if (n) names.set(id, n) }
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-1.5">
        {value.length === 0 && <span className="text-[12.5px] text-muted-foreground">Nobody chosen.</span>}
        {value.map((id) => (
          <span key={id} className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-[12.5px]">
            {names.get(id) ?? "Unknown user"}
            {!disabled && (
              <button type="button" aria-label={`Remove ${names.get(id) ?? "this person"}`} onClick={() => onChange(value.filter((v) => v !== id))}>
                <X className="size-3" />
              </button>
            )}
          </span>
        ))}
      </div>
      {!disabled && (
        <UserSelect label="Add a person" value="" onChange={(id) => id && !value.includes(id) && onChange([...value, id])} />
      )}
    </div>
  )
}

/** A list of people, roles and departments with an access level: the audience of an official report. Empty = everyone. */
export function GrantPicker({
  value, onChange, level = "run", disabled,
}: { value: Grant[]; onChange: (grants: Grant[]) => void; level?: Grant["accessLevel"]; disabled?: boolean }) {
  const [kind, setKind] = useState<Kind>("user")
  const [typed, setTyped] = useState("")
  const term = useDebounced(typed)
  const users = useUserOptions(true, kind === "user" ? term : "")
  const roles = useRoleOptions(true, kind === "role" ? term : "")
  const departments = useDepartmentOptions()
  const options = kind === "user" ? users.data : kind === "role" ? roles.data : departments.data
  const names = new Map<string, string>()
  for (const o of [...(users.data ?? []), ...(roles.data ?? []), ...(departments.data ?? [])]) names.set(o.value, o.label)
  for (const g of value) if (!names.has(g.principalId)) { const n = knownName(g.principalId); if (n) names.set(g.principalId, n) }

  function add(id: string) {
    if (!id || value.some((g) => g.principalType === kind && g.principalId === id)) return
    onChange([...value, { principalType: kind, principalId: id, accessLevel: level }])
  }

  return (
    <div className="flex flex-col gap-3">
      <ul className="flex flex-wrap gap-1.5" aria-label="Audience">
        {value.length === 0 && <li className="text-[12.5px] text-muted-foreground">Everyone in the organisation who may run reports.</li>}
        {value.map((g) => (
          <li key={`${g.principalType}:${g.principalId}`} className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-[12.5px]">
            <span className="text-muted-foreground">{g.principalType}:</span> {names.get(g.principalId) ?? "Unknown"}
            {!disabled && (
              <button type="button" aria-label={`Remove ${names.get(g.principalId) ?? g.principalType}`}
                onClick={() => onChange(value.filter((x) => x !== g))}>
                <X className="size-3" />
              </button>
            )}
          </li>
        ))}
      </ul>
      {!disabled && (
        <div className="flex flex-col gap-2">
          <div className="flex gap-2" role="radiogroup" aria-label="Kind of audience">
            {KINDS.map((k) => (
              <Button key={k.value} type="button" size="xs" variant={kind === k.value ? "default" : "outline"} role="radio"
                aria-checked={kind === k.value} onClick={() => { setKind(k.value); setTyped("") }}>
                {k.label}
              </Button>
            ))}
          </div>
          {kind !== "department" && (
            <Input aria-label="Search by name" placeholder="Type a name to search…" value={typed} onChange={(e) => setTyped(e.target.value)} />
          )}
          <SearchableSelect inline options={options ?? []} selected="" onChange={add} allLabel="Add to the audience…" allowClear={false} />
        </div>
      )}
    </div>
  )
}
