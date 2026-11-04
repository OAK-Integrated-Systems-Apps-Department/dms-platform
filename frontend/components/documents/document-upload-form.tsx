"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { AlertTriangle, CheckCircle2, FileText, Loader2, RefreshCw, XCircle } from "lucide-react"
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
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { FolderSelect } from "@/components/folders/folder-select"
import { getTooltip } from "@/lib/tooltips"
import { fetchDocumentTypeSchema } from "@/lib/document-type-fields/api"
import { fetchAllDocumentTypes } from "@/lib/document-types/api"
import {
  fetchDocumentMetadata,
  fetchOcrStatus,
  rerunOcr,
  saveDocumentMetadata,
  uploadDocument,
  type IngestionTicketDto,
  type MetadataValues,
  type OcrStatusResponse,
} from "@/lib/documents/api"

/**
 * What the form tracks once the file has been accepted.
 *
 * Not a DocumentVersionResponse any more: this upload goes through the
 * malware scan gate, so there is no version until the file comes back clean.
 * The ingestion ticket is what exists in the meantime, and with a synchronous
 * scanner it already carries the ids; with a cloud scanner it is `pending` and
 * the ticket is what gets polled. OCR starts only after the clean verdict, so
 * the OCR poll below is enabled only once there is a versionId.
 */
interface ScannedVersion {
  documentId: string
  versionId: string
}

type UploadPhase = "idle" | "uploading" | "queued" | "processing" | "done" | "failed"

export function isTerminalStatus(status: OcrStatusResponse["status"] | undefined): boolean {
  // "queued" is deliberately NOT terminal -- polling must continue while
  // the job waits for an ocr-worker to claim it.
  return status === "completed" || status === "failed"
}

