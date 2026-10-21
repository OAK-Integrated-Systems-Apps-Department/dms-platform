"use client"

import Link from "next/link"
import { notFound, useParams } from "next/navigation"
import { toast } from "sonner"
import { ChevronRight, Download, Eye } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DiscussionPanel } from "@/components/comments/discussion-panel"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { Badge } from "@/components/ui/badge"
import { PinButton } from "@/components/pins/pin-button"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { SubmitToWorkflowButton } from "@/components/workflow/submit-to-workflow-button"
import { useDocumentPreview } from "@/components/workflow/use-document-preview"
import { WorkflowStatusPanel } from "@/components/workflow/workflow-status-panel"
import { DocumentSignaturesPanel } from "@/components/signatures/document-signatures-panel"
import { ApiError, friendlyMessage } from "@/lib/api-error"
import { downloadDocumentFile } from "@/lib/documents/api"
import { useDocument } from "@/lib/documents/use-document"
import { saveDownloadedFile } from "@/lib/download-file"
import { usePageTitle } from "@/lib/use-page-title"
import { formatDateTime } from "@/lib/utils"
import { useDocumentWorkflowHistory } from "@/lib/workflow/use-document-workflow-history"
import { useWorkflowInstance } from "@/lib/workflow/use-workflow-instance"
import { INSTANCE_STATUS_BADGE_VARIANT, INSTANCE_STATUS_LABELS } from "@/lib/workflow/workflow-badges"

/**
 * Minimal document page (W18a) - just enough to submit a document for
 * approval and follow it: title/type/status/latest version, "Submit for
 * approval", the current workflow's status panel (the newest in-progress
 * run, else the newest run) and a short workflow history. Preview,
 * metadata and upload are out of scope here.
 */
