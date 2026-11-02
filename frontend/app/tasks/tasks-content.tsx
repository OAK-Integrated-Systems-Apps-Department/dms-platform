"use client"

import { useState } from "react"
import { useSearchParams } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { DelegateTaskDialog } from "@/components/workflow/delegate-task-dialog"
import { MyTasksTable, stageLabel } from "@/components/workflow/my-tasks-table"
import { RequestsForMeTable } from "@/components/workflow/requests-for-me-table"
import { TaskActionDialog, type TaskDecisionKind } from "@/components/workflow/task-action-dialog"
import { SignAndApproveDialog } from "@/components/signatures/sign-and-approve-dialog"
import { isNetworkError } from "@/lib/api-error"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { usePageTitle } from "@/lib/use-page-title"
import { useMyInfoRequests } from "@/lib/workflow/use-my-info-requests"
import { useMyTaskCount } from "@/lib/workflow/use-my-task-count"
import { useMyTasks } from "@/lib/workflow/use-my-tasks"
import { useWorkflowActions } from "@/lib/workflow/use-workflow-actions"
import { cn } from "@/lib/utils"

import type { MyTaskDto, MyTasksView } from "@/lib/workflow/api"

const DEFAULT_PAGE_SIZE = 25

/** The task views, plus "Requests for me" (information requests addressed to me - not tasks). */
type View = MyTasksView | "requests"

const VIEWS: { value: View; label: string }[] = [
  { value: "all", label: "All" },
  { value: "assigned", label: "Assigned to me" },
  { value: "available", label: "Available to claim" },
  { value: "claimed", label: "Claimed by me" },
  { value: "waiting", label: "Waiting on info" },
  { value: "requests", label: "Requests for me" },
]

function isView(value: string | null): value is View {
  return VIEWS.some((v) => v.value === value)
}

/**
 * My Tasks (W18a) - every workflow task the caller can act on, from
 * `GET /api/v1/workflow/tasks/my`. Re-polls every 15 seconds and on window
 * focus, since pool tasks can be claimed by someone else at any time.
 * `?view=` picks the initial tab (the dashboard panel links here).
 */
export function TasksContent() {
  usePageTitle("My Tasks")

  const searchParams = useSearchParams()
  const initialView = searchParams.get("view")
  const [view, setView] = useState<View>(isView(initialView) ? initialView : "all")
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const [deciding, setDeciding] = useState<{ task: MyTaskDto; decision: TaskDecisionKind } | null>(null)
  const [delegating, setDelegating] = useState<MyTaskDto | null>(null)
  const requestsView = view === "requests"

  const { hasPermission } = usePermissions()
  const canApprove = hasPermission(PermissionCodes.WA_TASK_APPROVE)
  const canReject = hasPermission(PermissionCodes.WA_TASK_REJECT)

  const tasks = useMyTasks(requestsView ? "all" : view, page, pageSize)
  const requests = useMyInfoRequests("open", page, pageSize, requestsView)
  const { data, isPending, isError, error, refetch } = requestsView ? requests : tasks
  const { data: count } = useMyTaskCount()
  const actions = useWorkflowActions()

  function changeView(next: View) {
    setView(next)
    setPage(1)
  }

  return (
    <DashboardShell initialActiveLabel="My Tasks">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "My Tasks" }]} />
      <div className="mb-[22px] flex flex-wrap items-end justify-between gap-4">
        <div className="max-w-[680px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">My Tasks</h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Documents waiting for your approval. Tasks sent to a role or department are shared: claim one to take it
            on, or release it back to the pool.
          </p>
        </div>
        {count && (
          <p className="text-[13px] text-muted-foreground">
            <span className="font-semibold text-foreground">{count.open}</span> open
            {count.overdue > 0 && (
              <>
                {" · "}
                <span className="font-semibold text-[var(--rust)]">{count.overdue} overdue</span>
              </>
            )}
            {count.requestsForMe > 0 && (
              <>
                {" · "}
                <span className="font-semibold text-[var(--gold-foreground)]">{count.requestsForMe} asking you for information</span>
              </>
            )}
          </p>
        )}
      </div>

      <div className="mb-4 flex items-center gap-1 border-b border-border" role="tablist">
        {VIEWS.map((v) => (
          <button
            key={v.value}
            type="button"
            role="tab"
            aria-selected={view === v.value}
            onClick={() => changeView(v.value)}
            className={cn(
              "border-b-2 px-4 py-2.5 text-[13px] font-semibold transition-colors",
              view === v.value ? "border-primary text-primary" : "border-transparent text-muted-foreground hover:text-foreground"
            )}
          >
            {v.label}
          </button>
        ))}
      </div>

      {isPending ? (
        <LoadingState message="Loading your tasks…" />
      ) : isError ? (
        isNetworkError(error) ? (
          <BackendUnavailable onRetry={refetch} />
        ) : (
          <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
            {error instanceof Error ? error.message : "Could not load your tasks."}
          </div>
        )
      ) : (
        <>
          {requestsView ? (
            <RequestsForMeTable rows={requests.data?.content ?? []} />
          ) : (
            <MyTasksTable
              rows={tasks.data?.content ?? []}
              busy={actions.isActing}
              canApprove={canApprove}
              canReject={canReject}
              onClaim={(task) => void actions.claim(task.taskId)}
              onRelease={(task) => void actions.release(task.taskId)}
              onDecide={(task, decision) => setDeciding({ task, decision })}
              onDelegate={(task) => setDelegating(task)}
            />
          )}
          <TablePagination
            currentPage={page}
            totalPages={data?.totalPages ?? 1}
            onPageChange={setPage}
            pageSize={pageSize}
            onPageSizeChange={(size) => { setPageSize(size); setPage(1) }}
          />
        </>
      )}

      {delegating && (
        <DelegateTaskDialog
          mode="delegate"
          taskId={delegating.taskId}
          documentTitle={delegating.documentTitle ?? "Untitled document"}
          stageLabel={stageLabel(delegating)}
          onOpenChange={(open) => !open && setDelegating(null)}
        />
      )}

      {deciding && deciding.decision === "approve" && deciding.task.requiresSignature ? (
        <SignAndApproveDialog
          taskId={deciding.task.taskId}
          documentTitle={deciding.task.documentTitle ?? "Untitled document"}
          stageLabel={stageLabel(deciding.task)}
          versionChanged={deciding.task.versionChangedFlag}
          onOpenChange={(open) => !open && setDeciding(null)}
        />
      ) : (
        deciding && (
          <TaskActionDialog
            decision={deciding.decision}
            documentTitle={deciding.task.documentTitle ?? "Untitled document"}
            stageLabel={stageLabel(deciding.task)}
            versionChanged={deciding.task.versionChangedFlag}
            onOpenChange={(open) => !open && setDeciding(null)}
            onConfirm={async (text) => {
              const { task, decision } = deciding
              const result = decision === "approve" ? await actions.approve(task.taskId, text) : await actions.reject(task.taskId, text)
              return result !== null
            }}
          />
        )
      )}
    </DashboardShell>
  )
}
