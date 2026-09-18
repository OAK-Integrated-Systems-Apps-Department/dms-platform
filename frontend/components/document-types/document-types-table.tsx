"use client"

import Link from "next/link"
import { Eye, Pencil, ToggleLeft, ToggleRight } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { SortableTh } from "@/components/ui/sortable-th"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { useSortableTable } from "@/hooks/use-sortable-table"
import { cn } from "@/lib/utils"

import type { DocumentTypeResponseDto } from "@/lib/document-types/api"

type SortKey = "typeName" | "typeCode" | "currentSchemaVersion" | "status"

interface DocumentTypesTableProps {
  documentTypes: DocumentTypeResponseDto[]
  onToggleStatus: (documentType: DocumentTypeResponseDto) => void
  canUpdate: boolean
  canActivateDeactivate: boolean
}

/** Document Types list table — name / code / schema version / status / actions. */
export function DocumentTypesTable({ documentTypes, onToggleStatus, canUpdate, canActivateDeactivate }: DocumentTypesTableProps) {
  const { sorted, sortKey, direction, toggleSort } = useSortableTable<DocumentTypeResponseDto, SortKey>(documentTypes, {
    typeName: (t) => t.typeName,
    typeCode: (t) => t.typeCode,
    currentSchemaVersion: (t) => t.currentSchemaVersion,
    status: (t) => t.status,
  })

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[780px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <SortableTh label="Name" active={sortKey === "typeName"} direction={direction} onClick={() => toggleSort("typeName")} className="px-3.5" />
              <SortableTh label="Code" active={sortKey === "typeCode"} direction={direction} onClick={() => toggleSort("typeCode")} className="px-3.5" />
              <SortableTh label="Schema Version" active={sortKey === "currentSchemaVersion"} direction={direction} onClick={() => toggleSort("currentSchemaVersion")} className="px-3.5" />
              <SortableTh label="Status" active={sortKey === "status"} direction={direction} onClick={() => toggleSort("status")} className="px-3.5" />
              <th className="px-3.5 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">Actions</th>
            </tr>
          </thead>
          <tbody>
            {documentTypes.length === 0 && (
              <tr>
                <td colSpan={5} className="px-3.5 py-10 text-center text-[13px] text-muted-foreground">
                  No document types match your search.
                </td>
              </tr>
            )}
            {sorted.map((type) => (
              <tr key={type.documentTypeId} className="border-b border-border last:border-b-0 hover:bg-muted">
                <td className="px-3.5 py-3 text-[13.5px] font-semibold text-primary">{type.typeName}</td>
                <td className="px-3.5 py-3 font-mono text-[12.5px] text-muted-foreground">{type.typeCode}</td>
                <td className="px-3.5 py-3 font-mono text-[12.5px] text-muted-foreground">v{type.currentSchemaVersion}</td>
                <td className="px-3.5 py-3">
                  <Badge variant={type.status === "active" ? "default" : "muted"} className="gap-1.5 rounded-md">
                    <span className="size-[5px] rounded-full bg-current" />
                    {type.status}
                  </Badge>
                </td>
                <td className="px-3.5 py-3">
                  <div className="flex items-center gap-1">
                    <Tooltip>
                      <TooltipTrigger render={(props) => (
                        <Link {...props} href={`/admin/metadata/document-types/${type.documentTypeId}`} aria-label={`View ${type.typeName}`} className="flex size-7 items-center justify-center rounded-sm text-primary transition-colors hover:bg-muted">
                          <Eye size={15} />
                        </Link>
                      )} />
                      <TooltipContent>View</TooltipContent>
                    </Tooltip>

                    {canUpdate && (
                      <Tooltip>
                        <TooltipTrigger render={(props) => (
                          <Link {...props} href={`/admin/metadata/document-types/${type.documentTypeId}/edit`} aria-label={`Edit ${type.typeName}`} className="flex size-7 items-center justify-center rounded-sm text-primary transition-colors hover:bg-muted">
                            <Pencil size={15} />
                          </Link>
                        )} />
                        <TooltipContent>Edit</TooltipContent>
                      </Tooltip>
                    )}

                    {canActivateDeactivate && (
                      <Tooltip>
                        <TooltipTrigger render={(props) => (
                          <button
                            {...props}
                            type="button"
                            onClick={() => onToggleStatus(type)}
                            aria-label={type.status === "active" ? `Deactivate ${type.typeName}` : `Activate ${type.typeName}`}
                            className={cn(
                              "flex size-7 items-center justify-center rounded-sm transition-colors",
                              type.status === "active" ? "text-[var(--rust)] hover:bg-[var(--rust-tint)]" : "text-primary hover:bg-muted"
                            )}
                          >
                            {type.status === "active" ? <ToggleRight size={15} /> : <ToggleLeft size={15} />}
                          </button>
                        )} />
                        <TooltipContent>{type.status === "active" ? "Deactivate" : "Activate"}</TooltipContent>
                      </Tooltip>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
