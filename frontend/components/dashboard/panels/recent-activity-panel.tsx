"use client"

import type { CSSProperties } from "react"
import { activityLook, activityWho } from "@/components/dashboard/activity"
import { DashCard, DashSkeleton } from "@/components/dashboard/dash-card"
import { FriendlyDate } from "@/components/documents/browser-items"
import type { DashboardActivity } from "@/lib/dashboard/api"

/** Entries shown - enough to sit level with the recent documents beside it. */
const SHOWN = 6

/** What people did lately - the user's own actions, or everyone's with the audit-history permission. */
export function RecentActivityPanel({
  activity,
  scope,
  currentUserId,
  order,
}: {
  /** Absent while loading. */
  activity?: DashboardActivity[]
  scope?: "mine" | "organisation"
  currentUserId?: string
  order: number
}) {
  return (
    <DashCard
      order={order}
      title="Recent activity"
      subtitle={
        scope === "organisation"
          ? "Across your organisation"
          : "What you've done lately"
      }
      link={
        scope === "organisation"
          ? { href: "/admin/audit-log", label: "Audit log" }
          : undefined
      }
    >
      {!activity ? (
        <div className="flex flex-col gap-4">
          {[1, 2, 3, 4].map((row) => (
            <div key={row} className="flex items-center gap-3">
              <DashSkeleton className="size-8 shrink-0 rounded-full" />
              <DashSkeleton className="h-3.5 flex-1" />
            </div>
          ))}
        </div>
      ) : activity.length === 0 ? (
        <p className="py-10 text-center text-[13px] text-muted-foreground">
          Nothing yet.
        </p>
      ) : (
        <ol className="relative">
          {activity.slice(0, SHOWN).map((entry, i) => {
            const action = activityLook(entry.action)
            const Icon = action.icon
            const last = i === Math.min(activity.length, SHOWN) - 1
            const who = activityWho(entry, currentUserId)
            return (
              <li
                key={entry.id}
                className="dash-row relative flex gap-3"
                style={
                  {
                    "--dash-order": i,
                    paddingBottom: last ? 0 : 16,
                  } as CSSProperties
                }
              >
                {!last && (
                  <span
                    aria-hidden="true"
                    className="absolute top-9 bottom-1 left-[15.5px] w-px bg-gradient-to-b from-border to-transparent"
                  />
                )}
                <span
                  className="z-10 flex size-8 shrink-0 items-center justify-center rounded-full text-white"
                  style={{
                    background: `linear-gradient(145deg, color-mix(in oklab, ${action.color} 75%, white), ${action.color})`,
                    boxShadow: `0 3px 0 color-mix(in oklab, ${action.color} 55%, black), 0 6px 12px -6px ${action.color}`,
                  }}
                >
                  <Icon size={14} />
                </span>
                <div className="min-w-0 pt-1">
                  <p className="text-[13px] leading-snug">
                    <b className="font-semibold">{who}</b> {action.verb}{" "}
                    {entry.resourceName && (
                      <span className="break-words text-muted-foreground">
                        {entry.resourceName}
                      </span>
                    )}
                  </p>
                  <p className="mt-0.5 text-[11.5px] text-[var(--ink-faint)]">
                    <FriendlyDate iso={entry.occurredAt} />
                  </p>
                </div>
              </li>
            )
          })}
        </ol>
      )}
    </DashCard>
  )
}
