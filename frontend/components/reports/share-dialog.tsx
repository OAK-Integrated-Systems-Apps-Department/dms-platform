"use client"

import { useState } from "react"
import { X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import type { Grant, TemplateDto } from "@/lib/reports/api"
import { useRecipientDirectory, useShareTemplate } from "@/lib/reports/use-reports"

type Kind = Grant["principalType"]
const KINDS: { value: Kind; label: string }[] = [
  { value: "user", label: "Person" },
  { value: "role", label: "Role" },
  { value: "department", label: "Department" },
]

/**
 * Who else can open a report: people, roles or departments, each able to run it or to edit it. The list replaces the existing one;
 * an empty list makes the report private again. Sharing never gives anyone more documents - a person running a shared report still
 * sees only what their own access allows.
 */
export function ShareDialog({ template, onClose }: { template: TemplateDto; onClose: () => void }) {
  const directory = useRecipientDirectory()
  const share = useShareTemplate()
  const [grants, setGrants] = useState<Grant[]>(template.grants ?? [])
  const [kind, setKind] = useState<Kind>("user")
  const [picked, setPicked] = useState("")
  const [level, setLevel] = useState<Grant["accessLevel"]>("run")

  const options = kind === "user" ? directory.users : kind === "role" ? directory.roles : directory.departments
  const nameOf = (g: Grant) =>
    [...directory.users, ...directory.roles, ...directory.departments].find((o) => o.value === g.principalId)?.label ?? "Unknown"

  function add() {
    if (!picked || grants.some((g) => g.principalType === kind && g.principalId === picked)) return
    setGrants([...grants, { principalType: kind, principalId: picked, accessLevel: level }])
    setPicked("")
  }

  function save() {
    share.mutate({ id: template.id, grants }, { onSuccess: onClose })
  }

  return (
    <Sheet open onOpenChange={(open) => !open && !share.isPending && onClose()}>
      <SheetContent side="right" className="w-full gap-0 data-[side=right]:sm:max-w-[520px]">
        <SheetHeader className="border-b border-border">
          <SheetTitle>Share “{template.name}”</SheetTitle>
          <SheetDescription>
            People you share it with can run it with their own access. They never see documents they could not already see.
          </SheetDescription>
        </SheetHeader>
        <div className="flex-1 space-y-4 overflow-y-auto p-5">
          <div className="flex flex-wrap items-end gap-2">
            <label className="text-[12px] text-muted-foreground">
              Share with
              <select
                aria-label="Share with"
                value={kind}
                onChange={(e) => {
                  setKind(e.target.value as Kind)
                  setPicked("")
                }}
                className="mt-1 block h-10 rounded-btn border border-border bg-background px-2 text-[13px] text-foreground"
              >
                {KINDS.map((k) => (
                  <option key={k.value} value={k.value}>
                    {k.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-[12px] text-muted-foreground">
              They can
              <select
                aria-label="They can"
                value={level}
                onChange={(e) => setLevel(e.target.value as Grant["accessLevel"])}
                className="mt-1 block h-10 rounded-btn border border-border bg-background px-2 text-[13px] text-foreground"
              >
                <option value="run">Run it</option>
                <option value="edit">Edit it</option>
              </select>
            </label>
            <div className="min-w-[200px] flex-1">
              <SearchableSelect inline options={options} selected={picked} onChange={setPicked} allLabel="Choose…" allowClear={false} />
            </div>
            <Button type="button" variant="outline" onClick={add} disabled={!picked}>
              Add
            </Button>
          </div>

          {grants.length === 0 ? (
            <p className="text-[13px] text-muted-foreground">Only you can open this report.</p>
          ) : (
            <ul className="divide-y divide-border rounded-md border border-border" aria-label="Shared with">
              {grants.map((g) => (
                <li key={`${g.principalType}:${g.principalId}`} className="flex items-center justify-between gap-3 px-3 py-2 text-[13px]">
                  <span>
                    <span className="text-muted-foreground">{KINDS.find((k) => k.value === g.principalType)?.label}: </span>
                    {nameOf(g)}
                  </span>
                  <span className="flex items-center gap-2">
                    <select
                      aria-label={`Access for ${nameOf(g)}`}
                      value={g.accessLevel}
                      onChange={(e) =>
                        setGrants(grants.map((x) => (x === g ? { ...x, accessLevel: e.target.value as Grant["accessLevel"] } : x)))
                      }
                      className="h-8 rounded-btn border border-border bg-background px-1.5 text-[12.5px]"
                    >
                      <option value="run">Run</option>
                      <option value="edit">Edit</option>
                    </select>
                    <button
                      type="button"
                      aria-label={`Stop sharing with ${nameOf(g)}`}
                      onClick={() => setGrants(grants.filter((x) => x !== g))}
                      className="text-muted-foreground hover:text-foreground"
                    >
                      <X className="size-4" />
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className="flex justify-end gap-2 border-t border-border p-4">
          <Button type="button" variant="outline" onClick={onClose} disabled={share.isPending}>
            Cancel
          </Button>
          <Button type="button" onClick={save} disabled={share.isPending}>
            {share.isPending ? "Saving…" : "Save sharing"}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  )
}
