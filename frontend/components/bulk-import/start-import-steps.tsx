"use client"

import { useState } from "react"
import { ArrowRight, Download, Loader2, Upload } from "lucide-react"

import { Button } from "@/components/ui/button"
import { FileDropzone } from "@/components/ui/file-dropzone"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { friendlyMessage } from "@/lib/api-error"
import {
  downloadImportTemplate,
  saveDownloadedFile,
  type BulkImportCreatedResponseDto,
  type ImportDocumentTypeOptionDto,
  type ImportTemplatePreviewDto,
} from "@/lib/bulk-imports/api"

import { ImportRowsTable } from "./import-rows-table"
import { InlineError, StepCard } from "./step-card"

interface StartImportStepsProps {
  /** Active document types only, in display order (filtered server-side). */
  documentTypes: ImportDocumentTypeOptionDto[]
  documentTypesLoading: boolean
  documentTypesError: string | null
  previewing: boolean
  creating: boolean
  onPreview: (template: File) => Promise<{ preview: ImportTemplatePreviewDto | null; error: string | null }>
  onCreate: (template: File) => Promise<{ batch: BulkImportCreatedResponseDto | null; error: string | null }>
}

/**
 * Steps 1 and 2: download a template for a document type, then upload it
 * filled in. The template is checked first; if every row passes the batch is
 * opened straight away, otherwise the problem rows are listed and the user
 * either uploads a corrected template or continues without those rows.
 */
export function StartImportSteps({
  documentTypes,
  documentTypesLoading,
  documentTypesError,
  previewing,
  creating,
  onPreview,
  onCreate,
}: StartImportStepsProps) {
  const [documentTypeId, setDocumentTypeId] = useState("")
  const [downloading, setDownloading] = useState(false)
  const [downloadError, setDownloadError] = useState<string | null>(null)
  const [templateError, setTemplateError] = useState<string | null>(null)
  const [templateName, setTemplateName] = useState<string | null>(null)
  /** The checked template, kept so "Continue without these rows" can upload it. Set only while showing problems. */
  const [template, setTemplate] = useState<File | null>(null)
  const [preview, setPreview] = useState<ImportTemplatePreviewDto | null>(null)

  async function handleDownload() {
    if (!documentTypeId) return
    setDownloading(true)
    setDownloadError(null)
    try {
      saveDownloadedFile(await downloadImportTemplate(documentTypeId))
    } catch (error) {
      setDownloadError(friendlyMessage(error, "Could not download the template."))
    } finally {
      setDownloading(false)
    }
  }

  async function create(file: File) {
    setTemplateError(null)
    const { error } = await onCreate(file)
    if (error) setTemplateError(error)
  }

  async function handleTemplate(files: File[]) {
    const file = files[0]
    if (!file) return
    setTemplateName(file.name)
    setTemplateError(null)
    const checked = await onPreview(file)
    if (!checked.preview) {
      setTemplateError(checked.error)
      return
    }
    if (checked.preview.invalidRows === 0) {
      await create(file)
      return
    }
    setTemplate(file)
    setPreview(checked.preview)
  }

  function resetTemplate() {
    setTemplate(null)
    setPreview(null)
    setTemplateName(null)
    setTemplateError(null)
  }

  const busy = previewing || creating

  return (
    <div className="flex flex-col gap-4">
      <StepCard
        step={1}
        title="Download a template"
        description="Pick the document type you're importing. The template lists its current fields - fill in one row per file."
      >
        {documentTypesLoading ? (
          <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
            <Loader2 size={14} className="animate-spin" aria-hidden="true" />
            Loading document types…
          </p>
        ) : documentTypesError ? (
          <InlineError>{documentTypesError}</InlineError>
        ) : documentTypes.length === 0 ? (
          <p className="text-[13px] text-muted-foreground">There are no active document types to import into.</p>
        ) : (
          <div className="flex flex-wrap items-center gap-3">
            <SearchableSelect
              options={documentTypes.map((type) => ({ value: type.documentTypeId, label: type.name }))}
              selected={documentTypeId}
              onChange={(value) => {
                setDocumentTypeId(value)
                setDownloadError(null)
              }}
              allLabel="Select a document type"
              searchPlaceholder="Search document types…"
              className="w-[300px] max-w-full"
            />
            <Button onClick={handleDownload} disabled={!documentTypeId || downloading}>
              {downloading ? <Loader2 className="animate-spin" /> : <Download />}
              {downloading ? "Downloading…" : "Download template"}
            </Button>
          </div>
        )}
        {downloadError && <InlineError>{downloadError}</InlineError>}
      </StepCard>

      <StepCard
        step={2}
        title="Upload the filled-in template"
        description="Upload the completed .xlsx. Each row names the file it describes; you'll upload those files next."
      >
        {preview && template ? (
          <TemplateProblems
            preview={preview}
            templateName={template.name}
            creating={creating}
            onReupload={resetTemplate}
            onContinue={() => create(template)}
          />
        ) : (
          <FileDropzone accept=".xlsx" disabled={busy} onFiles={handleTemplate} label="Upload filled-in template">
            {busy ? (
              <span className="inline-flex items-center gap-2">
                <Loader2 size={14} className="animate-spin" aria-hidden="true" />
                {previewing ? "Checking template…" : `Checking ${templateName}…`}
              </span>
            ) : (
              <>
                <span className="font-semibold text-foreground">Drag the .xlsx template here</span> or click to
                browse
              </>
            )}
          </FileDropzone>
        )}
        {templateError && (
          <InlineError>
            {templateName && <strong className="font-semibold">{templateName}: </strong>}
            {templateError}
          </InlineError>
        )}
      </StepCard>
    </div>
  )
}

function TemplateProblems({
  preview,
  templateName,
  creating,
  onReupload,
  onContinue,
}: {
  preview: ImportTemplatePreviewDto
  templateName: string
  creating: boolean
  onReupload: () => void
  onContinue: () => void
}) {
  const { invalidRows, totalRows, validRows } = preview

  return (
    <section aria-live="polite">
      <h3 className="mb-1 text-[13px] font-semibold">
        {invalidRows} of {totalRows} {totalRows === 1 ? "row has" : "rows have"} problems
      </h3>
      <p className="mb-3 text-[12.5px] text-muted-foreground">
        {validRows === 0
          ? `None of the rows in ${templateName} can be imported. Fix the problems below and upload the template again.`
          : `Fix them in ${templateName} and upload it again, or continue and import only the rows without problems.`}
      </p>

      <ImportRowsTable rows={preview.rows} />

      <div className="mt-4 flex flex-wrap items-center gap-2.5">
        <Button onClick={onReupload} disabled={creating}>
          <Upload />
          Upload a corrected template
        </Button>
        {validRows > 0 && (
          <>
            <Button variant="outline" onClick={onContinue} disabled={creating}>
              {creating ? <Loader2 className="animate-spin" /> : <ArrowRight />}
              {creating ? "Starting import…" : "Continue without these rows"}
            </Button>
            <span className="text-[12.5px] text-muted-foreground">
              {validRows} {validRows === 1 ? "row" : "rows"} will be imported
            </span>
          </>
        )}
      </div>
    </section>
  )
}
