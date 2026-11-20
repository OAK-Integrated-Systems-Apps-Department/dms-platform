"use client"

import { useMemo, useState } from "react"
import { ArrowDown, ArrowUp } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog"
import { Input } from "@/components/ui/input"
import { LoadFailed } from "@/components/ui/load-failed"
import { LoadingState } from "@/components/ui/loading-state"
import { Switch } from "@/components/ui/switch"
import type { AdminField, FieldOverride } from "@/lib/reports/api"
import { fieldProblems } from "@/lib/reports/messages"
import { useAdminFields, useSaveAdminFields } from "@/lib/reports/use-reports"

type Draft = Record<string, FieldOverride>
type Switches = "enabled" | "selectable" | "filterable" | "sortable" | "groupable" | "defaultSelected"

const COLUMNS: { key: Switches; label: string; can?: (f: AdminField) => boolean }[] = [
  { key: "enabled", label: "Reportable" },
  { key: "selectable", label: "Column", can: (f) => f.canSelect },
  { key: "filterable", label: "Filter", can: (f) => f.canFilter },
  { key: "sortable", label: "Sort", can: (f) => f.canSort },
  { key: "groupable", label: "Group", can: (f) => f.canGroup },
  { key: "defaultSelected", label: "Default column", can: (f) => f.canSelect },
]

/** A complete override for a field: what it says now, so a switch changed here never resets another to the wrong value. */
function complete(f: AdminField, o: FieldOverride | undefined): FieldOverride {
  return {
    fieldKey: f.key,
    enabled: o?.enabled ?? true,
    labelOverride: o?.labelOverride ?? null,
    selectable: o?.selectable ?? true,
    filterable: o?.filterable ?? true,
    sortable: o?.sortable ?? true,
    groupable: o?.groupable ?? true,
    defaultSelected: o?.defaultSelected ?? f.defaultSelected,
    displayOrder: o?.displayOrder ?? null,
    showCode: o?.showCode ?? false,
  }
}

const orderOf = (draft: Draft, f: AdminField, index: number) => draft[f.key]?.displayOrder ?? index

function draftFrom(fields: AdminField[]): Draft {
  const d: Draft = {}
  for (const f of fields) if (f.override) d[f.key] = complete(f, f.override)
  return d
}

/** Which fields the report builder offers, and how each behaves. Saved as a whole; fields you do not touch keep the code's defaults. */
export function FieldsTab({ canEdit = true }: { canEdit?: boolean }) {
  const query = useAdminFields()
  if (query.isPending) return <LoadingState message="Loading fields…" />
  if (query.isError || !query.data) {
    return <LoadFailed error={query.error} fallback="Could not load the fields." onRetry={() => void query.refetch()} />
  }
  return <FieldsEditor fields={query.data} canEdit={canEdit} />
}

