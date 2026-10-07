"use client"

import { useRef, useState, type ReactNode } from "react"
import { AlertTriangle } from "lucide-react"
import { toast } from "sonner"

import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage } from "@/lib/api-error"
import type { BulkImportSummaryDto } from "@/lib/bulk-imports/api"
import { useBulkImport } from "@/lib/bulk-imports/use-bulk-import"
import { useImportDocumentTypes } from "@/lib/bulk-imports/use-import-document-types"
import { useUploadQueue } from "@/lib/bulk-imports/use-upload-queue"

import { BatchStatusCard } from "./batch-status-card"
import { ImportResultsCard } from "./import-results-card"
import { RecentImports } from "./recent-imports"
import { StartImportSteps } from "./start-import-steps"
import { InlineError } from "./step-card"
import { UploadFilesStep } from "./upload-files-step"

/**
 * Bulk Import screen body. What it shows is derived from the server:
 * - no batch being worked on: the caller's active batch (resume / queued /
 *   running card), or step 1 (choose a document type) to start a new one;
 * - a batch being worked on (or a submitted one found on load): steps 2-3
 *   (add files, submit) while it's `uploading`, the submitted card - polled for progress -
 *   while it's `queued`/`running`, and the results once it's finished (or
 *   cancelled).
 *
 * Below it, always, the caller's recent imports. Opening one there makes it
 * the batch being worked on, so it lands in the same views.
 *
 * Without document-create (`canCreateDocuments` false) a new import can't be
 * started, and an existing one is shown under a banner with uploading and
 * submitting disabled - status, results and Cancel stay available, matching
 * the backend, which keeps those endpoints FIE-only. The recent-imports list
 * is read-only, so it shows either way.
 */
export function BulkImportWizard({ canCreateDocuments }: { canCreateDocuments: boolean }) {
  const [batchId, setBatchId] = useState<string | null>(null)
  /** The batch's file limit - known only from the create response, so null after a resume. */
  const [maxFiles, setMaxFiles] = useState<number | null>(null)
  /** The list row last opened - its type name covers types that are no longer active. */
  const [opened, setOpened] = useState<BulkImportSummaryDto | null>(null)
  const topRef = useRef<HTMLDivElement>(null)

  const bulkImport = useBulkImport(batchId)
  const { documentTypes, isLoading: documentTypesLoading, error: documentTypesError } = useImportDocumentTypes()
  const queue = useUploadQueue(() => {
    void bulkImport.refetchStatus()
  })

  const openBatchId = batchId ?? bulkImport.watchedId

  function documentTypeName(documentTypeId: string): string | null {
    // The picker's list only has active types; an older import's type may
    // since have been deactivated or deleted.
    const active = documentTypes.find((type) => type.documentTypeId === documentTypeId)
    if (active) return active.name
    return opened?.documentTypeId === documentTypeId ? opened.documentTypeName : null
  }

  async function handleCreate(documentTypeId: string) {
    const result = await bulkImport.create(documentTypeId)
    if (result.batch) {
      setMaxFiles(result.batch.maxFiles)
      setBatchId(result.batch.batchId)
    }
    return result.error
  }

  function handleStartOver() {
    queue.reset()
    setMaxFiles(null)
    setOpened(null)
    setBatchId(null)
    void bulkImport.clearActive()
  }

  function handleContinue(id: string) {
    setMaxFiles(null)
    setBatchId(id)
  }

  function handleOpen(summary: BulkImportSummaryDto) {
    queue.reset()
    setMaxFiles(null)
    setOpened(summary)
    setBatchId(summary.batchId)
    topRef.current?.scrollIntoView({ behavior: "smooth", block: "start" })
  }

  async function handleSubmit(id: string) {
    const error = await bulkImport.submit(id)
    if (!error) {
      queue.reset()
      toast.success("Import submitted.")
    }
    return error
  }

  async function handleCancel(id: string) {
    const error = await bulkImport.cancel(id)
    if (!error) {
      queue.reset()
      setMaxFiles(null)
      setBatchId(null)
      toast.success("Import cancelled.")
    }
    return error
  }

  function withPermissionBanner(content: ReactNode) {
    if (canCreateDocuments) return content
    return (
      <>
        <div className="mb-4">
          <InlineError>{NEEDS_DOCUMENT_CREATE}</InlineError>
        </div>
        {content}
      </>
    )
  }

  function renderMain(): ReactNode {
    // Working on a specific batch (just created, resumed, opened from the
    // list, or already submitted when the screen loaded).
    if (openBatchId) {
      const status = bulkImport.status
      if (!status) {
        return bulkImport.statusLoading ? (
          <LoadingState message="Loading import…" />
        ) : (
          <ErrorPanel message="Could not load this import." />
        )
      }
      if (status.status === "uploading") {
        return withPermissionBanner(
          <UploadFilesStep
            status={status}
            maxFiles={maxFiles}
            entries={queue.entries}
            uploading={queue.busy}
            uploadsAllowed={canCreateDocuments}
            onFiles={(files) => queue.addFiles(status.batchId, files)}
            onSubmit={() => handleSubmit(status.batchId)}
            onCancel={() => handleCancel(status.batchId)}
          />
        )
      }
      if (status.status === "queued" || status.status === "running") {
        return withPermissionBanner(
          <BatchStatusCard
            batch={status}
            documentTypeName={documentTypeName(status.documentTypeId)}
            onCancel={() => handleCancel(status.batchId)}
          />
        )
      }
      // Finished, or cancelled (opened from the list, or cancelled in another
      // tab) - either way it no longer blocks a new import.
      return withPermissionBanner(
        <ImportResultsCard
          batch={status}
          documentTypeName={documentTypeName(status.documentTypeId)}
          onStartOver={handleStartOver}
        />
      )
    }

    if (bulkImport.activeLoading) {
      return <LoadingState message="Checking for an import in progress…" />
    }
    if (bulkImport.activeError) {
      return <ErrorPanel message={friendlyMessage(bulkImport.activeError, "Could not check for an import in progress.")} />
    }

    const active = bulkImport.active
    if (active) {
      return withPermissionBanner(
        <BatchStatusCard
          batch={active}
          documentTypeName={documentTypeName(active.documentTypeId)}
          onContinue={() => handleContinue(active.batchId)}
          onCancel={() => handleCancel(active.batchId)}
        />
      )
    }

    if (!canCreateDocuments) {
      return <ErrorPanel message={NEEDS_DOCUMENT_CREATE} />
    }

    return (
      <StartImportSteps
        documentTypes={documentTypes}
        documentTypesLoading={documentTypesLoading}
        documentTypesError={
          documentTypesError ? friendlyMessage(documentTypesError, "Could not load document types.") : null
        }
        creating={bulkImport.creating}
        onCreate={handleCreate}
      />
    )
  }

  return (
    <div ref={topRef} className="flex scroll-mt-6 flex-col gap-6">
      {renderMain()}
      <RecentImports selectedBatchId={openBatchId} onOpen={handleOpen} disabled={queue.busy} />
    </div>
  )
}

const NEEDS_DOCUMENT_CREATE =
  "You also need permission to create documents to use bulk import. Ask your administrator."

function ErrorPanel({ message }: { message: string }) {
  return (
    <div
      role="alert"
      className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card py-16 text-center shadow-[var(--ledger-shadow)]"
    >
      <AlertTriangle size={20} className="text-[var(--rust)]" />
      <p className="text-[13.5px] text-muted-foreground">{message}</p>
    </div>
  )
}
