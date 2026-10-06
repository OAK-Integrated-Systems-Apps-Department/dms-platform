"use client"

import type { CSSProperties } from "react"
import { activityLook, activityWho } from "@/components/dashboard/activity"
import { History } from "lucide-react"

import {
  DashCard,
  DashSkeleton,
  EmptyState,
  Pending,
} from "@/components/dashboard/dash-card"
import { useNow } from "@/components/dashboard/motion"
import type { DashboardActivity } from "@/lib/dashboard/api"

/** Entries shown - enough to fill the column under "Needs your attention". */
const SHOWN = 8

/** Entries by the day they happened - "Today", "Yesterday", then the date. */
function groupByDay(entries: DashboardActivity[], now: Date | null) {
  const dayKey = (d: Date) =>
    `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
  const today = now ? dayKey(now) : ""
  const yesterday = now
    ? dayKey(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1))
    : ""
  const groups: { key: string; label: string; entries: DashboardActivity[] }[] =
    []
  for (const entry of entries) {
    const when = new Date(entry.occurredAt)
    const key = dayKey(when)
    const label =
      key === today
        ? "Today"
        : key === yesterday
          ? "Yesterday"
          : when.toLocaleDateString(undefined, {
              weekday: "long",
              day: "numeric",
              month: "short",
            })
    const last = groups[groups.length - 1]
    if (last?.key === key) last.entries.push(entry)
    else groups.push({ key, label, entries: [entry] })
  }
  return groups
}

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
  const now = useNow()
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
        <Pending
          skeleton={
            <div className="flex flex-col gap-4">
              {[1, 2, 3, 4].map((row) => (
                <div key={row} className="flex items-center gap-3">
                  <DashSkeleton className="size-8 shrink-0 rounded-full" />
                  <DashSkeleton className="h-3.5 flex-1" />
                </div>
              ))}
            </div>
          }
        />
      ) : activity.length === 0 ? (
        <EmptyState
          icon={History}
          title="No activity yet"
          text="Uploads, moves, restores and access changes will show up here."
        />
      ) : (
        <div className="flex flex-col gap-4">
          {groupByDay(activity.slice(0, SHOWN), now).map((group, g) => (
            <section key={group.key} aria-label={group.label}>
              <h3 className="mb-2.5 text-[11px] font-semibold tracking-[0.08em] text-muted-foreground uppercase">
                {group.label}
              </h3>
              <ol className="relative">
                {group.entries.map((entry, i) => {
                  const action = activityLook(entry.action)
                  const Icon = action.icon
                  const last = i === group.entries.length - 1
                  const who = activityWho(entry, currentUserId)
                  return (
                    <li
                      key={entry.id}
                      className="dash-row relative flex gap-3"
                      style={
                        {
                          "--dash-order": g * 3 + i,
                          paddingBottom: last ? 0 : 14,
                        } as CSSProperties
                      }
                    >
                      {!last && (
                        <span
                          aria-hidden="true"
                          className="absolute top-9 bottom-1 left-[15.5px] w-px bg-border"
                        />
                      )}
                      <span
                        aria-hidden="true"
                        className="z-10 flex size-8 shrink-0 items-center justify-center rounded-full"
                        style={{
                          color: action.color,
                          background: `color-mix(in oklab, ${action.color} 14%, var(--card))`,
                          boxShadow: `inset 0 0 0 1px color-mix(in oklab, ${action.color} 30%, transparent)`,
                        }}
                      >
                        <Icon size={14} />
                      </span>
                      <div className="flex min-w-0 flex-1 items-start justify-between gap-3 pt-1">
                        <p className="min-w-0 text-[13px] leading-snug">
                          <b className="font-semibold">{who}</b> {action.verb}{" "}
                          {entry.resourceName && (
                            <span className="break-words text-muted-foreground">
                              {entry.resourceName}
                            </span>
                          )}
                        </p>
                        <time
                          dateTime={entry.occurredAt}
                          title={new Date(entry.occurredAt).toLocaleString()}
                          className="shrink-0 pt-px text-[11.5px] text-[var(--ink-faint)] tabular-nums"
                        >
                          {new Date(entry.occurredAt).toLocaleTimeString(
                            undefined,
                            { hour: "2-digit", minute: "2-digit" }
                          )}
                        </time>
                      </div>
                    </li>
                  )
                })}
              </ol>
            </section>
          ))}
        </div>
      )}
    </DashCard>
  )
}
