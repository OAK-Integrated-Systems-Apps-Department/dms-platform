import type { CSSProperties } from "react"
import { Stamp } from "lucide-react"

import { DashCard } from "@/components/dashboard/dash-card"
import { cn } from "@/lib/utils"

/**
 * Approvals aren't built yet - there's no workflow module in dms-core - so
 * this says so instead of showing made-up tasks. `compact` lays it out as a
 * single strip, for when it has the row to itself.
 *
 * FOLLOW-UP: when approval workflows exist, list the documents waiting for
 * the user's sign-off here (with approve/reject), from a real endpoint.
 */
export function PendingApprovalsPanel({
  order,
  compact,
}: {
  order: number
  compact?: boolean
}) {
  return (
    <DashCard order={order} className={cn(compact && "py-4")}>
      <div
        className={cn(
          "flex flex-1 items-center gap-5",
          compact ? "flex-row" : "flex-col justify-center py-4 text-center"
        )}
      >
        <div className="dash-stack-scene shrink-0">
          {/* A seal, gently floating. */}
          <div
            className={cn(
              "dash-tile flex items-center justify-center rounded-2xl text-white",
              compact ? "size-11" : "size-16"
            )}
            style={
              {
                background:
                  "linear-gradient(145deg, color-mix(in oklab, var(--gold) 70%, white), var(--gold))",
                "--tile-depth": "color-mix(in oklab, var(--gold) 55%, black)",
                "--tile-glow": "var(--dash-gold-glow)",
                animation: "dash-float-seal 5s ease-in-out infinite",
              } as CSSProperties
            }
          >
            <Stamp size={compact ? 20 : 28} />
          </div>
        </div>
        <div className={cn(compact ? "min-w-0 flex-1" : "max-w-[320px]")}>
          <div
            className={cn(
              "flex flex-wrap items-center gap-2",
              !compact && "justify-center"
            )}
          >
            <h2 className="font-heading text-[15px] font-semibold">
              Pending my approval
            </h2>
            <span className="rounded-full bg-[var(--gold-tint)] px-2.5 py-0.5 text-[10.5px] font-semibold tracking-wide text-[var(--gold)] uppercase">
              Coming soon
            </span>
          </div>
          <p className="mt-1 text-[12.5px] text-muted-foreground">
            Approval workflows aren&apos;t set up yet. When they are, documents
            waiting for your sign-off will appear here.
          </p>
        </div>
      </div>
    </DashCard>
  )
}
