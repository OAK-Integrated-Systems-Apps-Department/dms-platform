"use client"

import Link from "next/link"
import { CheckCircle2, Hand } from "lucide-react"

import { Panel } from "@/components/ui/panel"
import { useMyTasks } from "@/lib/workflow/use-my-tasks"
import { formatDateTime } from "@/lib/utils"

const PANEL_TASK_COUNT = 5

const actionClass =
  "flex items-center gap-[5px] rounded-sm border border-primary bg-primary/10 px-[11px] py-[6px] text-xs font-semibold text-primary"

/**
 * The caller's first few open workflow tasks (the same queue as My Tasks,
 * polled the same way). Decisions happen on /tasks, where the approve/
 * reject dialog lives - each row links there.
 * Self-contained - delete this file's import from the page to remove the panel.
 */
export function PendingApprovalsPanel() {
  const { data, isPending, isError } = useMyTasks("all", 1, PANEL_TASK_COUNT)
  const tasks = data?.content ?? []

  return (
    <Panel title="Pending my approval" action="View all" actionHref="/tasks">
      {isPending ? (
        <p className="py-3 text-[12.5px] text-muted-foreground">Loading…</p>
      ) : isError ? (
        <p className="py-3 text-[12.5px] text-[var(--rust)]">Could not load your tasks.</p>
      ) : tasks.length === 0 ? (
        <p className="py-3 text-[12.5px] text-muted-foreground">Nothing is waiting for your approval.</p>
      ) : (
        <div>
          {tasks.map((task, index) => {
            const needsClaim = task.assigneeType !== "user" && task.status === "pending"
            return (
              <div
                key={task.taskId}
                className="py-3"
                style={index === 0 ? undefined : { borderTop: "1px solid var(--border)" }}
              >
                <Link href={`/documents/${task.documentId}`} className="mb-[3px] block text-[13px] font-medium hover:text-primary">
                  {task.documentTitle ?? "Untitled document"}
                </Link>
                <div className="mb-[9px] text-[11.5px] text-muted-foreground">
                  From {task.initiatedBy?.displayName ?? "—"} · Stage {task.stageOrder} of {task.totalStages}: {task.stageName}
                  {task.dueAt && (
                    <>
                      {" · "}
                      <span className={task.overdue ? "font-semibold text-[var(--rust)]" : "font-semibold text-[var(--gold)]"}>
                        {task.overdue ? "Overdue" : `Due ${formatDateTime(task.dueAt)}`}
                      </span>
                    </>
                  )}
                </div>
                <Link href={needsClaim ? "/tasks?view=available" : "/tasks"} className={`${actionClass} w-fit`}>
                  {needsClaim ? <Hand size={13.5} /> : <CheckCircle2 size={13.5} />}
                  {needsClaim ? "Claim in My Tasks" : "Review in My Tasks"}
                </Link>
              </div>
            )
          })}
        </div>
      )}
    </Panel>
  )
}