export function phaseLabel(phase: UploadPhase): string {
  switch (phase) {
    case "uploading":
      return "Uploading…"
    case "queued":
      return "Queued…"
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
 * Retrying a permanently-unsupported format can never succeed, so the
 * retry affordances are hidden for that one error code specifically (a
 * real gap confirmed live: a HEIC upload showed the generic "…or retry
 * below" message, inviting a retry that could never work).
 */
export function isRetryable(errorCode: string | null | undefined): boolean {
  return errorCode !== "UNSUPPORTED_FORMAT"
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
  // "" until the user picks one; the effective type falls back to the
  // catalogue default below. Derived rather than written by an effect: the
  // default is a function of the loaded catalogue, not a separate fact.
  const [documentType, setDocumentType] = useState("")
  /** Shown as the document's name. Defaults to the filename, which is what people expect. */
  const [title, setTitle] = useState("")
  /** Where the document is filed. Null = the top level, same as the browser's upload. */
  const [folderId, setFolderId] = useState<string | null>(null)
  // null until the user changes the selection: the OCR field choice is the
  // schema default until somebody says otherwise, and keeping "untouched"
  // distinct from "chose exactly the defaults" is what lets the selection
  // follow a document-type change without an effect writing state.
  const [fieldSelection, setFieldSelection] = useState<Set<string> | null>(null)
  const [fieldValues, setFieldValues] = useState<MetadataValues>({})
  const [uploading, setUploading] = useState(false)
  const [savingFields, setSavingFields] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [version, setVersion] = useState<ScannedVersion | null>(null)
  /** The gate's answer while it is still 'pending', or when it refused the file. */
  const [ticket, setTicket] = useState<IngestionTicketDto | null>(null)
  // Bumped on every reset so the native <input type="file"> (whose
  // displayed filename can't be cleared just by resetting file to null --
  // the browser keeps showing it) remounts empty. Real bug found live: with
  // no way back to a blank form after one upload finished, the only way to
  // upload a second document was a full page reload.
  const [uploadKey, setUploadKey] = useState(0)

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
  // whichever active type comes first if GENERAL isn't configured for this
  // tenant), unless the user has picked something.
  //
  // Derived, not written by an effect. The old version set state from an
  // effect the moment the catalogue arrived, which is a render just to copy
  // a value that was already computable - and it meant the form briefly had
  // no type selected at all.
  const preferredType =
    activeDocumentTypes.find((t) => t.typeCode === "GENERAL") ?? activeDocumentTypes[0]
  const effectiveDocumentType = documentType || preferredType?.typeCode || ""
  const selectedType = activeDocumentTypes.find((t) => t.typeCode === effectiveDocumentType)
  const schemaQuery = useQuery({
    queryKey: ["document-type-schema", selectedType?.documentTypeId],
    queryFn: () => fetchDocumentTypeSchema(selectedType!.documentTypeId),
    enabled: !!selectedType,
    retry: false,
  })

  /**
   * The OCR field selection actually in force.
   *
   * Untouched (`fieldSelection === null`) means the schema's own defaults, so
   * switching document type before uploading simply changes what this
   * computes - no effect, no stale selection. Once the user has chosen, their
   * set is reconciled against the current schema, which drops keys the new
   * type doesn't have and keeps the ones it does (reconcileRequestedFields).
   */
  const selectedFields = useMemo(
    () =>
      fieldSelection === null
        ? defaultRequestedFields(schemaQuery.data)
        : reconcileRequestedFields(fieldSelection, schemaQuery.data),
    [fieldSelection, schemaQuery.data]
  )

  /**
   * Switching document type before uploading abandons any values typed
   * against the old type's fields.
   *
   * Done in the change handler rather than an effect on `documentType`: it is
   * a consequence of the user's action, not state that needs synchronising,
   * and as an effect it also fired once on mount and cleared values that had
   * just been restored.
   */
  function handleDocumentTypeChange(typeCode: string) {
    setDocumentType(typeCode)
    if (!version) {
      setFieldValues({})
      setFieldSelection(null)
    }
  }

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
          : ocrStatusQuery.data?.status === "queued"
            ? "queued"
            : "processing"

  const ocrErrorCode = ocrStatusQuery.data?.errorCode
  const retryable = isRetryable(ocrErrorCode)

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
          for (const [fieldKey, value] of Object.entries(saved)) {
            if (!userTouchedFields.current.has(fieldKey)) next[fieldKey] = value
          }
          return next
        })
      })
      .catch(() => {
        // Best-effort, same as the old post-OCR refetch: the OCR result
        // itself already landed; the user can still see/edit whatever
        // was already local.
      })
  }, [version, ocrStatusQuery.data])

  async function handleUpload() {
    if (!file || !documentType) return
    const selectedType = activeDocumentTypes.find((t) => t.typeCode === documentType)
    if (!selectedType) return
    setUploadError(null)
    setUploading(true)
    try {
      // The scan gate decides, not us: a clean verdict comes back with the
      // ids, a cloud scanner answers 'pending', and an infected file is
      // refused outright. OCR is queued server-side only on the clean path,
      // so there is nothing to start here either way.
      const result = await uploadDocument(
        file,
        selectedType.documentTypeId,
        title.trim() || file.name,
        folderId,
        false,
        Array.from(selectedFields)
      )
      setTicket(result)
      appliedResultKey.current = null
      userTouchedFields.current = new Set()
      if (result.status === "clean" && result.documentId && result.versionId) {
        setVersion({ documentId: result.documentId, versionId: result.versionId })
        toast.success("Document uploaded. Running OCR…")
      } else if (result.status === "pending") {
        toast.success("Uploaded. Scanning for malware before OCR starts…")
      } else {
        const refused = result.message ?? "This file was refused by the malware scan."
        setUploadError(refused)
        toast.error(refused)
      }
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
      setFieldValues(await saveDocumentMetadata(version.documentId, fieldValues))
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
    // Seeds from the effective selection, not from the raw override: the first
    // toggle has to start from whatever the user can currently see ticked -
    // the schema defaults - or it would clear everything else.
    setFieldSelection((prev) => {
      const next = new Set(prev ?? selectedFields)
      if (next.has(fieldKey)) next.delete(fieldKey)
      else next.add(fieldKey)
      return next
    })
  }

  /** Back to a blank form so a second document can be uploaded without a
   * page reload. The prior document's own OCR/status keeps running and
   * stays saved server-side -- this only resets what's shown here. */
  function handleReset() {
    setFile(null)
    setVersion(null)
    setTicket(null)
    setTitle("")
    setUploadError(null)
    setFieldValues({})
    setFieldSelection(null)
    appliedResultKey.current = null
    userTouchedFields.current = new Set()
    setUploadKey((k) => k + 1)
  }

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

          {version ? (
            <div className="mb-5 flex items-center justify-between gap-3 rounded-md border border-dashed border-border px-[13px] py-[11px]">
              <p className="text-[13px] text-muted-foreground">
                Reviewing <span className="font-semibold text-foreground">{ticket?.originalFilename ?? "this document"}</span> below.
              </p>
              <button
                type="button"
                onClick={handleReset}
                className="shrink-0 rounded-btn border border-[var(--border-strong)] font-semibold text-foreground h-[38px] px-4 text-[13px] inline-flex items-center justify-center"
              >
                Upload another document
              </button>
            </div>
          ) : (
            <>
              <div className="mb-5 grid grid-cols-2 gap-[18px]">
                <div>
                  <Label htmlFor="file">File</Label>
                  <Input
                    key={uploadKey}
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
                    value={effectiveDocumentType}
                    onChange={(e) => handleDocumentTypeChange(e.target.value)}
                    disabled={documentTypesQuery.isLoading || activeDocumentTypes.length === 0}
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
                <div>
                  {/*
                    A scanned document is filed like any other, so the folder
                    is the uploader's choice here rather than something the
                    OCR flow decides for them. Left at "no folder" it lands at
                    the top level, exactly as the browser's own upload does -
                    the same component, so folder access is enforced the same
                    way on both screens.
                  */}
                  <Label htmlFor="scanFolder">Folder</Label>
                  <FolderSelect
                    value={folderId ?? ""}
                    onChange={(next) => setFolderId(next || null)}
                    disabled={uploading}
                    className="w-full"
                  />
                  <p className="mt-1.5 text-[12px] text-muted-foreground">
                    Leave empty to file it at the top level.
                  </p>
                </div>
              </div>

              <FieldChips
                schema={schemaQuery.data}
                loading={schemaQuery.isLoading}
                selected={selectedFields}
                onToggle={handleToggleField}
                onSelectAll={() => setFieldSelection(new Set(visibleFieldsOf(schemaQuery.data).map((f) => f.fieldKey)))}
                onClear={() => setFieldSelection(mandatoryRequestedFields(schemaQuery.data))}
              />
            </>
          )}

          {!version && (
            <>
              <hr className="my-5 border-t border-dashed border-border" />

              <div className="flex items-center justify-end gap-2.5">
                <button
                  type="button"
                  disabled={!file || !documentType || uploading}
                  onClick={handleUpload}
                  className="flex items-center gap-2 rounded-btn border border-[var(--primary-hover)] bg-primary inline-flex h-[38px] items-center justify-center px-4 text-[13px] font-semibold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-70"
                >
                  {uploading && <Loader2 size={14} className="animate-spin" />}
                  {uploading ? "Uploading…" : "Upload"}
                </button>
              </div>
            </>
          )}
        </div>

        {version && (
          <div className="rounded-lg border border-border bg-card p-[30px] pb-[26px]">
            <div className="mb-4 flex items-center justify-between gap-2.5">
              <div className="flex items-center gap-2.5">
                <FileText size={18} className="text-primary" />
                <div>
                  <p className="text-[14px] font-semibold text-foreground">
                    {ticket?.originalFilename ?? file?.name}
                  </p>
                  <p className="text-[12px] text-muted-foreground">
                    {/* The scan verdict comes from the gate's ticket; the type
                        and size from the file the user chose, which is the same
                        thing and saves the ticket carrying a mime type it has
                        no other use for. */}
                    {file?.type || "unknown type"} ·{" "}
                    {(ticket?.sizeBytes ?? file?.size ?? 0).toLocaleString()} bytes · scan:{" "}
                    {ticket?.status ?? "pending"}
                  </p>
                </div>
              </div>
              {phase !== "idle" && (
                <span className="flex shrink-0 items-center gap-1.5 text-[12.5px] font-medium text-muted-foreground">
                  {(phase === "uploading" || phase === "queued" || phase === "processing") && <Loader2 size={14} className="animate-spin" />}
                  {phase === "done" && <CheckCircle2 size={14} className="text-primary" />}
                  {phase === "failed" && <XCircle size={14} className="text-[var(--rust)]" />}
                  {phaseLabel(phase)}
                </span>
              )}
            </div>

            {phase === "failed" && (
              <div className="mb-4 flex items-start gap-2.5 rounded-md border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[var(--rust-foreground)]">
                <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
                {ocrStatusQuery.data?.friendlyError ?? "OCR could not process this document."}{" "}
                {retryable ? "You can still fill in fields manually, or retry below." : "You can still fill in the fields manually."}
                {retryable && (
                  <button
                    type="button"
                    onClick={() => handleRerun(Array.from(selectedFields))}
                    className="ml-auto shrink-0 font-semibold underline"
                  >
                    Retry
                  </button>
                )}
              </div>
            )}

            {(schemaQuery.data?.fields.length ?? 0) > 0 && (
              <div className="mb-4">
                <div className="mb-2.5 flex items-center justify-between gap-3">
                  <p className="text-[12.5px] font-semibold text-foreground">Fields</p>
                  {(phase === "done" || (phase === "failed" && retryable)) && (
                    <Tooltip>
                      <TooltipTrigger
                        render={(props) => (
                          <button
                            {...props}
                            type="button"
                            disabled={savingFields}
                            onClick={() => handleRerun(Array.from(selectedFields))}
                            className="flex items-center gap-1.5 text-[11.5px] font-semibold text-primary hover:underline disabled:cursor-not-allowed disabled:opacity-60"
                          >
                            <RefreshCw size={11} />
                            Re-run OCR
                          </button>
                        )}
                      />
                      <TooltipContent>{getTooltip("documents.ocr.rerunAll")}</TooltipContent>
                    </Tooltip>
                  )}
                </div>
                <DocumentFieldsForm
                  schema={schemaQuery.data}
                  loading={schemaQuery.isLoading}
                  values={fieldValues}
                  onChange={handleFieldChange}
                  disabled={savingFields}
                  ocrResults={ocrResultsByFieldKey}
                  onRerunField={phase === "done" || (phase === "failed" && retryable) ? (fieldKey) => handleRerun([fieldKey]) : undefined}
                />
              </div>
            )}

            <div className="flex justify-end gap-2.5">
              {(schemaQuery.data?.fields.length ?? 0) > 0 && (
                <button
                  type="button"
                  disabled={savingFields || hasMissingMandatoryField(schemaQuery.data, fieldValues)}
                  onClick={handleSaveFields}
                  className="flex items-center gap-2 rounded-btn border border-[var(--border-strong)] font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-70 h-[38px] px-4 text-[13px]"
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
