"use client"

import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { AlertTriangle, CheckCircle2, Loader2, ScanLine, XCircle } from "lucide-react"
import { toast } from "sonner"

import { isTerminalStatus } from "@/components/documents/document-upload-form"
import {
  defaultRequestedFields,
  FieldChips,
  mandatoryRequestedFields,
} from "@/components/documents/document-fields-form"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { fetchDocumentTypeSchema } from "@/lib/document-type-fields/api"
import { fetchDocument, fetchOcrStatus, rerunOcr, type OcrFieldStatus } from "@/lib/documents/api"

interface OcrScanDialogProps {
  documentId: string
  documentTitle: string
  documentTypeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

const STATUS_LABELS: Record<OcrFieldStatus, string> = {
  extracted: "Extracted",
  low_confidence: "Low confidence",
  not_found: "Not found",
  not_extractable: "Not applicable",
  defaulted: "Defaulted",
}

function FieldStatusBadge({ status }: { status: OcrFieldStatus }) {
  if (status === "not_extractable") return <Badge variant="muted">{STATUS_LABELS[status]}</Badge>
  if (status === "extracted" || status === "defaulted")
    return <Badge variant="gold">{STATUS_LABELS[status]}</Badge>
  return <Badge variant="rust">{STATUS_LABELS[status]}</Badge>
}

/**
 * Run (or re-run) OCR scanning on a document straight from My Documents,
 * the same entry-point shape as "Add watermark": pick the row, open a
 * sheet, act, watch it finish. The heavier per-field review (editing a
 * value, resolving a low-confidence match) stays on the review queue -
 * this is just the trigger and a status readout, not that screen.
 */
export function OcrScanDialog({
  documentId,
  documentTitle,
  documentTypeId,
  open,
  onOpenChange,
}: OcrScanDialogProps) {
  const queryClient = useQueryClient()

  const documentQuery = useQuery({
    queryKey: ["documents", documentId, "detail", "ocr-scan"],
    queryFn: () => fetchDocument(documentId),
    enabled: open,
  })

  const schemaQuery = useQuery({
    queryKey: ["document-type-schema", documentTypeId],
    queryFn: () => fetchDocumentTypeSchema(documentTypeId),
    enabled: open,
    staleTime: 5 * 60 * 1000,
  })

  const statusQuery = useQuery({
    queryKey: ["ocr-status", documentId],
    queryFn: () => fetchOcrStatus(documentId),
    enabled: open,
    refetchInterval: (query) => (isTerminalStatus(query.state.data?.status) ? false : 2000),
  })

  const labelOf = (fieldKey: string) =>
    schemaQuery.data?.fields.find((f) => f.fieldKey === fieldKey)?.label ?? fieldKey

  const running = statusQuery.data ? !isTerminalStatus(statusQuery.data.status) : false

  const status = statusQuery.data
  const fields = status ? Object.entries(status.fields) : []
  // Documents uploaded without going through the field-selection step (e.g.
  // straight into a My Documents folder) land with requested_fields = []
  // and no result at all - re-running with no selection is refused server
  // side ("choose which fields to extract"), so a picker is needed here,
  // not just a one-click re-run.
  const needsFieldPicker = fields.length === 0 && !running

  // null until the user touches a chip - until then the schema's own
  // defaults are shown selected, without needing an effect to seed state
  // once the schema query resolves (see DocumentUploadForm's fieldSelection
  // for the same shape).
  const [fieldOverride, setFieldOverride] = useState<Set<string> | null>(null)
  const selectedFields = fieldOverride ?? defaultRequestedFields(schemaQuery.data)

  function toggleField(fieldKey: string) {
    const next = new Set(selectedFields)
    if (next.has(fieldKey)) next.delete(fieldKey)
    else next.add(fieldKey)
    setFieldOverride(next)
  }

  async function handleRun() {
    const versionId = documentQuery.data?.currentVersionId
    if (!versionId) return
    try {
      await rerunOcr(versionId, needsFieldPicker ? Array.from(selectedFields) : [])
      await queryClient.invalidateQueries({ queryKey: ["ocr-status", documentId] })
      toast.success("Running OCR…")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not start OCR on this document.")
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full gap-0 data-[side=right]:sm:max-w-[480px]">
        <SheetHeader className="border-b border-border">
          <SheetTitle className="flex items-center gap-2">
            <ScanLine size={18} className="text-primary" aria-hidden="true" />
            OCR scan
          </SheetTitle>
          <SheetDescription className="truncate">{documentTitle}</SheetDescription>
        </SheetHeader>

        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-5">
          {status && (
            <div className="flex items-center gap-2.5">
              <Badge
                variant={
                  status.status === "completed"
                    ? "gold"
                    : status.status === "failed"
                      ? "rust"
                      : "muted"
                }
                className="gap-1.5"
              >
                {running ? (
                  <Loader2 className="size-3 animate-spin" aria-hidden="true" />
                ) : status.status === "completed" ? (
                  <CheckCircle2 className="size-3" aria-hidden="true" />
                ) : status.status === "failed" ? (
                  <XCircle className="size-3" aria-hidden="true" />
                ) : (
                  <span className="size-[5px] rounded-full bg-current" />
                )}
                {status.status === "queued"
                  ? "Queued"
                  : status.status === "processing"
                    ? "Processing"
                    : status.status === "completed"
                      ? "Completed"
                      : "Failed"}
              </Badge>
              {running && (
                <span className="text-[12.5px] text-muted-foreground">
                  This usually takes a second or two.
                </span>
              )}
            </div>
          )}

          {status?.status === "failed" && status.friendlyError && (
            <p
              role="alert"
              className="flex items-start gap-1.5 rounded-btn border border-[var(--rust)] bg-[var(--rust-tint)] px-3.5 py-2.5 text-[12.5px] leading-[1.5] text-[var(--rust)]"
            >
              <AlertTriangle size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
              {status.friendlyError}
            </p>
          )}

          {fields.length > 0 && (
            <dl className="flex flex-col gap-2.5 rounded-lg border border-border bg-muted/20 px-3.5 py-3">
              {fields.map(([fieldKey, result]) => (
                <div key={fieldKey} className="flex items-start justify-between gap-2.5">
                  <div className="min-w-0">
                    <dt className="text-[12.5px] font-medium text-foreground">{labelOf(fieldKey)}</dt>
                    <dd className="truncate text-[12px] text-muted-foreground">
                      {Array.isArray(result.value)
                        ? result.value.join(", ") || "—"
                        : (result.value ?? "—")}
                    </dd>
                  </div>
                  <FieldStatusBadge status={result.status} />
                </div>
              ))}
            </dl>
          )}

          {needsFieldPicker && (
            <div>
              <p className="mb-2.5 text-[12.5px] text-muted-foreground">
                No OCR result yet for this document. Choose which fields to extract, then run a scan.
              </p>
              <FieldChips
                schema={schemaQuery.data}
                loading={schemaQuery.isLoading}
                selected={selectedFields}
                onToggle={toggleField}
                onSelectAll={() =>
                  setFieldOverride(
                    new Set((schemaQuery.data?.fields ?? []).map((f) => f.fieldKey))
                  )
                }
                onClear={() => setFieldOverride(mandatoryRequestedFields(schemaQuery.data))}
              />
            </div>
          )}
        </div>

        <SheetFooter className="border-t border-border">
          <div className="flex items-center justify-end gap-2.5">
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Close
            </Button>
            <Button
              type="button"
              onClick={() => void handleRun()}
              disabled={
                running ||
                !documentQuery.data?.currentVersionId ||
                (needsFieldPicker && selectedFields.size === 0)
              }
            >
              {running && <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />}
              {status ? "Re-run OCR scan" : "Run OCR scan"}
            </Button>
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
