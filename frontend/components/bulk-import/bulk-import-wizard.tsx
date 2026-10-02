"use client"

import { useState, type ReactNode } from "react"
import { AlertTriangle, RotateCcw } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage } from "@/lib/api-error"
import { isFinished } from "@/lib/bulk-imports/api"
import { useBulkImport } from "@/lib/bulk-imports/use-bulk-import"
import { useImportDocumentTypes } from "@/lib/bulk-imports/use-import-document-types"
import { useUploadQueue } from "@/lib/bulk-imports/use-upload-queue"

import { BatchStatusCard } from "./batch-status-card"
import { ImportResultsCard } from "./import-results-card"
import { StartImportSteps } from "./start-import-steps"
import { InlineError, StepCard } from "./step-card"
import { UploadFilesStep } from "./upload-files-step"

/**
 * Bulk Import screen body. What it shows is derived from the server:
 * - no batch being worked on: the caller's active batch (resume / queued /
 *   running card), or steps 1-2 to start a new one;
 * - a batch being worked on (or a submitted one found on load): steps 3-4
 *   while it's `uploading`, the submitted card - polled for progress -
 *   while it's `queued`/`running`, and the results once it's finished.
 *
 * Without document-create (`canCreateDocuments` false) a new import can't be
 * started, and an existing one is shown under a banner with uploading and
 * submitting disabled - status, results and Cancel stay available, matching
 * the backend, which keeps those endpoints FIE-only.
 */
export function BulkImportWizard({ canCreateDocuments }: { canCreateDocuments: boolean }) {
  const [batchId, setBatchId] = useState<string | null>(null)
  const [createdNotice, setCreatedNotice] = useState<string | null>(null)

  const bulkImport = useBulkImport(batchId)
  const { documentTypes, isLoading: documentTypesLoading, error: documentTypesError } = useImportDocumentTypes()
  const queue = useUploadQueue(() => {
    void bulkImport.refetchStatus()
  })

  function documentTypeName(documentTypeId: string): string | null {
    return documentTypes.find((type) => type.documentTypeId === documentTypeId)?.name ?? null
  }

  async function handleCreate(template: File) {
    const result = await bulkImport.create(template)
    if (result.batch) {
      const { batchId: createdId, totalRecords } = result.batch
      setCreatedNotice(
        `Template accepted - ${totalRecords} ${totalRecords === 1 ? "file" : "files"} expected.`
      )
      setBatchId(createdId)
    }
    return result
  }

  function handleStartOver() {
    queue.reset()
    setCreatedNotice(null)
    setBatchId(null)
    void bulkImport.clearActive()
  }

  function handleContinue(id: string) {
    setCreatedNotice(null)
    setBatchId(id)
  }

  async function handleSubmit(id: string) {
    const error = await bulkImport.submit(id)
    if (!error) {
      setCreatedNotice(null)
      queue.reset()
      toast.success("Import submitted.")
    }
    return error
  }

  async function handleCancel(id: string) {
    const error = await bulkImport.cancel(id)
    if (!error) {
      queue.reset()
      setCreatedNotice(null)
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

  // Working on a specific batch (just created, resumed, or already
  // submitted when the screen loaded).
  if (batchId ?? bulkImport.watchedId) {
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
          createdNotice={createdNotice}
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
    if (isFinished(status.status)) {
      return withPermissionBanner(
        <ImportResultsCard
          batch={status}
          documentTypeName={documentTypeName(status.documentTypeId)}
          onStartOver={handleStartOver}
        />
      )
    }
    // Cancelled elsewhere (another tab) - it no longer blocks a new import.
    return (
      <StepCard
        title="This import was cancelled"
        description="Start a new import to upload more documents."
      >
        <Button onClick={() => handleStartOver()}>
          <RotateCcw />
          Start a new import
        </Button>
      </StepCard>
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
