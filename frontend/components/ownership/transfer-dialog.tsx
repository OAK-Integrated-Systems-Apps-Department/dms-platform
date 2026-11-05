"use client"

import { useState } from "react"

import { SearchableSelect } from "@/components/ui/searchable-select"
import { useQuery } from "@tanstack/react-query"

import { ActionDialog } from "@/components/folders/folder-dialogs"
import { RestrictedConversionNotice } from "@/components/ownership/restricted-conversion-notice"
import { Checkbox } from "@/components/ui/checkbox"
import { Textarea } from "@/components/ui/textarea"
import { PersonPicker } from "@/components/workflow/person-picker"
import { friendlyMessage } from "@/lib/api-error"
import {
  MAX_TRANSFER_DOCUMENTS,
  mergeBatches,
  previewBulkTransfer,
  previewDocumentTransfer,
  tooLargeTotal,
  transferAllInSteps,
  transferBulk,
  transferDocument,
} from "@/lib/ownership/api"
import { useDepartmentOptions } from "@/lib/workflow/use-participant-options"
import { lookupPeople } from "@/lib/workflow/api"

import type {
  BatchSummaryDto,
  BulkTransferRequestDto,
  TransferDocumentResponseDto,
  TransferMode,
} from "@/lib/ownership/types"
import type { PersonOptionDto } from "@/lib/workflow/api"

/** One document, or a run over a source's documents ("all" of them, or a "selected" few). */
export type TransferSubject =
  | { kind: "document"; documentId: string; title: string }
  | {
      kind: "bulk"
      sourceUserId?: string
      sourceDepartmentId?: string
      sourceLabel: string
      mode: TransferMode
      documentIds?: string[]
      excludeDocumentIds?: string[]
      /** How many documents the run covers - decides whether it is split into steps of 5,000. */
      total: number
    }

export type TransferOutcome =
  | { kind: "document"; result: TransferDocumentResponseDto }
  | { kind: "bulk"; summary: BatchSummaryDto }

interface TransferDialogProps {
  subject: TransferSubject
  open: boolean
  onOpenChange: (open: boolean) => void
  onDone?: (outcome: TransferOutcome) => void
}

/**
 * Transfer ownership of one document, or of a user's or department's documents. Before confirming it asks the
 * server what would happen and says how many documents would be switched to Restricted (the new owner couldn't
 * open them otherwise). More than 5,000 documents are moved in steps of 5,000, automatically, with progress.
 * Remount (new `key`) per opening.
 */
