"use client"

import { useState } from "react"
import Link from "next/link"
import { ArchiveX, Pencil, ShieldCheck, ToggleLeft, ToggleRight } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { SortableTh } from "@/components/ui/sortable-th"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { useSortableTable } from "@/hooks/use-sortable-table"
import { cn } from "@/lib/utils"

import type { MetadataFieldResponseDto } from "@/lib/metadata-fields/api"
import { DATA_TYPE_LABELS, type DataType } from "./types"
import { FieldDeprecateDialog } from "./field-deprecate-dialog"

type SortKey = "label" | "fieldKey" | "dataType" | "status"

const STATUS_VARIANT: Record<MetadataFieldResponseDto["status"], "default" | "muted" | "gold"> = {
  active: "default",
  inactive: "muted",
  deprecated: "gold",
}

interface MetadataFieldsTableProps {
  fields: MetadataFieldResponseDto[]
  onToggleStatus: (field: MetadataFieldResponseDto) => void
  onDeprecate: (field: MetadataFieldResponseDto) => Promise<string | null>
  canUpdate: boolean
  canActivateDeactivate: boolean
  canDeprecate: boolean
}

/** Metadata Fields list table — key / label / data type / status / actions. */
export function MetadataFieldsTable({
  fields,
  onToggleStatus,
  onDeprecate,
  canUpdate,
  canActivateDeactivate,
  canDeprecate,
}: MetadataFieldsTableProps) {
  const { sorted, sortKey, direction, toggleSort } = useSortableTable<MetadataFieldResponseDto, SortKey>(fields, {
    label: (f) => f.label,
    fieldKey: (f) => f.fieldKey,
    dataType: (f) => f.dataType,
    status: (f) => f.status,
  })

  const [pendingDeprecate, setPendingDeprecate] = useState<MetadataFieldResponseDto | null>(null)

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[820px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <SortableTh label="Label" active={sortKey === "label"} direction={direction} onClick={() => toggleSort("label")} className="px-3.5" />
              <SortableTh label="Field Key" active={sortKey === "fieldKey"} direction={direction} onClick={() => toggleSort("fieldKey")} className="px-3.5" />
              <SortableTh label="Data Type" active={sortKey === "dataType"} direction={direction} onClick={() => toggleSort("dataType")} className="px-3.5" />
              <SortableTh label="Status" active={sortKey === "status"} direction={direction} onClick={() => toggleSort("status")} className="px-3.5" />
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">Actions</th>
            </tr>
          </thead>
          <tbody>
            {fields.length === 0 && (
              <tr>
                <td colSpan={5} className="px-3.5 py-10 text-center text-[13px] text-muted-foreground">
                  No metadata fields match your search.
                </td>
              </tr>
            )}
            {sorted.map((field) => (
              <tr key={field.fieldId} className="border-b border-border last:border-b-0 hover:bg-muted">
                <td className="px-3.5 py-3 text-[13.5px] font-semibold text-primary">{field.label}</td>
                <td className="px-3.5 py-3 font-mono text-[12.5px] text-muted-foreground">{field.fieldKey}</td>
                <td className="px-3.5 py-3 text-[13px] text-foreground">
                  {DATA_TYPE_LABELS[field.dataType as DataType] ?? field.dataType}
                </td>
                <td className="px-3.5 py-3">
                  <Badge variant={STATUS_VARIANT[field.status]} className="gap-1.5 rounded-md">
                    <span className="size-[5px] rounded-full bg-current" />
                    {field.status}
                  </Badge>
                </td>
                <td className="px-3.5 py-3">
                  <div className="flex items-center gap-1">
                    {canUpdate && (
                      <Tooltip>
                        <TooltipTrigger
                          render={(props) => (
                            <Link
                              {...props}
                              href={`/admin/metadata/fields/${field.fieldId}/edit`}
                              aria-label={`Edit ${field.label}`}
                              className="flex size-7 items-center justify-center rounded-sm text-primary transition-colors hover:bg-muted"
                            >
                              <Pencil size={15} />
                            </Link>
                          )}
                        />
                        <TooltipContent>Edit field</TooltipContent>
                      </Tooltip>
                    )}

                    {field.systemField ? (
                      <Tooltip>
                        <TooltipTrigger
                          render={(props) => (
                            <span {...props} className="inline-flex items-center gap-1.5 text-[12px] text-muted-foreground">
                              <ShieldCheck size={13} />
                              System field
                            </span>
                          )}
                        />
                        <TooltipContent>System fields can&apos;t be deactivated or deprecated</TooltipContent>
                      </Tooltip>
                    ) : (
                      <>
                        {canActivateDeactivate && field.status !== "deprecated" && (
                          <Tooltip>
                            <TooltipTrigger
                              render={(props) => (
                                <button
                                  {...props}
                                  type="button"
                                  onClick={() => onToggleStatus(field)}
                                  aria-label={field.status === "active" ? `Deactivate ${field.label}` : `Activate ${field.label}`}
                                  className={cn(
                                    "flex size-7 items-center justify-center rounded-sm transition-colors",
                                    field.status === "active"
                                      ? "text-[var(--rust)] hover:bg-[var(--rust-tint)]"
                                      : "text-primary hover:bg-muted"
                                  )}
                                >
                                  {field.status === "active" ? <ToggleRight size={15} /> : <ToggleLeft size={15} />}
                                </button>
                              )}
                            />
                            <TooltipContent>{field.status === "active" ? "Deactivate" : "Activate"}</TooltipContent>
                          </Tooltip>
                        )}

                        {canDeprecate && field.status !== "deprecated" && (
                          <Tooltip>
                            <TooltipTrigger
                              render={(props) => (
                                <button
                                  {...props}
                                  type="button"
                                  onClick={() => setPendingDeprecate(field)}
                                  aria-label={`Deprecate ${field.label}`}
                                  className="flex size-7 items-center justify-center rounded-sm text-[var(--gold-foreground)] transition-colors hover:bg-[var(--gold-tint)]"
                                >
                                  <ArchiveX size={15} />
                                </button>
                              )}
                            />
                            <TooltipContent>Deprecate</TooltipContent>
                          </Tooltip>
                        )}
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {pendingDeprecate && (
        <FieldDeprecateDialog
          field={pendingDeprecate}
          open={pendingDeprecate !== null}
          onOpenChange={(open) => {
            if (!open) setPendingDeprecate(null)
          }}
          onConfirm={() => onDeprecate(pendingDeprecate)}
        />
      )}
    </div>
  )
}
