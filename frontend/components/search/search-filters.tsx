"use client"

import { Plus, X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { OP_LABELS, opsFor } from "@/lib/search/params"
import type {
  LifecycleStatus,
  MetaFilter,
  MetaOp,
  SearchFilterOptionsDto,
  SearchFilters,
} from "@/lib/search/types"
import type { FolderNode } from "@/lib/folders/api"

type StatusChoice = "active" | "inactive" | "archived" | "all"

function statusChoice(statuses: LifecycleStatus[]): StatusChoice {
  if (statuses.length >= 3) return "all"
  return statuses.length === 1 ? statuses[0] : "active"
}

function statusesOf(choice: StatusChoice): LifecycleStatus[] {
  return choice === "all" ? ["active", "inactive", "archived"] : [choice]
}

/**
 * The filter panel: document type, department, owner, created and modified date ranges, status (inactive and archived only
 * for people who may see them), folder with or without subfolders, and typed metadata filters. Each control writes one
 * field of {@link SearchFilters}; the page puts the result in the URL.
 */
export function SearchFilterPanel({
  filters,
  onChange,
  options,
  folders,
  canViewArchive,
}: {
  filters: SearchFilters
  onChange: (next: SearchFilters) => void
  options: SearchFilterOptionsDto | undefined
  folders: FolderNode[]
  canViewArchive: boolean
}) {
  const set = <K extends keyof SearchFilters>(key: K, value: SearchFilters[K]) =>
    onChange({ ...filters, [key]: value })
  const fields = options?.metadataFields ?? []

  function updateMeta(index: number, patch: Partial<MetaFilter>) {
    set(
      "meta",
      filters.meta.map((m, i) => (i === index ? { ...m, ...patch } : m))
    )
  }

  return (
    <div
      className="grid gap-4 rounded-lg border border-border bg-card p-4 shadow-[var(--ledger-shadow)] sm:grid-cols-2 lg:grid-cols-3"
      aria-label="Search filters"
    >
      <Field label="Document type">
        <SearchableSelect
          options={(options?.documentTypes ?? []).map((t) => ({ value: t.id, label: t.name }))}
          selected={filters.type}
          onChange={(v) => set("type", v)}
          allLabel="Any type"
          searchPlaceholder="Find a type…"
        />
      </Field>
      <Field label="Department">
        <SearchableSelect
          options={(options?.departments ?? []).map((d) => ({ value: d.id, label: d.name }))}
          selected={filters.department}
          onChange={(v) => set("department", v)}
          allLabel="Any department"
          searchPlaceholder="Find a department…"
        />
      </Field>
      <Field label="Owner">
        <SearchableSelect
          options={(options?.owners ?? []).map((o) => ({ value: o.id, label: o.name }))}
          selected={filters.owner}
          onChange={(v) => set("owner", v)}
          allLabel="Anyone"
          searchPlaceholder="Find a person…"
        />
      </Field>

      <Field label="Created">
        <DateRange
          from={filters.createdFrom}
          to={filters.createdTo}
          onFrom={(v) => set("createdFrom", v)}
          onTo={(v) => set("createdTo", v)}
          label="Created"
        />
      </Field>
      <Field label="Last modified">
        <DateRange
          from={filters.modifiedFrom}
          to={filters.modifiedTo}
          onFrom={(v) => set("modifiedFrom", v)}
          onTo={(v) => set("modifiedTo", v)}
          label="Modified"
        />
      </Field>

      <Field label="Folder">
        <SearchableSelect
          options={folders.map((f) => ({ value: f.folderId, label: f.path }))}
          selected={filters.folderId}
          onChange={(v) => onChange({ ...filters, folderId: v, includeSubfolders: v ? filters.includeSubfolders : false })}
          allLabel="Any folder"
          searchPlaceholder="Find a folder…"
        />
        <div className="mt-2">
          <Checkbox
            label="Include subfolders"
            checked={filters.includeSubfolders}
            disabled={!filters.folderId}
            onCheckedChange={(v) => set("includeSubfolders", v)}
          />
        </div>
      </Field>

      {canViewArchive && (
        <Field label="Status">
          <SegmentedControl<StatusChoice>
            aria-label="Document status"
            value={statusChoice(filters.statuses)}
            onChange={(v) => set("statuses", statusesOf(v))}
            options={[
              { value: "active", label: "Active" },
              { value: "inactive", label: "Inactive" },
              { value: "archived", label: "Archived" },
              { value: "all", label: "All" },
            ]}
          />
        </Field>
      )}

      {fields.length > 0 && (
        <div className="sm:col-span-2 lg:col-span-3">
          <Label>Metadata</Label>
          <div className="mt-2 grid gap-2">
            {filters.meta.map((m, i) => {
              const def = fields.find((f) => f.fieldId === m.fieldId)
              const ops = def ? opsFor(def.dataType) : (["eq"] as MetaOp[])
              return (
                <div key={i} className="flex flex-wrap items-center gap-2" data-testid="meta-row">
                  <div className="w-[200px]">
                    <SearchableSelect
                      options={fields.map((f) => ({ value: f.fieldId, label: f.label }))}
                      selected={m.fieldId}
                      onChange={(v) => {
                        const next = fields.find((f) => f.fieldId === v)
                        updateMeta(i, { fieldId: v, op: next ? opsFor(next.dataType)[0] : "eq", value: "" })
                      }}
                      allLabel="Choose a field"
                      allowClear={false}
                      searchPlaceholder="Find a field…"
                    />
                  </div>
                  <select
                    aria-label="Comparison"
                    value={m.op}
                    onChange={(e) => updateMeta(i, { op: e.target.value as MetaOp })}
                    className="h-10 rounded-btn border border-input bg-background px-2 text-sm"
                  >
                    {ops.map((op) => (
                      <option key={op} value={op}>
                        {OP_LABELS[op]}
                      </option>
                    ))}
                  </select>
                  <MetaValue def={def} value={m.value} onChange={(v) => updateMeta(i, { value: v })} />
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    aria-label="Remove this metadata filter"
                    onClick={() => set("meta", filters.meta.filter((_, j) => j !== i))}
                  >
                    <X size={16} />
                  </Button>
                </div>
              )
            })}
            <div>
              <Button
                type="button"
                variant="outline"
                size="xs"
                onClick={() => set("meta", [...filters.meta, { fieldId: "", op: "eq", value: "" }])}
              >
                <Plus size={14} /> Add a metadata filter
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <Label className="mb-1.5 block">{label}</Label>
      {children}
    </div>
  )
}

function DateRange({
  from,
  to,
  onFrom,
  onTo,
  label,
}: {
  from: string
  to: string
  onFrom: (v: string) => void
  onTo: (v: string) => void
  label: string
}) {
  return (
    <div className="flex items-center gap-2">
      <Input type="date" aria-label={`${label} from`} value={from} max={to || undefined} onChange={(e) => onFrom(e.target.value)} />
      <span className="text-muted-foreground">–</span>
      <Input type="date" aria-label={`${label} to`} value={to} min={from || undefined} onChange={(e) => onTo(e.target.value)} />
    </div>
  )
}

function MetaValue({
  def,
  value,
  onChange,
}: {
  def: SearchFilterOptionsDto["metadataFields"][number] | undefined
  value: string
  onChange: (v: string) => void
}) {
  const type = def?.dataType
  if (type === "dropdown" || type === "multi_select") {
    return (
      <select
        aria-label="Value"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="h-10 min-w-[160px] rounded-btn border border-input bg-background px-2 text-sm"
      >
        <option value="">Choose…</option>
        {def?.options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    )
  }
  if (type === "boolean") {
    return (
      <select
        aria-label="Value"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="h-10 rounded-btn border border-input bg-background px-2 text-sm"
      >
        <option value="">Choose…</option>
        <option value="true">Yes</option>
        <option value="false">No</option>
      </select>
    )
  }
  const inputType = type === "number" || type === "decimal" ? "number" : type === "date" || type === "datetime" ? "date" : "text"
  return (
    <Input
      aria-label="Value"
      className="w-[180px]"
      type={inputType}
      step={inputType === "number" ? "any" : undefined}
      value={value}
      onChange={(e) => onChange(e.target.value)}
    />
  )
}
