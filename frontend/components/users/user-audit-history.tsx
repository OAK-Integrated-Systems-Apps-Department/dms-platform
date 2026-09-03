"use client"

import { useMemo, useState } from "react"
import Link from "next/link"
import { ChevronDown, X } from "lucide-react"

import { renderActivityText } from "./render-activity-text"
import type { UserActivityEntry, UserRecord } from "./types"

interface UserAuditHistoryProps {
  user: UserRecord
}

const PAGE_SIZE = 5

const EVENT_TYPE_OPTIONS: {
  value: UserActivityEntry["type"] | "all"
  label: string
}[] = [
  { value: "all", label: "All event types" },
  { value: "login", label: "Login" },
  { value: "failed-login", label: "Failed login" },
  { value: "role-change", label: "Role changes" },
  { value: "status-change", label: "Activation / deactivation" },
  { value: "password-reset", label: "Password reset" },
  { value: "mfa", label: "MFA" },
  { value: "document", label: "Document activity" },
  { value: "other", label: "Other" },
]

const DATE_RANGE_OPTIONS = [
  { value: "all", label: "All time" },
  { value: "7", label: "Last 7 days" },
  { value: "30", label: "Last 30 days" },
  { value: "90", label: "Last 90 days" },
  { value: "custom", label: "Custom range" },
] as const

/**
 * `UserActivityEntry.actor` is free text ("self / web", "system / admin",
 * ...), but every seed entry so far comes from one of these two sources —
 * a real integration would likely keep it that way (Keycloak's own admin
 * events distinguish user-initiated vs admin/system-initiated actions the
 * same way). Filtering matches on substring so any future actor string
 * that still contains "self" or "system" keeps working.
 */
const ACTOR_OPTIONS = [
  { value: "all", label: "All actors" },
  { value: "self", label: "Self (web)" },
  { value: "system", label: "System / admin" },
] as const

const SORT_OPTIONS = [
  { value: "newest", label: "Newest first" },
  { value: "oldest", label: "Oldest first" },
] as const

function parseEntryDate(time: string): Date {
  // Entries are "YYYY-MM-DD, HH:mm" — the date portion alone is enough
  // for day-level range filtering.
  return new Date(time.slice(0, 10))
}

/** Strips the `**bold**` markup `renderActivityText` renders, so a
 * keyword search still matches text inside an emphasized span. */
function plainText(text: string): string {
  return text.replace(/\*\*(.+?)\*\*/g, "$1")
}

/**
 * User Activity / Audit History (U10) — security and administrative
 * activity for a user: login, failed login, role changes,
 * activation/deactivation, password reset, and other significant account
 * events.
 *
 *   `GET /admin/realms/{realm}/admin-events?resourcePath=users/{id}`
 *   `GET /admin/realms/{realm}/events?user={id}` (login/auth — disabled by
 *   default per realm; Realm Settings → Events must be turned on)
 *
 * Reads `user.recentActivity` — the same array `UserDetailView`'s "Recent
 * activity" section already renders a short slice of — rather than a
 * separate audit-only data source, so the two views can never disagree.
 * This screen adds the event-type/actor/date-range/keyword filtering,
 * sorting, and pagination the short list doesn't need.
 *
 * Filters: event type, actor (self vs. system/admin), a date range (fixed
 * presets or a custom From/To pair), and a keyword search over the entry
 * text and actor. All filters combine with AND semantics and reset
 * pagination back to page 1 on change.
 *
 * Read-only: unlike U6–U9, there's nothing to confirm or revoke here, so
 * no dialog.
 */
