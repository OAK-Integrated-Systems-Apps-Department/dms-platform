"use client"

import { notFound, useParams } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { Panel } from "@/components/ui/panel"
import { WorkflowStatusPanel } from "@/components/workflow/workflow-status-panel"
import { WorkflowTimeline } from "@/components/workflow/workflow-timeline"
import { ApiError } from "@/lib/api-error"
import { usePageTitle } from "@/lib/use-page-title"
import { useWorkflowInstance } from "@/lib/workflow/use-workflow-instance"
import { workflowErrorMessage } from "@/lib/workflow/workflow-errors"

/**
 * One workflow run (WA-FR-005): the status panel (stages, tasks, outcome,
 * the caller's own task actions) beside the full timeline. Visible to the
 * run's participants, document viewers and audit-trail viewers - anyone
 * else gets the backend's 403.
 */
export default function WorkflowInstancePage() {
  const params = useParams<{ instanceId: string }>()
  const { data: instance, isPending, isError, error } = useWorkflowInstance(params.instanceId)

  usePageTitle(instance ? `${instance.documentTitle ?? "Document"} - ${instance.templateName}` : "Workflow Status")

  if (isError && error instanceof ApiError && error.status === 404) {
    notFound()
  }

  return (
    <DashboardShell initialActiveLabel="My Tasks">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "My Tasks", href: "/tasks" },
          ...(instance ? [{ label: instance.documentTitle ?? "Document", href: `/documents/${instance.documentId}` }] : []),
          { label: "Workflow Status" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Workflow Status</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Where this document is in its approval chain, who has decided, and who is next.
        </p>
      </div>

      {isPending ? (
        <LoadingState message="Loading workflow…" />
      ) : isError || !instance ? (
        <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
          {workflowErrorMessage(error, "Could not load this workflow.")}
        </div>
      ) : (
        <div className="grid items-start gap-5 lg:grid-cols-[1.6fr_1fr]">
          <WorkflowStatusPanel instance={instance} />
          <Panel title="Timeline">
            <WorkflowTimeline instanceId={instance.instanceId} />
          </Panel>
        </div>
      )}
    </DashboardShell>
  )
}
