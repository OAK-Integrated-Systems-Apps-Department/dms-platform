"use client"

import { Lock, Pencil, Trash2 } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { IconButton } from "@/components/ui/icon-button"
import { Switch } from "@/components/ui/switch"

import type { RelationshipTypeDto } from "@/lib/relationships/types"

interface RelationshipTypesTableProps {
  rows: RelationshipTypeDto[]
  canUpdate: boolean
  canDelete: boolean
  onEdit: (type: RelationshipTypeDto) => void
  onToggleEnabled: (type: RelationshipTypeDto) => void
  onDelete: (type: RelationshipTypeDto) => void
}

const TH =
  "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/**
 * The tenant's relationship types. Built-in ones carry a badge and no delete
 * button (they can be renamed and switched off, never deleted); a custom type
 * can be deleted only while nothing has ever used it - the server decides, the
 * button is shown only while it has no active links and says why otherwise.
 */
export function RelationshipTypesTable({
  rows,
  canUpdate,
  canDelete,
  onEdit,
  onToggleEnabled,
  onDelete,
}: RelationshipTypesTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Type</th>
              <th className={TH}>Reads as</th>
              <th className={TH}>Direction</th>
              <th className={TH}>Links</th>
              <th className={TH}>Enabled</th>
              <th className={TH}>
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-3 py-10 text-center text-[13px] text-muted-foreground">
                  No relationship types yet.
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr key={row.relationshipTypeId} className="border-b border-border align-top last:border-b-0">
                <td className="px-3 py-3">
                  <div className="flex items-center gap-1.5">
                    <span className="text-[13.5px] font-semibold text-foreground">{row.label}</span>
                    {row.system && (
                      <Badge variant="muted" className="gap-1 text-[10.5px] tracking-[0.04em] uppercase">
                        <Lock size={10} aria-hidden="true" />
                        System
                      </Badge>
                    )}
                  </div>
                  <p className="font-mono text-[11.5px] text-muted-foreground">{row.code}</p>
                </td>
                <td className="px-3 py-3 text-[13px] text-muted-foreground">
                  {row.directionality === "symmetric"
                    ? row.label
                    : `${row.label} / ${row.inverseLabel}`}
                </td>
                <td className="px-3 py-3 text-[12.5px] text-muted-foreground">
                  {row.directionality === "symmetric" ? "Two-way" : "One-way"}
                  {row.preventsCycles && " · prevents loops"}
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">{row.usageCount}</td>
                <td className="px-3 py-3">
                  <Switch
                    size="sm"
                    checked={row.enabled}
                    disabled={!canUpdate}
                    onCheckedChange={() => onToggleEnabled(row)}
                    aria-label={`${row.enabled ? "Disable" : "Enable"} ${row.label}`}
                  />
                </td>
                <td className="px-3 py-3">
                  <div className="flex items-center justify-end gap-1">
                    {canUpdate && (
                      <IconButton aria-label={`Edit ${row.label}`} title={`Edit ${row.label}`} onClick={() => onEdit(row)}>
                        <Pencil size={14} />
                      </IconButton>
                    )}
                    {canDelete && !row.system && (
                      <IconButton
                        aria-label={`Delete ${row.label}`}
                        title={
                          row.usageCount > 0
                            ? `${row.label} is in use - disable it instead`
                            : `Delete ${row.label}`
                        }
                        disabled={row.usageCount > 0}
                        className="disabled:opacity-40"
                        onClick={() => onDelete(row)}
                      >
                        <Trash2 size={14} />
                      </IconButton>
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