export function UserAuditHistory({ user }: UserAuditHistoryProps) {
  const [eventType, setEventType] = useState<UserActivityEntry["type"] | "all">(
    "all"
  )
  const [actor, setActor] =
    useState<(typeof ACTOR_OPTIONS)[number]["value"]>("all")
  const [dateRange, setDateRange] =
    useState<(typeof DATE_RANGE_OPTIONS)[number]["value"]>("all")
  const [customFrom, setCustomFrom] = useState("")
  const [customTo, setCustomTo] = useState("")
  const [keyword, setKeyword] = useState("")
  const [sortOrder, setSortOrder] =
    useState<(typeof SORT_OPTIONS)[number]["value"]>("newest")
  const [page, setPage] = useState(1)

  const filtersActive =
    eventType !== "all" ||
    actor !== "all" ||
    dateRange !== "all" ||
    keyword.trim() !== "" ||
    sortOrder !== "newest"

  const filteredEntries = useMemo(() => {
    const cutoff =
      dateRange === "all" || dateRange === "custom"
        ? null
        : new Date(Date.now() - Number(dateRange) * 24 * 60 * 60 * 1000)
    const fromDate =
      dateRange === "custom" && customFrom ? new Date(customFrom) : null
    const toDate =
      dateRange === "custom" && customTo ? new Date(customTo) : null
    const needle = keyword.trim().toLowerCase()

    const filtered = user.recentActivity.filter((entry) => {
      const matchesType = eventType === "all" || entry.type === eventType
      const matchesActor =
        actor === "all" || entry.actor.toLowerCase().includes(actor)
      const entryDate = parseEntryDate(entry.time)
      const matchesPreset = !cutoff || entryDate >= cutoff
      const matchesFrom = !fromDate || entryDate >= fromDate
      const matchesTo = !toDate || entryDate <= toDate
      const matchesKeyword =
        !needle ||
        plainText(entry.text).toLowerCase().includes(needle) ||
        entry.actor.toLowerCase().includes(needle)
      return (
        matchesType &&
        matchesActor &&
        matchesPreset &&
        matchesFrom &&
        matchesTo &&
        matchesKeyword
      )
    })

    return [...filtered].sort((a, b) => {
      const diff =
        parseEntryDate(a.time).getTime() - parseEntryDate(b.time).getTime()
      return sortOrder === "newest" ? -diff : diff
    })
  }, [
    user.recentActivity,
    eventType,
    actor,
    dateRange,
    customFrom,
    customTo,
    keyword,
    sortOrder,
  ])

  const totalPages = Math.max(1, Math.ceil(filteredEntries.length / PAGE_SIZE))
  const currentPage = Math.min(page, totalPages)
  const pageEntries = filteredEntries.slice(
    (currentPage - 1) * PAGE_SIZE,
    currentPage * PAGE_SIZE
  )

  function handleFilterChange(update: () => void) {
    update()
    setPage(1)
  }

  function clearFilters() {
    handleFilterChange(() => {
      setEventType("all")
      setActor("all")
      setDateRange("all")
      setCustomFrom("")
      setCustomTo("")
      setKeyword("")
      setSortOrder("newest")
    })
  }

  return (
    <div className="rounded-[10px] border border-border bg-card px-[30px] py-[26px] shadow-[var(--ledger-shadow)]">
      {/* Identity */}
      <div className="mb-6 flex items-center justify-between gap-3">
        <div>
          <p className="mb-0.5 font-heading text-[17px] font-semibold">
            {user.name}
          </p>
          <p className="font-mono text-[12px] text-muted-foreground">
            {user.username} · {user.email}
          </p>
        </div>
        <Link
          href={`/admin/users/${user.id}`}
          className="text-[12px] font-semibold text-primary hover:underline"
        >
          View full profile →
        </Link>
      </div>

      {/* Filters */}
      <div className="mb-5 flex flex-wrap items-center gap-2.5">
        <div className="relative w-[190px]">
          <select
            value={eventType}
            onChange={(event) =>
              handleFilterChange(() =>
                setEventType(
                  event.target.value as UserActivityEntry["type"] | "all"
                )
              )
            }
            className="h-[38px] w-full appearance-none rounded-[10px] border border-border bg-muted px-3 text-[13px] font-medium text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
          >
            {EVENT_TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <ChevronDown
            size={14}
            className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
          />
        </div>

        <div className="relative w-[150px]">
          <select
            value={actor}
            onChange={(event) =>
              handleFilterChange(() =>
                setActor(
                  event.target.value as (typeof ACTOR_OPTIONS)[number]["value"]
                )
              )
            }
            className="h-[38px] w-full appearance-none rounded-[10px] border border-border bg-muted px-3 text-[13px] font-medium text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
          >
            {ACTOR_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <ChevronDown
            size={14}
            className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
          />
        </div>

        <div className="relative w-[160px]">
          <select
            value={dateRange}
            onChange={(event) =>
              handleFilterChange(() =>
                setDateRange(
                  event.target
                    .value as (typeof DATE_RANGE_OPTIONS)[number]["value"]
                )
              )
            }
            className="h-[38px] w-full appearance-none rounded-[10px] border border-border bg-muted px-3 text-[13px] font-medium text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
          >
            {DATE_RANGE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <ChevronDown
            size={14}
            className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
          />
        </div>

        {dateRange === "custom" && (
          <>
            <input
              type="date"
              value={customFrom}
              onChange={(event) =>
                handleFilterChange(() => setCustomFrom(event.target.value))
              }
              aria-label="From date"
              className="h-[38px] w-[150px] rounded-[10px] border border-border bg-muted px-3 text-[13px] font-medium text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
            />
            <span className="text-[12.5px] text-muted-foreground">to</span>
            <input
              type="date"
              value={customTo}
              onChange={(event) =>
                handleFilterChange(() => setCustomTo(event.target.value))
              }
              aria-label="To date"
              className="h-[38px] w-[150px] rounded-[10px] border border-border bg-muted px-3 text-[13px] font-medium text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
            />
          </>
        )}

        <div className="relative w-[150px]">
          <select
            value={sortOrder}
            onChange={(event) =>
              handleFilterChange(() =>
                setSortOrder(
                  event.target.value as (typeof SORT_OPTIONS)[number]["value"]
                )
              )
            }
            className="h-[38px] w-full appearance-none rounded-[10px] border border-border bg-muted px-3 text-[13px] font-medium text-foreground outline-none focus-visible:border-primary focus-visible:bg-card"
          >
            {SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <ChevronDown
            size={14}
            className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)]"
          />
        </div>

        <input
          type="text"
          value={keyword}
          onChange={(event) =>
            handleFilterChange(() => setKeyword(event.target.value))
          }
          placeholder="Search activity…"
          className="h-[38px] w-[200px] rounded-[10px] border border-border bg-muted px-3 text-[13px] font-medium text-foreground outline-none placeholder:font-normal placeholder:text-muted-foreground focus-visible:border-primary focus-visible:bg-card"
        />

        {filtersActive && (
          <button
            type="button"
            onClick={clearFilters}
            className="flex h-[38px] items-center gap-1 rounded-[10px] px-2.5 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground"
          >
            <X size={13} />
            Clear filters
          </button>
        )}
      </div>

      {/* Result count */}
      <p className="mb-3 text-[12px] text-muted-foreground">
        {filteredEntries.length} matching{" "}
        {filteredEntries.length === 1 ? "event" : "events"}
        {user.recentActivity.length !== filteredEntries.length && (
          <> of {user.recentActivity.length} total</>
        )}
      </p>

      {/* Feed */}
      {pageEntries.length === 0 ? (
        <p className="py-3 text-[13px] text-muted-foreground">
          No matching activity.
        </p>
      ) : (
        <div>
          {pageEntries.map((entry, i) => (
            <div
              key={entry.id}
              className={
                "flex items-start gap-3.5 pb-3" +
                (i < pageEntries.length - 1
                  ? " mb-3 border-b border-dashed border-border"
                  : "")
              }
            >
              <span className="mt-[5px] size-2 shrink-0 rounded-full border-[1.5px] border-[var(--border-strong)]" />
              <span className="w-[150px] shrink-0 font-mono text-xs text-[var(--ink-faint)]">
                {entry.time}
              </span>
              <span className="text-[13.5px] text-foreground">
                {renderActivityText(entry.text)} —{" "}
                <span className="text-[12.5px] text-muted-foreground">
                  {entry.actor}
                </span>
              </span>
            </div>
          ))}
        </div>
      )}

      {/* Pager */}
      {totalPages > 1 && (
        <div className="mt-5 flex items-center justify-end gap-1.5 border-t border-dashed border-border pt-4">
          {Array.from({ length: totalPages }, (_, i) => i + 1).map((n) => (
            <button
              key={n}
              type="button"
              onClick={() => setPage(n)}
              className={
                "flex size-7 items-center justify-center rounded-[10px] border text-[12.5px] " +
                (n === currentPage
                  ? "bg-primary-tint border-primary font-semibold text-primary"
                  : "border-border bg-card text-muted-foreground")
              }
            >
              {n}
            </button>
          ))}
          <button
            type="button"
            onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
            disabled={currentPage === totalPages}
            className="ml-1 rounded-[10px] px-2 py-1 text-[12.5px] font-semibold text-primary disabled:cursor-not-allowed disabled:text-muted-foreground"
          >
            Next ›
          </button>
        </div>
      )}
    </div>
  )
}
