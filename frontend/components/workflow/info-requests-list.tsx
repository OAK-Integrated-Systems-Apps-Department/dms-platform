"use client"

import { useState } from "react"
import { Forward, MessageSquareReply, MessageSquareX, Undo2 } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { useWorkflowActions } from "@/lib/workflow/use-workflow-actions"
import { INFO_REQUEST_STATUS_BADGE_VARIANT, INFO_REQUEST_STATUS_LABELS } from "@/lib/workflow/workflow-badges"
import { formatDateTime } from "@/lib/utils"

import type { InfoRequestDto } from "@/lib/workflow/api"

import { RequestInfoDialog } from "./request-info-dialog"
import { RespondInfoDialog } from "./respond-info-dialog"
import { SupportingDocumentRow } from "./supporting-documents-list"

const BTN = "flex items-center gap-1 border disabled:cursor-not-allowed disabled:opacity-50 h-[38px] rounded-btn px-4 text-[13px] font-semibold justify-center"

interface InfoRequestsListProps {
  requests: InfoRequestDto[]
  documentTitle: string
  currentUserId: string | undefined
  /** The submitter or a tenant admin may redirect an open request. */
  canRedirect: boolean
  inProgress: boolean
}

/** A run's information requests, with what the caller can do on each (answer, decline, withdraw, redirect). */
export function InfoRequestsList({ requests, documentTitle, currentUserId, canRedirect, inProgress }: InfoRequestsListProps) {
  const actions = useWorkflowActions()
  const [responding, setResponding] = useState<{ request: InfoRequestDto; mode: "respond" | "decline" } | null>(null)
  const [redirecting, setRedirecting] = useState<InfoRequestDto | null>(null)

  if (requests.length === 0) return null

  return (
    <div>
      <p className="mb-2 text-[12px] font-bold tracking-[0.08em] text-muted-foreground uppercase">Information requests</p>
      <ul className="flex flex-col gap-2">
        {requests.map((r) => {
          const open = r.status === "open" && inProgress
          const forMe = open && currentUserId === r.requestedFrom.userId
          const mine = open && currentUserId === r.requestedBy.userId
          return (
            <li key={r.infoRequestId} className="rounded-md border border-border px-3.5 py-2.5 text-[12.5px]">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-foreground">
                  <span className="font-semibold">{r.requestedBy.displayName ?? "An approver"}</span> asked{" "}
                  <span className="font-semibold">{r.requestedFrom.displayName ?? "someone"}</span>
                  {r.stageOrder != null && <span className="text-muted-foreground"> · Stage {r.stageOrder}</span>}
                </span>
                <Badge variant={INFO_REQUEST_STATUS_BADGE_VARIANT[r.status]}>{INFO_REQUEST_STATUS_LABELS[r.status]}</Badge>
                <span className="font-mono text-[11.5px] text-muted-foreground">{formatDateTime(r.requestedAt)}</span>
                {r.dueAt && r.status === "open" && (
                  <span className="text-[11.5px] text-muted-foreground">· answer by {formatDateTime(r.dueAt)}</span>
                )}
              </div>
              <p className="mt-1 whitespace-pre-wrap text-foreground">{r.message}</p>
              {r.responseMessage && (
                <p className="mt-1.5 rounded-sm border-l-2 border-primary bg-primary/5 px-2.5 py-1.5 whitespace-pre-wrap text-foreground">
                  {r.responseMessage}
                </p>
              )}
              {r.declineReason && (
                <p className="mt-1.5 rounded-sm border-l-2 border-[var(--rust)] bg-[var(--rust-tint)] px-2.5 py-1.5 whitespace-pre-wrap">
                  Declined: {r.declineReason}
                </p>
              )}
              {r.cancelReason && r.status === "cancelled" && (
                <p className="mt-1.5 text-[12px] text-muted-foreground">Cancelled: {r.cancelReason}</p>
              )}
              {r.supportingDocuments.length > 0 && (
                <ul className="mt-1.5 flex flex-col gap-1">
                  {r.supportingDocuments.map((d) => (
                    <SupportingDocumentRow key={d.supportingDocumentId} document={d} />
                  ))}
                </ul>
              )}
              {(forMe || mine || (open && canRedirect)) && (
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {forMe && (
                    <>
                      <button type="button" disabled={actions.isActing} onClick={() => setResponding({ request: r, mode: "respond" })} className={`${BTN} border-primary bg-primary text-primary-foreground`}>
                        <MessageSquareReply size={12} /> Answer
                      </button>
                      <button type="button" disabled={actions.isActing} onClick={() => setResponding({ request: r, mode: "decline" })} className={`${BTN} border-[var(--rust)] bg-card text-[var(--rust)]`}>
                        <MessageSquareX size={12} /> Decline
                      </button>
                    </>
                  )}
                  {mine && (
                    <button type="button" disabled={actions.isActing} onClick={() => void actions.withdrawInfo(r.infoRequestId)} className={`${BTN} border-[var(--border-strong)] bg-card text-muted-foreground`}>
                      <Undo2 size={12} /> Withdraw
                    </button>
                  )}
                  {open && canRedirect && (
                    <button type="button" disabled={actions.isActing} onClick={() => setRedirecting(r)} className={`${BTN} border-[var(--border-strong)] bg-card text-muted-foreground`}>
                      <Forward size={12} /> Redirect
                    </button>
                  )}
                </div>
              )}
            </li>
          )
        })}
      </ul>

      {responding && (
        <RespondInfoDialog
          request={responding.request}
          mode={responding.mode}
          onOpenChange={(o) => !o && setResponding(null)}
        />
      )}
      {redirecting && (
        <RequestInfoDialog
          mode="redirect"
          infoRequestId={redirecting.infoRequestId}
          documentTitle={documentTitle}
          onOpenChange={(o) => !o && setRedirecting(null)}
        />
      )}
    </div>
  )
}