export default function DocumentPage() {
  const params = useParams<{ documentId: string }>()
  const { data: document, isPending, isError, error } = useDocument(params.documentId)
  const history = useDocumentWorkflowHistory(params.documentId)

  const runs = history.data?.content ?? []
  const current = runs.find((run) => run.status === "in_progress") ?? runs[0]
  const inProgress = current?.status === "in_progress"
  const instance = useWorkflowInstance(current?.instanceId)
  const preview = useDocumentPreview()

  usePageTitle(document ? document.title : "Document")

  async function downloadFile() {
    if (!document) return
    try {
      saveDownloadedFile(await downloadDocumentFile(document.documentId))
    } catch (error) {
      toast.error(friendlyMessage(error, "Could not download the document."))
    }
  }

  if (isError && error instanceof ApiError && error.status === 404) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="My Documents">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "My Documents", href: "/documents" },
          { label: document?.title ?? "Document" },
        ]}
      />

      {isPending ? (
        <LoadingState message="Loading document…" />
      ) : isError || !document ? (
        <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
          {error instanceof ApiError && error.status === 403
            ? "You don't have permission to view this document."
            : error instanceof Error
              ? error.message
              : "Could not load this document."}
        </div>
      ) : (
        <>
          <div className="mb-[22px] flex flex-wrap items-start justify-between gap-4">
            <div className="max-w-[640px]">
              <div className="mb-2 flex flex-wrap items-center gap-2.5">
                <h1 className="font-heading text-[26px] font-semibold tracking-[-0.01em]">{document.title}</h1>
                <Badge variant="outline" className="capitalize">{document.status.replace(/_/g, " ")}</Badge>
                <PinButton row={{ documentId: document.documentId, title: document.title }} />
              </div>
              <p className="text-[13px] text-muted-foreground">
                {document.documentTypeName ?? "—"} · Created {formatDateTime(document.createdAt)}
                {document.updatedAt && <> · Updated {formatDateTime(document.updatedAt)}</>}
              </p>
            </div>
            <SubmitToWorkflowButton
              documentId={document.documentId}
              documentTitle={document.title}
              hidden={inProgress || history.isPending}
            />
          </div>

          <div className="grid items-start gap-5 lg:grid-cols-[1.6fr_1fr]">
            <div className="flex min-w-0 flex-col gap-5">
              <Panel title="Document">
                <dl className="grid grid-cols-1 gap-x-6 gap-y-3 pt-3 sm:grid-cols-2">
                  <Field label="Type" value={document.documentTypeName ?? "—"} />
                  <Field label="Status" value={<span className="capitalize">{document.status.replace(/_/g, " ")}</span>} />
                  <Field label="Latest Version" value={document.latestVersionId ?? "—"} mono />
                  <Field label="Published Version" value={document.publishedVersionId ?? "—"} mono />
                </dl>
                {document.latestVersionId && (
                  <div className="mt-4 flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() =>
                        preview.open({
                          documentId: document.documentId,
                          title: document.title,
                          fileName: document.fileName,
                          mimeType: document.mimeType,
                          sizeBytes: document.sizeBytes,
                          documentTypeName: document.documentTypeName,
                          createdAt: document.createdAt,
                        })
                      }
                      className="flex items-center gap-1.5 rounded-btn border border-primary bg-primary/10 px-3.5 py-2 text-[13px] font-semibold text-primary"
                    >
                      <Eye size={15} /> Preview
                    </button>
                    <button
                      type="button"
                      onClick={() => void downloadFile()}
                      className="flex items-center gap-1.5 rounded-btn border border-[var(--border-strong)] bg-card px-3.5 py-2 text-[13px] font-semibold text-foreground"
                    >
                      <Download size={15} /> Download
                    </button>
                  </div>
                )}
              </Panel>

              {current ? (
                instance.isPending ? (
                  <LoadingState message="Loading workflow…" />
                ) : instance.data ? (
                  <WorkflowStatusPanel instance={instance.data} showDocumentLink={false} />
                ) : (
                  <p className="text-[13px] text-muted-foreground">Could not load the current workflow.</p>
                )
              ) : (
                !history.isPending && (
                  <div className="rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-8 text-center text-[13px] text-muted-foreground">
                    This document hasn&apos;t been submitted for approval yet.
                  </div>
                )
              )}

              <DocumentSignaturesPanel document={document} />
            </div>

            <div className="flex min-w-0 flex-col gap-5">
              <Panel title="Workflow History">
                {history.isPending ? (
                  <p className="pt-3 text-[13px] text-muted-foreground">Loading…</p>
                ) : history.isError ? (
                  <p className="pt-3 text-[13px] text-[var(--rust)]">Could not load this document&apos;s workflow history.</p>
                ) : runs.length === 0 ? (
                  <p className="pt-3 text-[13px] text-muted-foreground">No workflows yet.</p>
                ) : (
                  <ul className="divide-y divide-border pt-2">
                    {runs.map((run) => (
                      <li key={run.instanceId}>
                        <Link href={`/workflows/instances/${run.instanceId}`} className="flex items-center justify-between gap-3 rounded-sm px-2 py-2.5 hover:bg-muted">
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="text-[13px] font-semibold text-foreground">
                                {run.templateName} <span className="font-normal text-muted-foreground">v{run.templateVersionNumber}</span>
                              </span>
                              <Badge variant={INSTANCE_STATUS_BADGE_VARIANT[run.status]}>{INSTANCE_STATUS_LABELS[run.status]}</Badge>
                            </div>
                            <p className="mt-0.5 text-[12px] text-muted-foreground">
                              {run.initiatedBy?.displayName ?? "—"} · {formatDateTime(run.startedAt)}
                            </p>
                            {run.outcomeReason && (
                              <p className="mt-0.5 truncate text-[12px] text-muted-foreground" title={run.outcomeReason}>
                                {run.outcomeReason}
                              </p>
                            )}
                          </div>
                          <ChevronRight size={15} className="shrink-0 text-muted-foreground" />
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </Panel>
              <DiscussionPanel documentId={document.documentId} />
            </div>
          </div>
          {preview.element}
        </>
      )}
    </DashboardShell>
  )
}

function Field({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div>
      <dt className="text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">{label}</dt>
      <dd className={`mt-1 text-[13.5px] text-foreground ${mono ? "font-mono text-[12.5px] break-all" : ""}`}>{value}</dd>
    </div>
  )
}