function FieldsEditor({ fields, canEdit }: { fields: AdminField[]; canEdit: boolean }) {
  const save = useSaveAdminFields()
  const [draft, setDraft] = useState<Draft>(() => draftFrom(fields))
  const [search, setSearch] = useState("")
  const [problems, setProblems] = useState<Record<string, string>>({})
  const [confirmOff, setConfirmOff] = useState<AdminField | null>(null)
  const locked = !canEdit || save.isPending

  const ordered = useMemo(
    () => fields.map((f, i) => ({ f, i })).sort((a, b) => orderOf(draft, a.f, a.i) - orderOf(draft, b.f, b.i) || a.i - b.i).map((x) => x.f),
    [fields, draft]
  )
  const needle = search.trim().toLowerCase()
  const visible = ordered.filter((f) => !needle || f.label.toLowerCase().includes(needle) || f.key.toLowerCase().includes(needle))
  const initial = useMemo(() => draftFrom(fields), [fields])
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial)

  function patch(f: AdminField, change: Partial<FieldOverride>) {
    setDraft((d) => ({ ...d, [f.key]: { ...complete(f, d[f.key]), ...change } }))
    setProblems((p) => {
      const rest = { ...p }
      delete rest[f.key]
      return rest
    })
  }

  function reset(key: string) {
    setDraft((d) => {
      const rest = { ...d }
      delete rest[key]
      return rest
    })
  }

  function move(f: AdminField, by: -1 | 1) {
    const index = ordered.findIndex((x) => x.key === f.key)
    const target = index + by
    if (target < 0 || target >= ordered.length) return
    const next = ordered.slice()
    ;[next[index], next[target]] = [next[target], next[index]]
    setDraft((d) => {
      const out = { ...d }
      next.forEach((field, i) => {
        out[field.key] = { ...complete(field, out[field.key]), displayOrder: i }
      })
      return out
    })
  }

  function toggle(f: AdminField, key: Switches, value: boolean) {
    if (key === "enabled" && !value) {
      setConfirmOff(f)
      return
    }
    patch(f, { [key]: value })
  }

  async function submit() {
    try {
      await save.mutateAsync(Object.values(draft))
      setProblems({})
    } catch (error) {
      const found = fieldProblems(error)
      setProblems(found)
      toast.error(Object.keys(found).length ? "Some fields have problems. See the messages next to them." : "Could not save the fields.")
    }
  }

  const changed = Object.keys({ ...draft, ...initial }).filter((k) => JSON.stringify(draft[k]) !== JSON.stringify(initial[k]))
  const label = (key: string) => fields.find((f) => f.key === key)?.label ?? key

  return (
    <section className="rounded-lg border border-border bg-card" aria-label="Report fields">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-5 py-3.5">
        <div>
          <h2 className="text-[14.5px] font-semibold">Fields</h2>
          <p className="mt-0.5 text-[12.5px] text-muted-foreground">
            Switch off what should not appear in reports, rename a field, and put the fields in the order people see them. A switch can only
            take something away from a field, never give it a power it does not have.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Input aria-label="Search fields" className="w-[220px]" placeholder="Search fields…" value={search} onChange={(e) => setSearch(e.target.value)} />
          {canEdit && (
            <Button variant="outline" disabled={locked || Object.keys(draft).length === 0} onClick={() => setDraft({})}>
              Reset all
            </Button>
          )}
        </div>
      </header>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-[13px]">
          <thead className="text-[12px] text-muted-foreground">
            <tr>
              <th className="px-4 py-2.5 font-medium">Field</th>
              <th className="px-4 py-2.5 font-medium">Label</th>
              {COLUMNS.map((c) => <th key={c.key} className="px-3 py-2.5 text-center font-medium">{c.label}</th>)}
              <th className="px-4 py-2.5 font-medium"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {visible.map((f) => {
              const o = complete(f, draft[f.key])
              const problem = problems[f.key]
              return (
                <tr key={f.key} data-field={f.key}>
                  <td className="px-4 py-2.5">
                    <div className="font-medium">{f.label}</div>
                    <div className="text-[12px] text-muted-foreground">{f.custom ? "Custom field" : f.type}</div>
                    {problem && <p role="alert" className="mt-1 text-[12.5px] font-medium text-destructive">{problem}</p>}
                  </td>
                  <td className="px-4 py-2.5">
                    <Input aria-label={`Label for ${f.label}`} className="w-[170px]" maxLength={120} placeholder={f.label} disabled={locked}
                      value={o.labelOverride ?? ""} onChange={(e) => patch(f, { labelOverride: e.target.value })} />
                  </td>
                  {COLUMNS.map((c) => {
                    const can = c.can ? c.can(f) : true
                    return (
                      <td key={c.key} className="px-3 py-2.5 text-center">
                        <Switch
                          size="sm"
                          aria-label={`${c.label}: ${f.label}`}
                          checked={can && o[c.key] === true}
                          disabled={locked || !can || (c.key !== "enabled" && o.enabled === false)}
                          onCheckedChange={(v) => toggle(f, c.key, v)}
                        />
                      </td>
                    )
                  })}
                  <td className="px-4 py-2.5">
                    {canEdit && (
                      <div className="flex justify-end gap-1">
                        <Button size="icon-xs" variant="ghost" aria-label={`Move ${f.label} up`} disabled={locked || Boolean(needle)} onClick={() => move(f, -1)}>
                          <ArrowUp />
                        </Button>
                        <Button size="icon-xs" variant="ghost" aria-label={`Move ${f.label} down`} disabled={locked || Boolean(needle)} onClick={() => move(f, 1)}>
                          <ArrowDown />
                        </Button>
                        <Button size="xs" variant="outline" aria-label={`Reset ${f.label} to default`} disabled={locked || !draft[f.key]} onClick={() => reset(f.key)}>
                          Reset
                        </Button>
                      </div>
                    )}
                  </td>
                </tr>
              )
            })}
            {visible.length === 0 && (
              <tr><td colSpan={COLUMNS.length + 3} className="px-5 py-8 text-center text-muted-foreground">No fields match.</td></tr>
            )}
          </tbody>
        </table>
      </div>
      {problems.overrides && <p role="alert" className="px-5 pb-2 text-[12.5px] font-medium text-destructive">{problems.overrides}</p>}
      {canEdit ? (
        <footer className="flex flex-col gap-3 border-t border-border px-5 py-4">
          {changed.length > 0 && (
            <div className="rounded-lg border border-border bg-muted/40 px-4 py-3 text-[13px]">
              <p className="mb-1 font-medium">Not saved yet</p>
              <ul className="space-y-0.5">
                {changed.map((k) => <li key={k}>{label(k)}: {draft[k] ? "changed" : "back to the default"}</li>)}
              </ul>
            </div>
          )}
          <div className="flex items-center gap-3">
            <Button disabled={!dirty || locked} onClick={() => void submit()}>{save.isPending ? "Saving…" : "Save"}</Button>
            <Button variant="outline" disabled={!dirty || locked} onClick={() => { setDraft(initial); setProblems({}) }}>Discard</Button>
          </div>
        </footer>
      ) : (
        <p className="border-t border-border px-5 py-4 text-[12.5px] text-muted-foreground">You can look at these settings but not change them.</p>
      )}
      {confirmOff && (
        <ConfirmActionDialog
          open
          onOpenChange={(open) => { if (!open) setConfirmOff(null) }}
          title={`Turn off “${confirmOff.label}”?`}
          description={usageWarning(confirmOff)}
          confirmLabel="Turn off"
          pendingLabel="Turning off…"
          onConfirm={async () => {
            patch(confirmOff, { enabled: false, defaultSelected: false })
            return null
          }}
        />
      )}
    </section>
  )
}

/** What turning a field off affects: counts only, never the names of other people's reports. */
function usageWarning(f: AdminField): string {
  const reports = f.officialTemplatesUsing ?? 0
  const schedules = f.schedulesUsing ?? 0
  const base = "It disappears from the report builder."
  if (reports === 0 && schedules === 0) return `${base} No official report or active schedule uses it.`
  const parts = [reports > 0 && `${reports} official report${reports === 1 ? "" : "s"}`, schedules > 0 && `${schedules} schedule${schedules === 1 ? "" : "s"}`].filter(Boolean)
  return `${base} ${parts.join(" and ")} use it and will stop working until the field is removed from them or turned back on.`
}
