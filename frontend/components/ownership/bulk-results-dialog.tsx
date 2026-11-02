"use client"

import { CheckCircle2 } from "lucide-react"

import { RestrictedConversionNotice } from "@/components/ownership/restricted-conversion-notice"
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogDescription,
  AlertDialogPopup,
  AlertDialogPortal,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { SKIP_REASON_LABELS } from "@/lib/ownership/api"

import type { BatchSummaryDto } from "@/lib/ownership/types"

interface BulkResultsDialogProps {
  summary: BatchSummaryDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** What a bulk transfer, a holding assignment or an offboarding did: moved, skipped (and why), access, Restricted. */
export function BulkResultsDialog({ summary, open, onOpenChange }: BulkResultsDialogProps) {
  return (
    <AlertDialog open={open && summary !== null} onOpenChange={onOpenChange}>
      <AlertDialogPortal>
        <AlertDialogPopup className="max-w-[520px] text-left">
          {summary && (
            <>
              <span className="mb-3 flex size-10 items-center justify-center rounded-md bg-[var(--sage-tint,var(--muted))] text-primary">
                <CheckCircle2 size={20} aria-hidden="true" />
              </span>
              <AlertDialogTitle>
                {summary.transferredCount.toLocaleString()}{" "}
                {summary.transferredCount === 1 ? "document" : "documents"} {summary.holding ? "parked" : "transferred"}
              </AlertDialogTitle>
              <AlertDialogDescription>
                {summary.newOwner ? (
                  <>
                    {summary.holding ? "Now held by" : "New owner:"} <strong className="font-semibold text-foreground">{summary.newOwner.name}</strong>
                    {summary.newOwnerDepartment && <> · {summary.newOwnerDepartment.name}</>}
                  </>
                ) : (
                  "The transfer finished."
                )}
              </AlertDialogDescription>
              <dl className="mb-4 grid grid-cols-2 gap-x-6 gap-y-2 text-[13px]">
                <Stat label="Looked at" value={summary.totalCount} />
                <Stat label="Transferred" value={summary.transferredCount} />
                <Stat label="Skipped" value={summary.skippedCount} />
                <Stat label="Given access" value={summary.accessGrantedCount} />
              </dl>
              <div className="mb-4 flex flex-col gap-3">
                <RestrictedConversionNotice count={summary.convertedToRestrictedCount} done />
                {summary.newOwnerLacksViewPermission && (
                  <p className="rounded-btn border border-border bg-muted px-3 py-2 text-[12.5px] text-muted-foreground">
                    The new owner doesn&apos;t hold the permission to view documents, so they can&apos;t open these yet.
                  </p>
                )}
                {summary.skipped.length > 0 && (
                  <ul aria-label="Skipped documents" className="rounded-btn border border-border bg-muted px-4 py-2.5 text-[12.5px]">
                    {summary.skipped.map((group) => (
                      <li key={group.reason} className="flex justify-between gap-3 py-0.5">
                        <span>{SKIP_REASON_LABELS[group.reason] ?? group.reason}</span>
                        <span className="font-semibold">{group.count.toLocaleString()}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div className="flex justify-end">
                <AlertDialogClose className="rounded-md border border-primary bg-primary px-5 py-2.5 text-[13px] font-bold text-primary-foreground">
                  Done
                </AlertDialogClose>
              </div>
            </>
          )}
        </AlertDialogPopup>
      </AlertDialogPortal>
    </AlertDialog>
  )
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-semibold">{value.toLocaleString()}</dd>
    </div>
  )
}
