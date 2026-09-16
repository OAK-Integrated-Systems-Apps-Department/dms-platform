"use client"

import { useEffect, useMemo, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { AlertTriangle } from "lucide-react"
import { toast } from "sonner"

import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { cn } from "@/lib/utils"

import { fetchAllDocumentTypes } from "@/lib/document-types/api"
import { fetchAllMetadataFields } from "@/lib/metadata-fields/api"
import {
  fetchCurrentDocumentTypeFields,
  replaceDocumentTypeFields,
  type DocumentTypeFieldMappingItemPayload,
  type DocumentTypeFieldResponseDto,
} from "@/lib/document-type-fields/api"

const COL1_WIDTH = 260

type CellState = "none" | "optional" | "mandatory"

/** One document type's full local mapping, keyed by fieldId — a cell edit here always resends the WHOLE set on save, since PUT /document-types/{id}/fields replaces the whole mapping. */
type TypeMapping = Map<string, { mandatory: boolean; displayOrder: number }>

function toTypeMapping(fields: DocumentTypeFieldResponseDto[]): TypeMapping {
  const map: TypeMapping = new Map()
  fields.forEach((f) => map.set(f.fieldId, { mandatory: f.mandatory, displayOrder: f.displayOrder }))
  return map
}

function cellState(mapping: TypeMapping, fieldId: string): CellState {
  const entry = mapping.get(fieldId)
  if (!entry) return "none"
  return entry.mandatory ? "mandatory" : "optional"
}

function nextState(state: CellState): CellState {
  if (state === "none") return "optional"
  if (state === "optional") return "mandatory"
  return "none"
}

/**
 * Document-type x field bulk-editing matrix (A6), modeled on
 * `components/roles/permission-matrix.tsx`'s sticky-first-column technique.
 * Unlike that matrix (read-only reference view; edits happen on a separate
 * per-role screen), cells here are directly toggleable — a deliberate
 * divergence the approved plan calls out, since Type Field Mapping needs
 * bulk cross-type editing, not just comparison. Each click cycles
 * not-included -> optional -> mandatory -> not-included; changes are kept
 * as a local per-type diff and only sent on "Save changes".
 */
export function DocumentTypeFieldMatrix({ canUpdate }: { canUpdate: boolean }) {
  const queryClient = useQueryClient()

  const typesQuery = useQuery({ queryKey: ["document-types"], queryFn: fetchAllDocumentTypes, retry: false })
  const fieldsQuery = useQuery({ queryKey: ["metadata-fields"], queryFn: fetchAllMetadataFields, retry: false })

  const documentTypes = useMemo(
    () => (typesQuery.data ?? []).filter((t) => t.status === "active").sort((a, b) => a.displayOrder - b.displayOrder),
    [typesQuery.data]
  )
  const fields = useMemo(
    () => (fieldsQuery.data ?? []).filter((f) => f.status === "active").sort((a, b) => a.label.localeCompare(b.label)),
    [fieldsQuery.data]
  )

  const [original, setOriginal] = useState<Record<string, TypeMapping>>({})
  const [local, setLocal] = useState<Record<string, TypeMapping>>({})
  const [mappingsFetched, setMappingsFetched] = useState(false)
  const [saving, setSaving] = useState(false)
  const [query, setQuery] = useState("")

  // "Have the per-type mappings loaded at least once" is tracked as its own
  // flag (set only from the resolved-promise callback below, never
  // synchronously in the effect body) rather than a `mappingsLoading` state
  // toggled at the top of the effect — avoids the cascading extra render a
  // synchronous setState-in-effect causes.
  const mappingsLoading = documentTypes.length > 0 && !mappingsFetched

  // Fetch every document type's current mapping in parallel once the base
  // lists load — same "fan out once, not per visible cell" shape
  // permission-matrix.tsx uses for role grants.
  useEffect(() => {
    if (documentTypes.length === 0) return
    let cancelled = false
    Promise.all(documentTypes.map((t) => fetchCurrentDocumentTypeFields(t.documentTypeId)))
      .then((results) => {
        if (cancelled) return
        const byType: Record<string, TypeMapping> = {}
        documentTypes.forEach((t, i) => {
          byType[t.documentTypeId] = toTypeMapping(results[i])
        })
        setOriginal(byType)
        setLocal(byType)
        setMappingsFetched(true)
      })
      .catch(() => {
        if (!cancelled) toast.error("Could not load field mappings for one or more document types.")
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentTypes.map((t) => t.documentTypeId).join(",")])

  const changedTypeIds = useMemo(() => {
    return Object.keys(local).filter((typeId) => {
      const a = original[typeId]
      const b = local[typeId]
      if (!a || !b) return false
      if (a.size !== b.size) return true
      for (const [fieldId, entry] of b) {
        const prev = a.get(fieldId)
        if (!prev || prev.mandatory !== entry.mandatory) return true
      }
      return false
    })
  }, [original, local])

  function toggleCell(typeId: string, fieldId: string) {
    if (!canUpdate) return
    setLocal((prev) => {
      const mapping = new Map(prev[typeId] ?? [])
      const state = cellState(mapping, fieldId)
      const next = nextState(state)
      if (next === "none") {
        mapping.delete(fieldId)
      } else {
        const existing = mapping.get(fieldId)
        mapping.set(fieldId, { mandatory: next === "mandatory", displayOrder: existing?.displayOrder ?? (mapping.size + 1) * 10 })
      }
      return { ...prev, [typeId]: mapping }
    })
  }

  async function handleSave() {
    setSaving(true)
    const results = await Promise.allSettled(
      changedTypeIds.map((typeId) => {
        const mapping = local[typeId]
        const payload: DocumentTypeFieldMappingItemPayload[] = [...mapping.entries()].map(([fieldId, entry]) => ({
          fieldId,
          mandatory: entry.mandatory,
          readonly: false,
          hidden: false,
          displayOrder: entry.displayOrder,
        }))
        return replaceDocumentTypeFields(typeId, payload)
      })
    )
    setSaving(false)

    const failures = results.filter((r): r is PromiseRejectedResult => r.status === "rejected")
    if (failures.length === 0) {
      toast.success(`Published a new schema version for ${changedTypeIds.length} document type(s).`)
    } else {
      toast.error(`${failures.length} of ${changedTypeIds.length} document types failed to save. Fix and retry.`)
    }

    queryClient.invalidateQueries({ queryKey: ["document-types"] })
    setOriginal(local)
  }

  function handleDiscard() {
    setLocal(original)
  }

  const filteredFields = fields.filter((f) => !query.trim() || f.label.toLowerCase().includes(query.trim().toLowerCase()) || f.fieldKey.toLowerCase().includes(query.trim().toLowerCase()))

  if (typesQuery.isLoading || fieldsQuery.isLoading || mappingsLoading) {
    return <LoadingState message="Loading mapping matrix…" />
  }

  if (typesQuery.isError || fieldsQuery.isError) {
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card py-16 text-center shadow-[var(--ledger-shadow)]">
        <AlertTriangle size={20} className="text-[var(--rust)]" />
        <p className="text-[13.5px] text-muted-foreground">Could not load the mapping matrix.</p>
      </div>
    )
  }

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2.5">
        <SearchInput className="min-w-[220px]" value={query} onChange={setQuery} placeholder="Search fields…" />
        <div className="flex items-center gap-2 text-[12px] text-muted-foreground">
          <LegendDot state="mandatory" /> Mandatory
          <LegendDot state="optional" /> Optional
          <LegendDot state="none" /> Not included
        </div>
      </div>

      {canUpdate && changedTypeIds.length > 0 && (
        <div className="mb-3 flex items-center justify-between rounded-btn border border-primary/30 bg-primary/5 px-3.5 py-2.5 text-[12.5px]">
          <span>{changedTypeIds.length} document type(s) have unsaved changes.</span>
          <div className="flex items-center gap-2">
            <button type="button" onClick={handleDiscard} disabled={saving} className="rounded-btn border border-[var(--border-strong)] bg-card px-3 py-1.5 text-[12.5px] font-semibold text-foreground disabled:opacity-60">
              Discard
            </button>
            <button type="button" onClick={handleSave} disabled={saving} className="rounded-btn border border-primary bg-primary px-3.5 py-1.5 text-[12.5px] font-bold text-primary-foreground disabled:opacity-60">
              {saving ? "Saving…" : "Save changes"}
            </button>
          </div>
        </div>
      )}

      <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
        <div className="overflow-x-auto">
          <table className="border-collapse">
            <thead>
              <tr className="border-b border-border">
                <th
                  style={{ width: COL1_WIDTH, minWidth: COL1_WIDTH, maxWidth: COL1_WIDTH }}
                  className="sticky left-0 z-20 bg-card px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"
                >
                  Field
                </th>
                {documentTypes.map((type) => (
                  <th key={type.documentTypeId} style={{ minWidth: 140 }} className="px-3 py-2.5 text-center text-[11px] font-semibold tracking-[0.05em] text-muted-foreground uppercase">
                    {type.typeName}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filteredFields.length === 0 && (
                <tr>
                  <td colSpan={documentTypes.length + 1} className="px-3.5 py-10 text-center text-[13px] text-muted-foreground">
                    No fields match your search.
                  </td>
                </tr>
              )}
              {filteredFields.map((field) => (
                <tr key={field.fieldId} className="border-b border-border last:border-b-0 hover:bg-muted">
                  <td
                    style={{ width: COL1_WIDTH, minWidth: COL1_WIDTH, maxWidth: COL1_WIDTH }}
                    className="sticky left-0 z-10 bg-card px-3.5 py-2.5 text-[13px] font-medium text-foreground"
                  >
                    {field.label}
                    <span className="ml-1.5 font-mono text-[11px] text-muted-foreground">{field.fieldKey}</span>
                  </td>
                  {documentTypes.map((type) => {
                    const mapping = local[type.documentTypeId] ?? new Map()
                    const state = cellState(mapping, field.fieldId)
                    return (
                      <td key={type.documentTypeId} className="px-3 py-2 text-center">
                        <button
                          type="button"
                          disabled={!canUpdate}
                          onClick={() => toggleCell(type.documentTypeId, field.fieldId)}
                          aria-label={`${field.label} for ${type.typeName}: ${state}`}
                          className={cn("mx-auto flex size-6 items-center justify-center rounded-full border transition-colors", !canUpdate && "cursor-default")}
                        >
                          <StateDot state={state} />
                        </button>
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

function StateDot({ state }: { state: CellState }) {
  if (state === "mandatory") return <span className="size-3 rounded-full bg-primary" />
  if (state === "optional") return <span className="size-3 rounded-full border-2 border-primary bg-transparent" />
  return <span className="size-3 rounded-full border border-[var(--border-strong)] bg-transparent" />
}

function LegendDot({ state }: { state: CellState }) {
  return (
    <span className="mr-1 inline-flex size-4 items-center justify-center">
      <StateDot state={state} />
    </span>
  )
}
