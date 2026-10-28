"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { Bell, Check, Inbox as InboxIcon, X } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { useArchive, useMarkAllRead, useMarkRead, useNotifications } from "@/lib/notifications/use-notifications"
import { usePageTitle } from "@/lib/use-page-title"
import { formatDateTime } from "@/lib/utils"

const PAGE_SIZE = 20

/**
 * The notification inbox (3.2). Every notification is here, newest first;
 * clicking one marks it read and opens its link when it has one. The bell in
 * the top bar is a quick view onto the same inbox.
 */
export default function InboxPage() {
  usePageTitle("Inbox")
  const router = useRouter()
  const [page, setPage] = useState(1)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const { data, isPending, isError, error } = useNotifications(page - 1, PAGE_SIZE, unreadOnly)
  const markRead = useMarkRead()
  const markAllRead = useMarkAllRead()
  const archive = useArchive()
  const rows = data?.content ?? []

  function open(id: string, linkUrl: string | null, read: boolean) {
    if (!read) markRead.mutate(id)
    if (linkUrl) router.push(linkUrl)
  }

  return (
    <DashboardShell initialActiveLabel="Inbox">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Inbox" }]} />
      <div className="mb-[22px] flex flex-wrap items-end justify-between gap-3">
        <div className="max-w-[680px]">
          <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Inbox</h1>
          <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
            Your notifications, newest first. Critical alerts always reach you by email too.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => {
              setUnreadOnly((v) => !v)
              setPage(1)
            }}
            className="rounded-md border border-border px-3 py-1.5 text-[12.5px] font-semibold hover:bg-muted"
          >
            {unreadOnly ? "Showing unread" : "Show unread only"}
          </button>
          <button
            type="button"
            onClick={() => markAllRead.mutate()}
            className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-[12.5px] font-semibold hover:bg-muted"
          >
            <Check size={14} /> Mark all read
          </button>
        </div>
      </div>

      {isPending ? (
        <LoadingState message="Loading your notifications…" />
      ) : isError ? (
        <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)]">
          {error instanceof Error ? error.message : "Could not load your notifications."}
        </div>
      ) : rows.length === 0 ? (
        <div className="flex items-center gap-2 rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-8 text-[13px] text-muted-foreground">
          <InboxIcon size={15} /> {unreadOnly ? "No unread notifications." : "Nothing here yet."}
        </div>
      ) : (
        <>
          <ul className="flex flex-col gap-2.5">
            {rows.map((n) => (
              <li
                key={n.notificationId}
                className={`flex items-start gap-3 rounded-lg border px-5 py-4 shadow-[var(--ledger-shadow)] ${
                  n.read ? "border-border bg-card" : "border-[var(--border-strong)] bg-[var(--primary-tint)]"
                }`}
              >
                <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground">
                  <Bell size={13} />
                </span>
                <button
                  type="button"
                  onClick={() => open(n.notificationId, n.linkUrl, n.read)}
                  className="min-w-0 flex-1 text-left"
                >
                  <div className="flex items-center gap-2">
                    <span className="text-[13.5px] font-semibold text-foreground">{n.title}</span>
                    {n.tier === "critical" && (
                      <span className="rounded-full bg-[var(--rust-tint)] px-2 py-px text-[10.5px] font-semibold text-[var(--rust)]">
                        Critical
                      </span>
                    )}
                    {!n.read && <span className="size-[7px] rounded-full bg-[var(--rust)]" />}
                  </div>
                  {n.body && <p className="mt-1 text-[13px] whitespace-pre-line text-muted-foreground">{n.body}</p>}
                  <span className="mt-1 block font-mono text-[11.5px] text-[var(--ink-faint)]">
                    {formatDateTime(n.createdAt)}
                  </span>
                </button>
                <button
                  type="button"
                  title="Archive"
                  onClick={() => archive.mutate(n.notificationId)}
                  className="shrink-0 rounded-md p-1.5 text-muted-foreground hover:bg-muted"
                >
                  <X size={15} />
                </button>
              </li>
            ))}
          </ul>
          <TablePagination
            currentPage={page}
            totalPages={data?.totalPages ?? 1}
            onPageChange={setPage}
            pageSize={PAGE_SIZE}
            onPageSizeChange={() => undefined}
          />
        </>
      )}
    </DashboardShell>
  )
}
