"use client"

import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { FileDown, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { InlineError, StepCard } from "@/components/bulk-import/step-card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { SearchableSelect } from "@/components/ui/searchable-select"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { friendlyMessage } from "@/lib/api-error"
import {
  fetchExportDocumentTypes,
  previewExport,
  requestExport,
  type CreateExportRequestDto,
  type ExportMetadataFormat,
} from "@/lib/export-jobs/api"

const LABEL = "mb-1.5 block text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase"

/**
 * Start a bulk export: a document type, optionally narrowed to the dates
 * the documents were created, as a ZIP of their files plus a metadata
 * sheet - or the sheet alone. The matching count is shown before anything
 * starts; the export itself is built in the background and appears in the
 * list below (and My Exports).
 */
export function NewExportForm() {
  const queryClient = useQueryClient()
  const [documentTypeId, setDocumentTypeId] = useState("")
  const [createdFrom, setCreatedFrom] = useState("")
  const [createdTo, setCreatedTo] = useState("")
  const [metadataFormat, setMetadataFormat] = useState<ExportMetadataFormat>("xlsx")
  const [contents, setContents] = useState<"files" | "metadata">("files")

  const types = useQuery({ queryKey: ["export-document-types"], queryFn: fetchExportDocumentTypes })

  const datesInvalid = Boolean(createdFrom && createdTo && createdFrom > createdTo)
  const request: CreateExportRequestDto = {
    documentTypeId: documentTypeId || undefined,
    createdFrom: createdFrom || undefined,
    createdTo: createdTo || undefined,
    metadataFormat,
    includeFiles: contents === "files",
  }

  const preview = useQuery({
    queryKey: ["export-preview", documentTypeId, createdFrom, createdTo],
    queryFn: () => previewExport(request),
    enabled: Boolean(documentTypeId) && !datesInvalid,
    retry: false,
  })

  const start = useMutation({
    mutationFn: () => requestExport(request),
    onSuccess: (job) => {
      toast.success(`Export started - ${job.documentCount} ${job.documentCount === 1 ? "document" : "documents"}.`)
      void queryClient.invalidateQueries({ queryKey: ["export-jobs"] })
    },
  })

  const count = preview.data?.documentCount
  const max = preview.data?.maxDocuments
  const canStart =
    Boolean(documentTypeId) && !datesInvalid && count !== undefined && count > 0 && max !== undefined && count <= max

  return (
    <StepCard
      title="New export"
      description="Choose what to export. You'll get a ZIP with the documents' files and a sheet of their metadata."
    >
      <div className="grid gap-4 md:grid-cols-2">
        <div className="md:col-span-2">
          <span className={LABEL}>Document type</span>
          {types.isError ? (
            <InlineError>{friendlyMessage(types.error, "Could not load document types.")}</InlineError>
          ) : (
            <SearchableSelect
              options={(types.data ?? []).map((type) => ({ value: type.documentTypeId, label: type.name }))}
              selected={documentTypeId}
              onChange={setDocumentTypeId}
              allLabel={types.isPending ? "Loading…" : "Select a document type"}
              searchPlaceholder="Search document types…"
              className="w-[320px] max-w-full"
            />
          )}
        </div>
        <div>
          <label htmlFor="export-from" className={LABEL}>
            Created from
          </label>
          <Input id="export-from" type="date" value={createdFrom} onChange={(e) => setCreatedFrom(e.target.value)} />
        </div>
        <div>
          <label htmlFor="export-to" className={LABEL}>
            Created to
          </label>
          <Input id="export-to" type="date" value={createdTo} onChange={(e) => setCreatedTo(e.target.value)} />
        </div>
        <div>
          <span className={LABEL}>Contents</span>
          <SegmentedControl
            value={contents}
            onChange={setContents}
            options={[
              { value: "files", label: "Files + metadata" },
              { value: "metadata", label: "Metadata only" },
            ]}
          />
        </div>
        <div>
          <span className={LABEL}>Metadata sheet</span>
          <SegmentedControl
            value={metadataFormat}
            onChange={setMetadataFormat}
            options={[
              { value: "xlsx", label: "Excel (.xlsx)" },
              { value: "csv", label: "CSV" },
            ]}
          />
        </div>
      </div>

      <div className="mt-5 flex flex-wrap items-center gap-3">
        <Button onClick={() => start.mutate()} disabled={!canStart || start.isPending}>
          {start.isPending ? <Loader2 className="animate-spin" /> : <FileDown />}
          {start.isPending ? "Starting…" : "Start export"}
        </Button>
        <p className="text-[12.5px] text-muted-foreground" aria-live="polite">
          {!documentTypeId
            ? "Choose a document type to see how many documents match."
            : datesInvalid
              ? "The start date is after the end date."
              : preview.isFetching
                ? "Counting…"
                : preview.isError
                  ? friendlyMessage(preview.error, "Could not count the documents.")
                  : count === 0
                    ? "No documents match."
                    : count !== undefined && max !== undefined && count > max
                      ? `${count} documents match - an export can hold at most ${max}. Narrow the dates.`
                      : count !== undefined
                        ? `${count} ${count === 1 ? "document matches" : "documents match"}.`
                        : null}
        </p>
      </div>
      {start.isError && <InlineError>{friendlyMessage(start.error, "Could not start the export.")}</InlineError>}
    </StepCard>
  )
}
