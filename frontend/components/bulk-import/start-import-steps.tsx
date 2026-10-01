"use client"

import { useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Download, Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { FileDropzone } from "@/components/ui/file-dropzone"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { friendlyMessage } from "@/lib/api-error"
import {
  downloadImportTemplate,
  saveDownloadedFile,
  type BulkImportCreatedResponseDto,
  type ImportDocumentTypeOptionDto,
  type TemplateFormat,
} from "@/lib/bulk-imports/api"
import { downloadSavedImportTemplate, fetchImportTemplates } from "@/lib/import-templates/api"

import { InlineError, StepCard } from "./step-card"

interface StartImportStepsProps {
  /** Active document types only, in display order (filtered server-side). */
  documentTypes: ImportDocumentTypeOptionDto[]
  documentTypesLoading: boolean
  documentTypesError: string | null
  creating: boolean
  onCreate: (template: File) => Promise<{ batch: BulkImportCreatedResponseDto | null; error: string | null }>
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
      setDownloadError(friendlyMessage(error, "Could not download the template."))
    } finally {
      setDownloading(false)
    }
  }

  async function handleTemplate(files: File[]) {
    const template = files[0]
    if (!template) return
    setTemplateName(template.name)
    setTemplateError(null)
    const { error } = await onCreate(template)
    if (error) setTemplateError(error)
  }

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
                setSavedTemplateId("")
                setDownloadError(null)
              }}
              allLabel="Select a document type"
              searchPlaceholder="Search document types…"
              className="w-[300px] max-w-full"
            />
            {documentTypeId && (savedTemplates.data?.length ?? 0) > 0 && (
              <SearchableSelect
                options={(savedTemplates.data ?? []).map((t) => ({ value: t.importTemplateId, label: t.name }))}
                selected={savedTemplateId}
                onChange={setSavedTemplateId}
                allLabel="Standard template (all fields)"
                searchable={(savedTemplates.data?.length ?? 0) > 8}
                className="w-[260px] max-w-full"
              />
            )}
            <SegmentedControl
              value={format}
              onChange={setFormat}
              options={[
                { value: "xlsx", label: "Excel (.xlsx)" },
                { value: "csv", label: "CSV" },
              ]}
              disabled={downloading}
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
        description="Upload the completed .xlsx or .csv - just the list of rows. Each row names the document file it describes; you upload the documents themselves (PDF, Office, images, email and more) in the next step."
      >
        <FileDropzone
          accept=".xlsx,.csv"
          disabled={creating}
          onFiles={handleTemplate}
          label="Upload filled-in template"
        >
          {creating ? (
            <span className="inline-flex items-center gap-2">
              <Loader2 size={14} className="animate-spin" aria-hidden="true" />
              Checking {templateName}…
            </span>
          ) : (
            <>
              <span className="font-semibold text-foreground">Drag the .xlsx or .csv template here</span> or click to
              browse
            </>
          )}
        </FileDropzone>
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
