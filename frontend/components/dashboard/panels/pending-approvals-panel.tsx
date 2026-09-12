import { CheckCircle2, XCircle } from "lucide-react"

import { pendingApprovals } from "@/components/dashboard/data"
import { Panel } from "@/components/ui/panel"

const approveClass =
  "flex items-center gap-[5px] rounded-sm border border-primary bg-primary/10 px-[11px] py-[6px] text-xs font-semibold text-primary"

const rejectClass =
  "flex items-center gap-[5px] rounded-sm border border-[var(--border-strong)] bg-card px-[11px] py-[6px] text-xs font-semibold text-muted-foreground"

/** Self-contained — delete this file's import from the page to remove the panel. */
export function PendingApprovalsPanel() {
  return (
    <Panel title="Pending my approval" action="View all">
      <div>
        {pendingApprovals.map((task, index) => (
          <div
            key={task.id}
            className="py-3"
            style={index === 0 ? undefined : { borderTop: "1px solid var(--border)" }}
          >
            <div className="mb-[3px] text-[13px] font-medium">{task.name}</div>
            <div className="mb-[9px] text-[11.5px] text-muted-foreground">
              From {task.requester} · {task.size} ·{" "}
              <span className="font-semibold text-[var(--gold)]">{task.due}</span>
            </div>
            <div className="flex gap-2">
              <button type="button" className={approveClass}>
                <CheckCircle2 size={13.5} />
                Approve
              </button>
              <button type="button" className={rejectClass}>
                <XCircle size={13.5} />
                Reject
              </button>
            </div>
          </div>
        ))}
      </div>
    </Panel>
  )
}