export function TransferDialog({ subject, open, onOpenChange, onDone }: TransferDialogProps) {
  const departments = useDepartmentOptions(open)
  const [person, setPerson] = useState<PersonOptionDto | null>(null)
  const [departmentId, setDepartmentId] = useState("")
  const [toHolding, setToHolding] = useState(false)
  const [reorganization, setReorganization] = useState(false)
  const [grantAccess, setGrantAccess] = useState(true)
  const [note, setNote] = useState("")
  const [progress, setProgress] = useState<string | null>(null)

  const bulk = subject.kind === "bulk"
  const total = bulk ? subject.total : 1
  const needsSplit = bulk && total > MAX_TRANSFER_DOCUMENTS
  const ready = toHolding || person !== null

  /** Everything of a bulk request except which documents: who they leave, where they go, and the options. */
  function bulkBase(): Omit<BulkTransferRequestDto, "mode" | "limit" | "documentIds"> {
    if (subject.kind !== "bulk") throw new Error("Not a bulk transfer")
    return {
      sourceUserId: subject.sourceUserId,
      sourceDepartmentId: subject.sourceDepartmentId,
      excludeDocumentIds: subject.excludeDocumentIds?.length ? subject.excludeDocumentIds : undefined,
      target: toHolding
        ? { type: "holding", departmentId: departmentId || subject.sourceDepartmentId || null }
        : { type: "user", newOwnerUserId: person?.userId, newOwnerDepartmentId: departmentId || null },
      reason: reorganization ? "reorganization" : "manual",
      grantAccess,
      note: note.trim() || undefined,
    }
  }

  function bulkRequest(limit?: number): BulkTransferRequestDto {
    if (subject.kind !== "bulk") throw new Error("Not a bulk transfer")
    return {
      ...bulkBase(),
      mode: subject.mode,
      documentIds: subject.mode === "selected" ? subject.documentIds : undefined,
      limit,
    }
  }

  const preview = useQuery({
    queryKey: ["ownership", "transfer-preview", subject, person?.userId, departmentId, toHolding, grantAccess],
    queryFn: async () => {
      if (subject.kind === "document") {
        const p = await previewDocumentTransfer(subject.documentId, {
          newOwnerUserId: person!.userId,
          newOwnerDepartmentId: departmentId || null,
          grantAccess,
        })
        return { convert: p.willConvertToRestricted ? 1 : 0, lacksView: p.newOwnerLacksViewPermission, unchanged: p.alreadyOwner }
      }
      // A selection above the limit previews its first step; "all" above it asks for a limit too.
      const request = bulkRequest(needsSplit && subject.mode === "all" ? MAX_TRANSFER_DOCUMENTS : undefined)
      if (needsSplit && subject.mode === "selected") {
        request.documentIds = subject.documentIds?.slice(0, MAX_TRANSFER_DOCUMENTS)
      }
      const p = await previewBulkTransfer(request)
      return { convert: p.willConvertToRestrictedCount, lacksView: p.newOwnerLacksViewPermission, unchanged: false }
    },
    enabled: open && ready,
    retry: false,
  })

  async function submit(): Promise<string | null> {
    if (!ready) return "Choose who the documents go to."
    setProgress(null)
    try {
      if (subject.kind === "document") {
        const result = await transferDocument(subject.documentId, {
          newOwnerUserId: person!.userId,
          newOwnerDepartmentId: departmentId || null,
          grantAccess,
          note: note.trim() || undefined,
        })
        onDone?.({ kind: "document", result })
        return null
      }
      const summary = await runBulk(subject)
      onDone?.({ kind: "bulk", summary })
      return null
    } catch (error) {
      return friendlyMessage(error, "Could not transfer.")
    } finally {
      setProgress(null)
    }
  }

  async function runBulk(subject: Extract<TransferSubject, { kind: "bulk" }>): Promise<BatchSummaryDto> {
    const report = (done: number) => setProgress(`Moved ${done.toLocaleString()} of ${total.toLocaleString()} documents…`)
    if (subject.mode === "selected") {
      const ids = subject.documentIds ?? []
      const batches: BatchSummaryDto[] = []
      for (let from = 0; from < ids.length; from += MAX_TRANSFER_DOCUMENTS) {
        const step = { ...bulkRequest(), documentIds: ids.slice(from, from + MAX_TRANSFER_DOCUMENTS) }
        batches.push(await transferBulk(step))
        report(Math.min(ids.length, from + MAX_TRANSFER_DOCUMENTS))
      }
      return mergeBatches(batches)
    }
    const rest = bulkBase()
    if (needsSplit) {
      const { batches } = await transferAllInSteps(rest, (done) => report(done))
      return mergeBatches(batches)
    }
    try {
      return await transferBulk(bulkRequest())
    } catch (error) {
      // The count on screen was out of date and the set grew past the limit: fall back to steps.
      if (tooLargeTotal(error) === null) throw error
      const { batches } = await transferAllInSteps(rest, (done) => report(done))
      return mergeBatches(batches)
    }
  }

  const heading =
    subject.kind === "document"
      ? `Transfer “${subject.title}”`
      : subject.mode === "selected"
        ? `Transfer ${total.toLocaleString()} selected ${total === 1 ? "document" : "documents"}`
        : `Transfer all documents of ${subject.sourceLabel}`

  return (
    <ActionDialog
      open={open}
      onOpenChange={onOpenChange}
      title={heading}
      description={
        subject.kind === "document"
          ? "The previous owner is kept in the document's ownership history."
          : "The previous owners are kept in each document's ownership history."
      }
      confirmLabel="Transfer"
      pendingLabel={progress ?? "Transferring…"}
      onConfirm={submit}
      disabled={!ready}
    >
      <div className="flex flex-col gap-3.5">
        {bulk && (
          <Checkbox
            label="Hand them to the department's holding owner instead"
            hint="Parks the documents until someone is chosen to take them over."
            checked={toHolding}
            onCheckedChange={setToHolding}
          />
        )}
        {!toHolding && (
          <div className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
            New owner
            <PersonPicker
              queryKey={["ownership-transfer"]}
              fetchPeople={(search) => lookupPeople(search, 20)}
              value={person}
              onChange={setPerson}
              placeholder="Search for the new owner"
            />
          </div>
        )}
        <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
          {toHolding ? "Holding department" : "Department (optional)"}
          <SearchableSelect
            inline
            options={departments.data ?? []}
            selected={departmentId}
            onChange={setDepartmentId}
            allLabel={toHolding ? "The source's own department" : "The new owner's own department"}
            searchPlaceholder="Search departments"
          />
        </label>
        {bulk && !toHolding && (
          <Checkbox
            label="This is a department reorganisation"
            checked={reorganization}
            onCheckedChange={setReorganization}
          />
        )}
        <Checkbox
          label="Also give the new owner access"
          hint="If they couldn't open a document, they are given Can manage on it. The previous owner's access is never removed."
          checked={grantAccess}
          onCheckedChange={setGrantAccess}
        />
        {grantAccess && preview.data && (
          <RestrictedConversionNotice count={preview.data.convert} partial={needsSplit} />
        )}
        {preview.data?.lacksView && (
          <p className="rounded-btn border border-border bg-muted px-3 py-2 text-[12.5px] text-muted-foreground">
            The new owner doesn&apos;t hold the permission to view documents, so they still won&apos;t be able to open
            these until an administrator gives them a role with it.
          </p>
        )}
        {preview.data?.unchanged && (
          <p className="text-[12.5px] text-[var(--rust)]">That person already owns this document.</p>
        )}
        {needsSplit && (
          <p className="rounded-btn border border-border bg-muted px-3 py-2 text-[12.5px] text-muted-foreground">
            A transfer moves at most {MAX_TRANSFER_DOCUMENTS.toLocaleString()} documents at a time, so these{" "}
            {total.toLocaleString()} will be moved in steps of {MAX_TRANSFER_DOCUMENTS.toLocaleString()}, one after the
            other. You don&apos;t need to do anything - keep this window open until it finishes.
          </p>
        )}
        <label className="flex flex-col gap-1.5 text-[12.5px] font-semibold">
          Note (optional)
          <Textarea
            aria-label="Note"
            rows={2}
            maxLength={1000}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            className="min-h-[60px] font-normal"
          />
        </label>
      </div>
    </ActionDialog>
  )
}
