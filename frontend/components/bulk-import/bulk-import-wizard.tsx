"use client"

import { useState } from "react"
import { AlertTriangle, RotateCcw } from "lucide-react"
import { toast } from "sonner"

import { Button } from "@/components/ui/button"
import { LoadingState } from "@/components/ui/loading-state"
import { friendlyMessage } from "@/lib/api-error"
import { useBulkImport } from "@/lib/bulk-imports/use-bulk-import"
import { useImportDocumentTypes } from "@/lib/bulk-imports/use-import-document-types"
import { useUploadQueue } from "@/lib/bulk-imports/use-upload-queue"

import { BatchStatusCard } from "./batch-status-card"
import { StartImportSteps } from "./start-import-steps"
import { StepCard } from "./step-card"
import { UploadFilesStep } from "./upload-files-step"

/**
 * Bulk Import screen body. What it shows is derived from the server:
 * - no batch being worked on: the caller's active batch (resume / queued /
 *   running card), or steps 1-2 to start a new one;
 * - a batch being worked on: steps 3-4 while it's `uploading`, the
 *   submitted card once it's `queued`.
 */
export function BulkImportWizard() {
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

  // Working on a specific batch (just created, or resumed).
  if (batchId) {
    const status = bulkImport.status
    if (!status) {
      return bulkImport.statusLoading ? (
        <LoadingState message="Loading import…" />
      ) : (
        <ErrorPanel message="Could not load this import." />
      )
    }
    if (status.status === "uploading") {
      return (
        <UploadFilesStep
          status={status}
          createdNotice={createdNotice}
          entries={queue.entries}
          uploading={queue.busy}
          onFiles={(files) => queue.addFiles(status.batchId, files)}
          onSubmit={() => handleSubmit(status.batchId)}
          onCancel={() => handleCancel(status.batchId)}
        />
      )
    }
    if (status.status === "queued" || status.status === "running") {
      return (
        <BatchStatusCard
          batch={status}
          documentTypeName={documentTypeName(status.documentTypeId)}
          onCancel={() => handleCancel(status.batchId)}
        />
      )
    }
    // Cancelled or finished elsewhere (another tab, or the worker) - it no
    // longer blocks a new import.
    return (
      <StepCard
        title={status.status === "cancelled" ? "This import was cancelled" : "This import has finished"}
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
    return (
      <BatchStatusCard
        batch={active}
        documentTypeName={documentTypeName(active.documentTypeId)}
        onContinue={() => handleContinue(active.batchId)}
        onCancel={() => handleCancel(active.batchId)}
      />
    )
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
