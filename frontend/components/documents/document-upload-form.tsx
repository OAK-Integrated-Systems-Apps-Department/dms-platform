"use client"

import { useState } from "react"
import { AlertTriangle, CheckCircle2, FileText, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { ApiError } from "@/lib/api-error"
import { runOcr, uploadDocument, type DocumentVersionResponse, type OcrResult, type OcrTable } from "@/lib/documents/api"

/**
 * Renders one Docling-recognized table (ocr-worker README's "Output
 * contract" `pages[].tables[]`) as a real HTML grid -- honoring
 * row_span/col_span so a merged cell doesn't get duplicated, and a
 * skipped (spanned-over) grid position renders as an empty cell rather
 * than a gap. `column_header`/`row_header` cells render as <th>.
 */
function OcrTableView({ table }: { table: OcrTable }) {
  const grid: (OcrTable["cells"][number] | undefined)[][] = Array.from({ length: table.num_rows }, () =>
    Array.from({ length: table.num_cols }, () => undefined)
  )
  const covered = Array.from({ length: table.num_rows }, () => Array<boolean>(table.num_cols).fill(false))

  for (const cell of table.cells) {
    if (cell.row >= table.num_rows || cell.col >= table.num_cols) continue
    grid[cell.row][cell.col] = cell
    for (let r = cell.row; r < Math.min(cell.row + cell.row_span, table.num_rows); r++) {
      for (let c = cell.col; c < Math.min(cell.col + cell.col_span, table.num_cols); c++) {
        if (r === cell.row && c === cell.col) continue
        covered[r][c] = true
      }
    }
  }

  return (
    <div className="overflow-x-auto rounded-md border border-border">
      <table className="w-full border-collapse text-[12.5px]">
        <tbody>
          {grid.map((row, r) => (
            <tr key={r}>
              {row.map((cell, c) => {
                if (covered[r][c]) return null
                if (!cell) return <td key={c} className="border border-border px-2.5 py-1.5" />
                const Tag = cell.column_header || cell.row_header ? "th" : "td"
                return (
                  <Tag
                    key={c}
                    rowSpan={cell.row_span > 1 ? cell.row_span : undefined}
                    colSpan={cell.col_span > 1 ? cell.col_span : undefined}
                    className={
                      Tag === "th"
                        ? "border border-border bg-muted px-2.5 py-1.5 text-left font-semibold text-foreground"
                        : "border border-border px-2.5 py-1.5 text-left align-top text-foreground"
                    }
                  >
                    {cell.text}
                  </Tag>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * Upload a file, then run OCR on it via ocr-worker, and show the real
 * result JSON -- the frontend half of the dms-core passthrough route
 * (com.oak.dms.document.DocumentController) that connects the Next.js app
 * to backend/workers/ocr-worker. See that service's README "Output
 * contract" for the exact shape of `ocrResult` below: `pages[]` (each with
 * `method: "native_text" | "paddleocr"`, real detected text/blocks),
 * `fields`, and `flags.low_confidence`/`requires_review`.
 *
 * Two separate calls, not one, mirroring the two backend endpoints: upload
 * first creates the document/version (and lands the file in MinIO);
 * "Run OCR" is a deliberately separate action so a user can upload now and
 * trigger (or re-trigger) OCR later, matching ocr-worker's own dev-trigger
 * endpoint being a distinct, repeatable operation.
 */
export function DocumentUploadForm() {
  const [file, setFile] = useState<File | null>(null)
  const [documentType, setDocumentType] = useState("general")
  const [uploading, setUploading] = useState(false)
  const [runningOcr, setRunningOcr] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [ocrError, setOcrError] = useState<string | null>(null)
  const [version, setVersion] = useState<DocumentVersionResponse | null>(null)
  const [ocrResult, setOcrResult] = useState<OcrResult | null>(null)

  async function handleUpload() {
    if (!file) return
    setUploadError(null)
    setUploading(true)
    try {
      const response = await uploadDocument(file, documentType.trim() || "general")
      setVersion(response)
      setOcrResult(null)
      toast.success("Document uploaded.")
    } catch (err) {
      const message = err instanceof Error ? err.message : "Could not upload the document."
      setUploadError(message)
      toast.error(message)
    } finally {
      setUploading(false)
    }
  }

  async function handleRunOcr() {
    if (!version) return
    setOcrError(null)
    setRunningOcr(true)
    try {
      const result = await runOcr(version.versionId)
      setOcrResult(result)
      toast.success("OCR complete.")
    } catch (err) {
      const message =
        err instanceof ApiError && err.status === 502
          ? "ocr-worker could not process this document. See the server logs."
          : err instanceof Error
            ? err.message
            : "Could not run OCR on this document."
      setOcrError(message)
      toast.error(message)
    } finally {
      setRunningOcr(false)
    }
  }

  const flags = ocrResult?.flags
  const tables = ocrResult?.pages.flatMap((page) => page.tables.map((table) => ({ page: page.page_number, table }))) ?? []

  return (
    <div className="flex flex-col gap-5">
      <div className="rounded-lg border border-border bg-card p-[30px] pb-[26px]">
        {uploadError && (
          <div className="mb-5 flex items-start gap-2.5 rounded-md border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[var(--rust-foreground)]">
            <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
            {uploadError}
          </div>
        )}

        <div className="mb-5 grid grid-cols-2 gap-[18px]">
          <div>
            <Label htmlFor="file">File</Label>
            <Input
              id="file"
              type="file"
              accept=".pdf,.png,.jpg,.jpeg,.tiff"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
            <p className="mt-1.5 text-[12px] text-muted-foreground">PDF, PNG, JPEG, or TIFF.</p>
          </div>
          <div>
            <Label htmlFor="documentType">Document type</Label>
            <Input
              id="documentType"
              placeholder="invoice"
              autoComplete="off"
              value={documentType}
              onChange={(e) => setDocumentType(e.target.value)}
            />
            <p className="mt-1.5 text-[12px] text-muted-foreground">
              Determines which zone/field template ocr-worker applies, if any.
            </p>
          </div>
        </div>

        <hr className="my-5 border-t border-dashed border-border" />

        <div className="flex justify-end gap-2.5">
          <button
            type="button"
            disabled={!file || uploading}
            onClick={handleUpload}
            className="flex items-center gap-2 rounded-btn border border-[var(--primary-hover)] bg-primary px-[22px] py-2.5 text-[13px] font-bold tracking-[0.03em] text-primary-foreground disabled:cursor-not-allowed disabled:opacity-70"
          >
            {uploading && <Loader2 size={14} className="animate-spin" />}
            {uploading ? "Uploading…" : "Upload"}
          </button>
        </div>
      </div>

      {version && (
        <div className="rounded-lg border border-border bg-card p-[30px] pb-[26px]">
          <div className="mb-4 flex items-center gap-2.5">
            <FileText size={18} className="text-primary" />
            <div>
              <p className="text-[14px] font-semibold text-foreground">{version.originalFilename}</p>
              <p className="text-[12px] text-muted-foreground">
                {version.mimeType} · {version.sizeBytes.toLocaleString()} bytes · scan: {version.scanStatus}
              </p>
            </div>
          </div>

          {ocrError && (
            <div className="mb-4 flex items-start gap-2.5 rounded-md border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[var(--rust-foreground)]">
              <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
              {ocrError}
            </div>
          )}

          <div className="flex justify-end">
            <button
              type="button"
              disabled={runningOcr}
              onClick={handleRunOcr}
              className="flex items-center gap-2 rounded-btn border border-[var(--border-strong)] px-5 py-2.5 text-[13px] font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-70"
            >
              {runningOcr && <Loader2 size={14} className="animate-spin" />}
              {runningOcr ? "Running OCR…" : ocrResult ? "Run OCR again" : "Run OCR"}
            </button>
          </div>
        </div>
      )}

      {ocrResult && (
        <div className="rounded-lg border border-border bg-card p-[30px] pb-[26px]">
          <div className="mb-4 flex items-center gap-2.5">
            <CheckCircle2 size={18} className="text-primary" />
            <p className="text-[14px] font-semibold text-foreground">OCR result</p>
            {flags?.requires_review && (
              <span className="rounded-full border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-2.5 py-0.5 text-[11px] font-semibold text-[var(--rust-foreground)]">
                Flagged for review
              </span>
            )}
          </div>
          <p className="mb-3 text-[13px] leading-[1.6] whitespace-pre-wrap text-foreground">
            {String(ocrResult.text ?? "")}
          </p>

          {tables.length > 0 && (
            <div className="mb-4 flex flex-col gap-3">
              <p className="text-[12.5px] font-semibold text-foreground">
                Detected {tables.length === 1 ? "table" : `tables (${tables.length})`}
              </p>
              {tables.map(({ page, table }, i) => (
                <div key={i} className="flex flex-col gap-1">
                  {tables.length > 1 && <p className="text-[11.5px] text-muted-foreground">Page {page}</p>}
                  <OcrTableView table={table} />
                </div>
              ))}
            </div>
          )}

          <details className="text-[12.5px] text-muted-foreground">
            <summary className="cursor-pointer select-none font-semibold text-foreground">
              Full structured output
            </summary>
            <pre className="mt-2 max-h-[400px] overflow-auto rounded-md bg-muted p-3 text-[11.5px] leading-[1.5]">
              {JSON.stringify(ocrResult, null, 2)}
            </pre>
          </details>
        </div>
      )}
    </div>
  )
}
