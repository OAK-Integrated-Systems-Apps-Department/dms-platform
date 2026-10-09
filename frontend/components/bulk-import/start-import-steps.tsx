"use client"

import { useMemo, useState, type ReactNode } from "react"
import { useQuery } from "@tanstack/react-query"
import { Download, Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { FileDropzone } from "@/components/ui/file-dropzone"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { FolderSelect } from "@/components/folders/folder-select"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { friendlyMessage } from "@/lib/api-error"
import {
  downloadImportTemplate,
  saveDownloadedFile,
  type BulkImportCreatedResponseDto,
  type ImportDocumentTypeOptionDto,
  type TemplateFormat,
  type DuplicateAction,
} from "@/lib/bulk-imports/api"
import {
  downloadSavedImportTemplate,
  fetchImportTemplates,
} from "@/lib/import-templates/api"
import { fetchDocumentTypeSchema } from "@/lib/document-type-fields/api"
import {
  FieldChips,
  defaultRequestedFields,
  mandatoryRequestedFields,
} from "@/components/documents/document-fields-form"
import { useOcrAvailability } from "@/lib/documents/use-ocr-availability"

import { cn } from "@/lib/utils"

import { InlineError, StepBadge } from "./step-card"

interface StartImportStepsProps {
  /** Active document types only, in display order (filtered server-side). */
  documentTypes: ImportDocumentTypeOptionDto[]
  documentTypesLoading: boolean
  documentTypesError: string | null
  creating: boolean
  onCreate: (
    template: File,
    folderId: string | null,
    duplicateAction: DuplicateAction,
    ocrFields: readonly string[]
  ) => Promise<{
    batch: BulkImportCreatedResponseDto | null
    error: string | null
  }>
}

/** Steps 1 and 2: download a template for a document type, then upload it filled in to open a batch. */
export function StartImportSteps({
  documentTypes,
  documentTypesLoading,
  documentTypesError,
  creating,
  onCreate,
}: StartImportStepsProps) {
  const [documentTypeId, setDocumentTypeId] = useState("")
  const [format, setFormat] = useState<TemplateFormat>("xlsx")
  /** "" = the standard template (every field). */
  const [savedTemplateId, setSavedTemplateId] = useState("")
  const savedTemplates = useQuery({
    queryKey: ["import-templates", "for-type", documentTypeId],
    queryFn: () => fetchImportTemplates(documentTypeId),
    enabled: Boolean(documentTypeId),
  })
  const [downloading, setDownloading] = useState(false)
  const [downloadError, setDownloadError] = useState<string | null>(null)
  const [templateError, setTemplateError] = useState<string | null>(null)
  const [templateName, setTemplateName] = useState<string | null>(null)
  /** "" = documents not filed in a folder. */
  const [folderId, setFolderId] = useState("")
  const [duplicateAction, setDuplicateAction] =
    useState<DuplicateAction>("new_version")

  // -- "Run OCR on imported files" --
  //
  // Off by default. OCR is minutes of CPU per document, so an import of
  // thousands of files must be something somebody asked for, never something
  // that happens because a checkbox defaulted on.
  const ocrAvailable = useOcrAvailability()
  const [runOcr, setRunOcr] = useState(false)
  const ocrSchemaQuery = useQuery({
    queryKey: ["document-type-schema", documentTypeId],
    queryFn: () => fetchDocumentTypeSchema(documentTypeId),
    enabled: Boolean(documentTypeId) && runOcr,
    retry: false,
  })
  /**
   * null = untouched, so the schema's own defaults apply and switching
   * document type simply changes what this computes. Same shape as the scan
   * upload form's `fieldSelection`, and for the same reason: an eagerly
   * materialised Set would go stale the moment the type changed.
   */
  const [fieldSelection, setFieldSelection] = useState<Set<string> | null>(null)
  const selectedFields = useMemo(
    () => fieldSelection ?? defaultRequestedFields(ocrSchemaQuery.data),
    [fieldSelection, ocrSchemaQuery.data]
  )

  function handleDocumentTypeChange(next: string) {
    setDocumentTypeId(next)
    setSavedTemplateId("")
    // The previous type's field keys mean nothing for this one.
    setFieldSelection(null)
  }

  function toggleField(fieldKey: string) {
    setFieldSelection((current) => {
      const next = new Set(current ?? defaultRequestedFields(ocrSchemaQuery.data))
      if (next.has(fieldKey)) next.delete(fieldKey)
      else next.add(fieldKey)
      return next
    })
  }

  async function handleDownload() {
    if (!documentTypeId) return
    setDownloading(true)
    setDownloadError(null)
    try {
      saveDownloadedFile(
        savedTemplateId
          ? await downloadSavedImportTemplate(savedTemplateId, format)
          : await downloadImportTemplate(documentTypeId, format)
      )
    } catch (error) {
      setDownloadError(
        friendlyMessage(error, "Could not download the template.")
      )
    } finally {
      setDownloading(false)
    }
  }

  async function handleTemplate(files: File[]) {
    const template = files[0]
    if (!template) return
    setTemplateName(template.name)
    setTemplateError(null)
    const { error } = await onCreate(
      template,
      folderId || null,
      duplicateAction,
      runOcr ? [...selectedFields] : []
    )
    if (error) setTemplateError(error)
  }

  return (
    <section
      aria-label="Start an import with a template"
      className="grid overflow-hidden rounded-xl border border-border bg-card shadow-[var(--ledger-shadow)] lg:grid-cols-2"
    >
      <div className="flex flex-col gap-4 p-5 sm:p-6">
        <PanelHeading
          step={1}
          title="Get the template"
          description="Pick the document type you're importing. The template lists its current fields - fill in one row per file."
        />
        {documentTypesLoading ? (
          <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
            <Loader2 size={14} className="animate-spin" aria-hidden="true" />
            Loading document types…
          </p>
        ) : documentTypesError ? (
          <InlineError>{documentTypesError}</InlineError>
        ) : documentTypes.length === 0 ? (
          <p className="text-[13px] text-muted-foreground">
            There are no active document types to import into.
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            <Field label="Document type">
              <SearchableSelect
                options={documentTypes.map((type) => ({
                  value: type.documentTypeId,
                  label: type.name,
                }))}
                selected={documentTypeId}
                onChange={(value) => {
                  handleDocumentTypeChange(value)
                  setDownloadError(null)
                }}
                allLabel="Select a document type"
                searchPlaceholder="Search document types…"
                className="w-full"
              />
            </Field>
            {documentTypeId && (savedTemplates.data?.length ?? 0) > 0 && (
              <Field label="Saved template">
                <SearchableSelect
                  options={(savedTemplates.data ?? []).map((t) => ({
                    value: t.importTemplateId,
                    label: t.name,
                  }))}
                  selected={savedTemplateId}
                  onChange={setSavedTemplateId}
                  allLabel="Standard template (all fields)"
                  searchable={(savedTemplates.data?.length ?? 0) > 8}
                  className="w-full"
                />
              </Field>
            )}
            <Field label="Format">
              <FormatToggle
                value={format}
                onChange={setFormat}
                disabled={downloading}
              />
            </Field>
            <Button
              onClick={handleDownload}
              disabled={!documentTypeId || downloading}
              className="self-start"
            >
              {downloading ? (
                <Loader2 className="animate-spin" />
              ) : (
                <Download />
              )}
              {downloading ? "Downloading…" : "Download template"}
            </Button>
          </div>
        )}
        {downloadError && <InlineError>{downloadError}</InlineError>}
      </div>

      <div className="flex flex-col gap-4 border-t border-border bg-muted/20 p-5 sm:p-6 lg:border-t-0 lg:border-l">
        <PanelHeading
          step={2}
          title="Upload the filled-in sheet"
          description="Just the list of rows - each one names the file it describes. You upload the files themselves (PDF, Office, images, email and more) next."
        />
        <Field label="File the documents in">
          <FolderSelect
            value={folderId}
            onChange={setFolderId}
            disabled={creating}
            className="w-full"
          />
        </Field>
        <Field label="If a file is already in the DMS">
          <SegmentedControl
            value={duplicateAction}
            onChange={setDuplicateAction}
            disabled={creating}
            options={[
              { value: "new_version", label: "Add as new version" },
              { value: "keep_both", label: "Keep both" },
              { value: "skip", label: "Skip it" },
            ]}
          />
          <p className="mt-1.5 text-[12px] leading-[1.5] text-muted-foreground">
            {duplicateAction === "new_version"
              ? "A file with the same name in this folder becomes its next version. An identical file isn't uploaded again."
              : duplicateAction === "skip"
                ? "Files with the same name in this folder, or the same content anywhere you can see, are left out."
                : "Every file becomes a new document, even if it's already there."}
          </p>
        </Field>
        {/*
          Hidden entirely when OCR is off for this deployment, rather than
          shown disabled: a checkbox you cannot tick is a question about a
          feature that does not exist here.

          Also hidden until a document type is picked - the field picker has
          nothing to offer before then, and asking "which fields?" before
          "which type?" is the wrong order.
        */}
        {ocrAvailable.data && documentTypeId && (
          <Field label="OCR">
            <label className="flex items-start gap-2.5 text-[13px] leading-[1.5] text-foreground">
              <input
                type="checkbox"
                checked={runOcr}
                disabled={creating}
                onChange={(e) => setRunOcr(e.target.checked)}
                className="mt-0.5 size-4 shrink-0 accent-primary"
              />
              <span>
                Run OCR on imported files
                <span className="mt-0.5 block text-[12px] text-muted-foreground">
                  Each file is read after it passes its virus scan, and its
                  fields are filled in for review. Imports use a separate,
                  lower-priority queue, so this never delays a scan someone is
                  waiting on.
                </span>
              </span>
            </label>
            {runOcr && (
              <div className="mt-3">
                <FieldChips
                  schema={ocrSchemaQuery.data}
                  loading={ocrSchemaQuery.isLoading}
                  selected={selectedFields}
                  onToggle={toggleField}
                  onSelectAll={() =>
                    setFieldSelection(
                      new Set(
                        (ocrSchemaQuery.data?.fields ?? []).map((f) => f.fieldKey)
                      )
                    )
                  }
                  // Clear falls back to the mandatory fields, never to
                  // nothing: a document type's mandatory fields cannot be
                  // deselected, and an empty set would be rejected by the
                  // same MANDATORY_FIELD_NOT_REQUESTED check the scan upload
                  // hits.
                  onClear={() =>
                    setFieldSelection(
                      mandatoryRequestedFields(ocrSchemaQuery.data)
                    )
                  }
                  disabled={creating}
                />
                {ocrSchemaQuery.isError && (
                  <InlineError>
                    Could not load this document type&apos;s fields, so OCR
                    can&apos;t be set up for this import.
                  </InlineError>
                )}
              </div>
            )}
          </Field>
        )}
        <FileDropzone
          accept=".xlsx,.csv"
          disabled={creating}
          onFiles={handleTemplate}
          label="Upload filled-in template"
          className="flex-1"
        >
          {creating ? (
            <span className="inline-flex items-center gap-2">
              <Loader2 size={14} className="animate-spin" aria-hidden="true" />
              Checking {templateName}…
            </span>
          ) : (
            <>
              <span className="font-semibold text-foreground">
                Drag the .xlsx or .csv here
              </span>{" "}
              or click to browse
            </>
          )}
        </FileDropzone>
        {templateError && (
          <InlineError>
            {templateName && (
              <strong className="font-semibold">{templateName}: </strong>
            )}
            {templateError}
          </InlineError>
        )}
      </div>
    </section>
  )
}

function PanelHeading({
  step,
  title,
  description,
}: {
  step: number
  title: string
  description: string
}) {
  return (
    <div className="flex items-start gap-3">
      <StepBadge step={step} />
      <div>
        <h2 className="text-[15px] font-semibold">
          <span className="sr-only">Step {step}: </span>
          {title}
        </h2>
        <p className="mt-1 text-[13px] leading-[1.6] text-muted-foreground">
          {description}
        </p>
      </div>
    </div>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="mb-1.5 text-[11.5px] font-medium tracking-[0.04em] text-muted-foreground uppercase">
        {label}
      </p>
      {children}
    </div>
  )
}

/** Compact two-option switch (Excel / CSV), in the style of the My Documents view toggle. */
function FormatToggle({
  value,
  onChange,
  disabled,
}: {
  value: TemplateFormat
  onChange: (value: TemplateFormat) => void
  disabled?: boolean
}) {
  return (
    <div
      role="radiogroup"
      aria-label="Template format"
      className="inline-flex rounded-lg border border-border p-0.5"
    >
      {(
        [
          { value: "xlsx", label: "Excel (.xlsx)" },
          { value: "csv", label: "CSV" },
        ] as const
      ).map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={value === option.value}
          disabled={disabled}
          onClick={() => onChange(option.value)}
          className={cn(
            "rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors",
            value === option.value
              ? "bg-primary/15 text-foreground"
              : "text-muted-foreground hover:text-foreground"
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}
