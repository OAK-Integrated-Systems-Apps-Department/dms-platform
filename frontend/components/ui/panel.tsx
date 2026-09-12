import type { ReactNode } from "react"
import { ChevronRight } from "lucide-react"

interface PanelProps {
  title: string
  action?: string
  children: ReactNode
}

/**
 * The card shell every dashboard panel (Recent Documents, Pending
 * Approvals, Recent Activity, ...) is built on. New panel = wrap your
 * content in <Panel title="...">.
 */
export function Panel({ title, action, children }: PanelProps) {
  return (
    <div className="rounded-lg border border-border bg-card px-5 py-[18px] shadow-[var(--ledger-shadow)]">
      <div className="mb-1 flex items-center justify-between">
        <h2 className="font-heading text-[16.5px] font-semibold">{title}</h2>
        {action && (
          <button
            type="button"
            className="flex items-center gap-0.5 text-[12.5px] font-semibold text-primary"
          >
            {action}
            <ChevronRight size={13} />
          </button>
        )}
      </div>
      {children}
    </div>
  )
}
