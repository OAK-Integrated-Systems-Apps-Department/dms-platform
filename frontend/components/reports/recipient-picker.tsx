"use client"

import { useState } from "react"
import { X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { SearchableSelect } from "@/components/ui/searchable-select"
import type { Recipient } from "@/lib/reports/api"
import { useRecipientDirectory } from "@/lib/reports/use-reports"

type Kind = Recipient["type"]

const KIND_LABELS: Record<Kind, string> = { user: "Person", role: "Role", department: "Department", email: "External address" }
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

interface RecipientPickerProps {
  value: Recipient[]
  onChange: (recipients: Recipient[]) => void
  /** External addresses: only when the organisation allows them AND the caller may administer reports. */
  allowExternal: boolean
  max?: number
  error?: string
}

/**
 * People, roles and departments, and (only if allowed) external addresses. The explanation of whose access applies is always visible:
 * it decides what each recipient is shown.
 */
export function RecipientPicker({ value, onChange, allowExternal, max, error }: RecipientPickerProps) {
  const directory = useRecipientDirectory()
  const [kind, setKind] = useState<Kind>("user")
  const [picked, setPicked] = useState("")
  const [address, setAddress] = useState("")
  const [addressError, setAddressError] = useState<string | null>(null)

  const options = kind === "user" ? directory.users : kind === "role" ? directory.roles : kind === "department" ? directory.departments : []
  const nameOf = (r: Recipient) =>
    r.type === "email" ? r.ref : ([...directory.users, ...directory.roles, ...directory.departments].find((o) => o.value === r.ref)?.label ?? "Unknown")
  const has = (r: Recipient) => value.some((v) => v.type === r.type && v.ref.toLowerCase() === r.ref.toLowerCase())
  const full = max !== undefined && value.length >= max

  function add() {
    if (kind === "email") {
      const text = address.trim()
      if (!EMAIL.test(text)) return setAddressError("Enter a valid email address.")
      if (!has({ type: "email", ref: text })) onChange([...value, { type: "email", ref: text }])
      setAddress("")
      setAddressError(null)
      return
    }
    if (picked && !has({ type: kind, ref: picked })) onChange([...value, { type: kind, ref: picked }])
    setPicked("")
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end gap-2">
        <div>
          <label htmlFor="recipient-kind" className="mb-1 block text-[12px] text-muted-foreground">
            Send to
          </label>
          <select
            id="recipient-kind"
            value={kind}
            onChange={(e) => {
              setKind(e.target.value as Kind)
              setPicked("")
              setAddressError(null)
            }}
            className="h-10 rounded-btn border border-border bg-background px-2 text-[13px]"
          >
            {(["user", "role", "department"] as Kind[]).concat(allowExternal ? ["email"] : []).map((k) => (
              <option key={k} value={k}>
                {KIND_LABELS[k]}
              </option>
            ))}
          </select>
        </div>
        <div className="min-w-[220px] flex-1">
          {kind === "email" ? (
            <Input
              aria-label="External email address"
              type="email"
              placeholder="name@example.com"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault()
                  add()
                }
              }}
            />
          ) : (
            <SearchableSelect
              inline
              options={options}
              selected={picked}
              onChange={setPicked}
              allLabel={directory.loading ? "Loading…" : `Choose a ${KIND_LABELS[kind].toLowerCase()}`}
              allowClear={false}
            />
          )}
        </div>
        <Button type="button" variant="outline" onClick={add} disabled={full || (kind !== "email" && !picked)}>
          Add
        </Button>
      </div>
      {addressError && <p role="alert" className="text-[12.5px] text-destructive">{addressError}</p>}
      {error && <p role="alert" className="text-[12.5px] text-destructive">{error}</p>}
      {full && <p className="text-[12.5px] text-muted-foreground">You can send to at most {max} recipients.</p>}

      {value.length > 0 && (
        <ul className="flex flex-wrap gap-2" aria-label="Recipients">
          {value.map((r) => (
            <li key={`${r.type}:${r.ref}`} className="inline-flex items-center gap-1.5 rounded-full border border-border bg-muted px-2.5 py-1 text-[12.5px]">
              <span className="text-muted-foreground">{KIND_LABELS[r.type]}:</span> {nameOf(r)}
              <button
                type="button"
                aria-label={`Remove ${nameOf(r)}`}
                className="text-muted-foreground hover:text-foreground"
                onClick={() => onChange(value.filter((v) => v !== r))}
              >
                <X className="size-3.5" />
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="rounded-md border border-border bg-muted/40 p-3 text-[12.5px] leading-[1.55] text-muted-foreground">
        <p>
          <strong className="font-medium text-foreground">Whose access applies.</strong> Each person, and each member of a role or
          department, gets a file made with <em>their own</em> access, so they see only what they could see themselves.
        </p>
        {allowExternal ? (
          <p className="mt-1">
            External addresses get a file made with <em>your</em> access, as an attachment. Only add addresses you are sure may see
            everything you can.
          </p>
        ) : null}
      </div>
    </div>
  )
}
