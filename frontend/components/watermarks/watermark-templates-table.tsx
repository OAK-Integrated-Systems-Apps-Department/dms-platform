"use client"

import { Lock, Pencil, Power, Trash2 } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { IconButton } from "@/components/ui/icon-button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { formatDateTime } from "@/lib/utils"

import type { WatermarkTemplateDto } from "@/lib/watermarks/api"

interface WatermarkTemplatesTableProps {
  rows: WatermarkTemplateDto[]
  canEdit: boolean
  onEdit: (template: WatermarkTemplateDto) => void
  onToggleActive: (template: WatermarkTemplateDto) => void
  onDelete: (template: WatermarkTemplateDto) => void
}

const TH =
  "px-3 py-2.5 text-left text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/**
 * The tenant's templates and the four built-in ones, in one list.
 *
 * Built-in rows carry a "Built-in" badge and no action buttons at all,
 * rather than disabled ones: they are not something this tenant could edit
 * with more permission, so offering a greyed-out control would imply a
 * missing grant rather than a fixed rule. The backend answers 403 either
 * way, which is what actually enforces it.
 *
 * Actions are omitted entirely for a user without
 * `dw.configure-watermark-policy.update-template` - they can still SEE the
 * templates (choosing one is part of watermarking a document) but not
 * change them.
 */
export function WatermarkTemplatesTable({
  rows,
  canEdit,
  onEdit,
  onToggleActive,
  onDelete,
}: WatermarkTemplatesTableProps) {
  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card shadow-[var(--ledger-shadow)]">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] border-collapse">
          <thead>
            <tr className="border-b border-border">
              <th className={TH}>Name</th>
              <th className={TH}>Watermark text</th>
              <th className={TH}>Style</th>
              <th className={TH}>Status</th>
              <th className={TH}>Updated</th>
              <th className={TH}>
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-3 py-10 text-center text-[13px] text-muted-foreground">
                  No watermark templates yet.
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr key={row.templateId} className="border-b border-border align-top last:border-b-0">
                <td className="px-3 py-3">
                  <div className="flex items-center gap-1.5">
                    <span className="text-[13.5px] font-semibold text-foreground">{row.label}</span>
                    {row.systemTemplate && (
                      <Tooltip>
                        <TooltipTrigger
                          render={(props) => (
                            <span
                              {...props}
                              className="inline-flex items-center gap-1 rounded-md border border-border px-1.5 py-0.5 text-[10.5px] font-semibold tracking-[0.04em] text-muted-foreground uppercase"
                            >
                              <Lock size={10} aria-hidden="true" />
                              Built-in
                            </span>
                          )}
                        />
                        <TooltipContent>
                          Provided with the product and shared by every workspace — it can&apos;t be
                          edited or deleted.
                        </TooltipContent>
                      </Tooltip>
                    )}
                  </div>
                </td>
                <td className="px-3 py-3">
                  <p className="max-w-[260px] break-words font-mono text-[12.5px] text-muted-foreground">
                    {row.defaultText}
                  </p>
                </td>
                <td className="px-3 py-3 text-[12px] text-muted-foreground">
                  {describeStyle(row)}
                </td>
                <td className="px-3 py-3">
                  <Badge variant={row.active ? "default" : "outline"} className="gap-1.5 rounded-md">
                    <span className="size-[5px] rounded-full bg-current" />
                    {row.active ? "Active" : "Retired"}
                  </Badge>
                </td>
                <td className="px-3 py-3 font-mono text-[12.5px] text-muted-foreground">
                  {formatDateTime(row.updatedAt)}
                </td>
                <td className="px-3 py-3">
                  {canEdit && row.editable && (
                    <div className="flex items-center justify-end gap-1">
                      <IconButton
                        aria-label={`Edit ${row.label}`}
                        title={`Edit ${row.label}`}
                        onClick={() => onEdit(row)}
                      >
                        <Pencil size={14} />
                      </IconButton>
                      <IconButton
                        aria-label={`${row.active ? "Retire" : "Reactivate"} ${row.label}`}
                        title={`${row.active ? "Retire" : "Reactivate"} ${row.label}`}
                        onClick={() => onToggleActive(row)}
                      >
                        <Power size={14} />
                      </IconButton>
                      <IconButton
                        aria-label={`Delete ${row.label}`}
                        title={`Delete ${row.label}`}
                        onClick={() => onDelete(row)}
                      >
                        <Trash2 size={14} />
                      </IconButton>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/** "Default" when a template overrides nothing - most of them don't. */
function describeStyle(template: WatermarkTemplateDto): string {
  const parts: string[] = []
  if (template.placement) parts.push(template.placement)
  if (template.pageScope === "first-page-only") parts.push("first page only")
  if (template.fontSize !== null) parts.push(`${template.fontSize}pt`)
  if (template.opacity !== null) parts.push(`${template.opacity}%`)
  if (template.rotation !== null) parts.push(`${template.rotation}°`)
  if (template.color) parts.push(template.color)
  return parts.length > 0 ? parts.join(" · ") : "System default"
}
