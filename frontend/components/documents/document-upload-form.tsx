"use client"

import { useEffect, useRef, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { AlertTriangle, CheckCircle2, ChevronDown, ChevronRight, FileText, Loader2, RefreshCw, XCircle } from "lucide-react"
import { toast } from "sonner"

import {
  DocumentFieldsForm,
  defaultRequestedFields,
  hasMissingMandatoryField,
  mandatoryRequestedFields,
  reconcileRequestedFields,
  visibleFieldsOf,
  FieldChips,
} from "@/components/documents/document-fields-form"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { ApiError } from "@/lib/api-error"
import { fetchDocumentTypeSchema } from "@/lib/document-type-fields/api"
import { fetchAllDocumentTypes } from "@/lib/document-types/api"
import {
  fetchDocumentMetadata,
  fetchOcrStatus,
  rerunOcr,
  saveDocumentMetadata,
  uploadDocument,
  type DocumentVersionResponse,
  type MetadataValues,
  type OcrStatusResponse,
} from "@/lib/documents/api"

type UploadPhase = "idle" | "uploading" | "processing" | "done" | "failed"

function isTerminalStatus(status: OcrStatusResponse["status"] | undefined): boolean {
  return status === "completed" || status === "failed"
}

function phaseLabel(phase: UploadPhase): string {
  switch (phase) {
    case "uploading":
      return "Uploading…"
    case "processing":
      return "Processing OCR…"
    case "done":
      return "Done"
    case "failed":
      return "Failed"
    default:
      return ""
  }
}

/**
 * Upload a file, choose which fields OCR should try to extract (the chip
 * picker in `FieldChips`), then poll the async OCR/field-extraction job
 * (`fetchOcrStatus`) until it lands on `"completed"`/`"failed"` -- this
 * replaced the old synchronous "Run OCR" button, which used to call
 * `POST /documents/{versionId}/ocr` and block on the full ocr-worker JSON.
 * That endpoint is now fire-and-forget (used here for both the initial
 * post-upload run and any later per-field/all-selected re-run), and the
 * frontend's job is only to reflect `document_versions.ocr_status` plus
 * the per-field results dms-core exposes via `GET .../ocr-status`, not to
 * render ocr-worker's raw page/table output anymore.
 */
export function DocumentUploadForm() {
  const queryClient = useQueryClient()
  const documentTypesQuery = useQuery({ queryKey: ["document-types"], queryFn: fetchAllDocumentTypes, retry: false })
  const activeDocumentTypes = (documentTypesQuery.data ?? []).filter((t) => t.status === "active")

  const [file, setFile] = useState<File | null>(null)
  const [documentType, setDocumentType] = useState("")
  const [selectedFields, setSelectedFields] = useState<Set<string>>(new Set())
  const [fieldValues, setFieldValues] = useState<MetadataValues>({})
  const [needsReview, setNeedsReview] = useState<Set<string>>(new Set())
  const [uploading, setUploading] = useState(false)
  const [savingFields, setSavingFields] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [version, setVersion] = useState<DocumentVersionResponse | null>(null)
  const [showRawData, setShowRawData] = useState(false)

  // Field keys the user has hand-edited since the last OCR result landed --
  // never silently overwritten by a later poll tick or re-run result (only
  // an explicit "Save fields" persists over them, matching the backend's
  // own manual-override rule in DocumentMetadataServiceImpl#applyOcrFields).
  const userTouchedFields = useRef<Set<string>>(new Set())
  // Which terminal ocr-status result (documentId + status) has already been
  // merged into fieldValues -- a poll tick re-delivering the same
  // "completed" result must not re-run the merge (and re-clobber an
  // in-progress edit) on every refetch.
  const appliedResultKey = useRef<string | null>(null)

  // Defaults to GENERAL once the real catalog loads (falling back to
  // whichever active type comes first if GENERAL isn't configured for
  // this tenant) -- only when the user hasn't already picked something.
  useEffect(() => {
    if (documentType || activeDocumentTypes.length === 0) return
    const preferred = activeDocumentTypes.find((t) => t.typeCode === "GENERAL") ?? activeDocumentTypes[0]
    setDocumentType(preferred.typeCode)
  }, [activeDocumentTypes, documentType])

  const selectedType = activeDocumentTypes.find((t) => t.typeCode === documentType)
  const schemaQuery = useQuery({
    queryKey: ["document-type-schema", selectedType?.documentTypeId],
    queryFn: () => fetchDocumentTypeSchema(selectedType!.documentTypeId),
    enabled: !!selectedType,
    retry: false,
  })

  // Clears any leftover field values when the user switches document type
  // before uploading -- once a version exists, the type is locked to
  // whatever was actually uploaded, so this no longer applies. The file
  // itself is untouched by a document-type change either way.
  useEffect(() => {
    if (!version) setFieldValues({})
  }, [documentType, version])

  // Resets the OCR field selection to the new type's defaults whenever its
  // schema (re)loads, keeping any previously-selected key that's still
  // valid on the new type -- see reconcileRequestedFields. Only applies
  // pre-upload; once a version exists the selection is locked in with it.
  useEffect(() => {
    if (version) return
    setSelectedFields((prev) => (prev.size === 0 ? defaultRequestedFields(schemaQuery.data) : reconcileRequestedFields(prev, schemaQuery.data)))
  }, [schemaQuery.data, version])

  const ocrStatusQuery = useQuery({
    queryKey: ["ocr-status", version?.documentId],
    queryFn: () => fetchOcrStatus(version!.documentId),
    enabled: !!version,
    refetchInterval: (query) => (isTerminalStatus(query.state.data?.status) ? false : 2000),
  })

  const phase: UploadPhase = uploading
    ? "uploading"
    : !version
      ? "idle"
      : ocrStatusQuery.data?.status === "completed"
        ? "done"
        : ocrStatusQuery.data?.status === "failed"
          ? "failed"
          : "processing"

  // Merges the saved metadata state into the form exactly once per terminal
  // OCR result, skipping any field the user has already hand-edited since.
  useEffect(() => {
    if (!version || !ocrStatusQuery.data || !isTerminalStatus(ocrStatusQuery.data.status)) return
    const key = `${version.documentId}:${ocrStatusQuery.data.status}`
    if (appliedResultKey.current === key) return
    appliedResultKey.current = key

    fetchDocumentMetadata(version.documentId)
      .then((saved) => {
        setFieldValues((prev) => {
          const next = { ...prev }
          for (const [fieldKey, value] of Object.entries(saved.values)) {
            if (!userTouchedFields.current.has(fieldKey)) next[fieldKey] = value
          }
          return next
        })
        setNeedsReview(new Set(saved.needsReview))
      })
      .catch(() => {
        // Best-effort, same as the old post-OCR refetch: the OCR result
        // itself already landed; the user can still see/edit whatever
        // was already local.
      })
  }, [version, ocrStatusQuery.data])

  async function handleUpload() {
    if (!file || !documentType) return
    setUploadError(null)
    setUploading(true)
    try {
      const response = await uploadDocument(file, documentType, Array.from(selectedFields))
      setVersion(response)
      appliedResultKey.current = null
      userTouchedFields.current = new Set()
      toast.success("Document uploaded. Running OCR…")
    } catch (err) {
      const message = err instanceof Error ? err.message : "Could not upload the document."
      setUploadError(message)
      toast.error(message)
    } finally {
      setUploading(false)
    }
  }

  async function handleRerun(fields: string[]) {
    if (!version || fields.length === 0) return
    try {
      await rerunOcr(version.versionId, fields)
      appliedResultKey.current = null
      await queryClient.invalidateQueries({ queryKey: ["ocr-status", version.documentId] })
      toast.success("Re-running OCR…")
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Could not re-run OCR on this document.")
    }
  }

  async function handleSaveFields() {
    if (!version) return
    setSavingFields(true)
    try {
      const saved = await saveDocumentMetadata(version.documentId, fieldValues)
      setFieldValues(saved.values)
      setNeedsReview(new Set(saved.needsReview))
      toast.success("Fields saved.")
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Could not save these field values.")
    } finally {
      setSavingFields(false)
    }
  }

  function handleFieldChange(fieldKey: string, value: string | string[]) {
    userTouchedFields.current.add(fieldKey)
    setFieldValues((prev) => ({ ...prev, [fieldKey]: value }))
  }

  function handleToggleField(fieldKey: string) {
    setSelectedFields((prev) => {
      const next = new Set(prev)
      if (next.has(fieldKey)) next.delete(fieldKey)
      else next.add(fieldKey)
      return next
    })
  }

  const busy = uploading || phase === "processing"
  const ocrResultsByFieldKey = ocrStatusQuery.data?.fields

  return (
    <div className="flex flex-col gap-5">
      <div className="flex w-full flex-col gap-5">
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
              <select
                id="documentType"
                value={documentType}
                onChange={(e) => setDocumentType(e.target.value)}
                disabled={documentTypesQuery.isLoading || activeDocumentTypes.length === 0 || !!version}
                className="h-10 w-full appearance-none rounded-btn border border-[var(--border-strong)] bg-muted px-3 text-[13.5px] text-foreground outline-none focus-visible:border-primary focus-visible:bg-card disabled:opacity-70"
              >
                {documentTypesQuery.isLoading && <option value="">Loading…</option>}
                {!documentTypesQuery.isLoading && activeDocumentTypes.length === 0 && (
                  <option value="">No document types available</option>
                )}
                {activeDocumentTypes.map((t) => (
                  <option key={t.documentTypeId} value={t.typeCode}>
                    {t.typeName}
                  </option>
                ))}
              </select>
              <p className="mt-1.5 text-[12px] text-muted-foreground">
                {documentTypesQuery.isError
                  ? "Could not load document types."
                  : "Determines which zone/field template ocr-worker applies, if any."}
              </p>
            </div>
          </div>

          {!version && (
            <FieldChips
              schema={schemaQuery.data}
              loading={schemaQuery.isLoading}
              selected={selectedFields}
              onToggle={handleToggleField}
              onSelectAll={() => setSelectedFields(new Set(visibleFieldsOf(schemaQuery.data).map((f) => f.fieldKey)))}
              onClear={() => setSelectedFields(mandatoryRequestedFields(schemaQuery.data))}
            />
          )}

          <hr className="my-5 border-t border-dashed border-border" />

          <div className="flex items-center justify-end gap-2.5">
            {phase !== "idle" && (
              <span className="flex items-center gap-1.5 text-[12.5px] font-medium text-muted-foreground">
                {(phase === "uploading" || phase === "processing") && <Loader2 size={14} className="animate-spin" />}
                {phase === "done" && <CheckCircle2 size={14} className="text-primary" />}
                {phase === "failed" && <XCircle size={14} className="text-[var(--rust)]" />}
                {phaseLabel(phase)}
              </span>
            )}
            <button
              type="button"
              disabled={!file || !documentType || busy || !!version}
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

            {phase === "failed" && (
              <div className="mb-4 flex items-start gap-2.5 rounded-md border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[var(--rust-foreground)]">
                <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
                OCR could not process this document. You can still fill in fields manually, or retry below.
                <button
                  type="button"
                  onClick={() => handleRerun(Array.from(selectedFields))}
                  className="ml-auto shrink-0 font-semibold underline"
                >
                  Retry
                </button>
              </div>
            )}

            {(schemaQuery.data?.fields.length ?? 0) > 0 && (
              <div className="mb-4">
                <div className="mb-2.5 flex items-center justify-between gap-3">
                  <p className="text-[12.5px] font-semibold text-foreground">Fields</p>
                  {(phase === "done" || phase === "failed") && (
                    <button
                      type="button"
                      disabled={savingFields}
                      onClick={() => handleRerun(Array.from(selectedFields))}
                      className="flex items-center gap-1.5 text-[11.5px] font-semibold text-primary hover:underline disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      <RefreshCw size={11} />
                      Re-run OCR
                    </button>
                  )}
                </div>
                <DocumentFieldsForm
                  schema={schemaQuery.data}
                  loading={schemaQuery.isLoading}
                  values={fieldValues}
                  onChange={handleFieldChange}
                  disabled={savingFields}
                  ocrResults={ocrResultsByFieldKey}
                  needsReview={needsReview}
                  onRerunField={phase === "done" || phase === "failed" ? (fieldKey) => handleRerun([fieldKey]) : undefined}
                />
              </div>
            )}

            {(phase === "done" || phase === "failed") && (
              <div className="mb-4 rounded-md border border-border">
                <button
                  type="button"
                  onClick={() => setShowRawData((prev) => !prev)}
                  className="flex w-full items-center gap-1.5 px-3 py-2 text-left text-[11.5px] font-semibold text-muted-foreground hover:text-foreground"
                >
                  {showRawData ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                  View raw extraction data
                </button>
                {showRawData && (
                  <pre className="max-h-[400px] overflow-auto border-t border-border bg-muted px-3 py-2.5 font-mono text-[11.5px] leading-[1.5] text-foreground">
                    {ocrResultsByFieldKey && Object.keys(ocrResultsByFieldKey).length > 0
                      ? JSON.stringify(ocrResultsByFieldKey, null, 2)
                      : "No OCR extraction results for this document."}
                  </pre>
                )}
              </div>
            )}

            <div className="flex justify-end gap-2.5">
              {(schemaQuery.data?.fields.length ?? 0) > 0 && (
                <button
                  type="button"
                  disabled={savingFields || hasMissingMandatoryField(schemaQuery.data, fieldValues)}
                  onClick={handleSaveFields}
                  className="flex items-center gap-2 rounded-btn border border-[var(--border-strong)] px-5 py-2.5 text-[13px] font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-70"
                >
                  {savingFields && <Loader2 size={14} className="animate-spin" />}
                  {savingFields ? "Saving…" : "Save fields"}
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
