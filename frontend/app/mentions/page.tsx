"use client"

import { useState } from "react"
import Link from "next/link"
import { AtSign, Lock } from "lucide-react"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { MentionText } from "@/components/comments/mention-text"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { useMyMentions } from "@/lib/comments/use-comments"
import { usePageTitle } from "@/lib/use-page-title"
import { formatDateTime } from "@/lib/utils"

const PAGE_SIZE = 20

/**
 * Comments I was mentioned in (Phase 2 S14). Someone without permission to
 * view documents still reads every comment they were tagged in here; the
 * document link appears only for those who can open it (decision 10).
 */
export default function MentionsPage() {
  usePageTitle("Mentions")
  const [page, setPage] = useState(1)
  const { data, isPending, isError, error } = useMyMentions(page, PAGE_SIZE)
  const rows = data?.content ?? []

  return (
    <DashboardShell initialActiveLabel="Mentions">
      <AdminBreadcrumb className="mb-3" segments={[{ label: "Dashboard", href: "/" }, { label: "Mentions" }]} />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Mentions</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">Comments where someone mentioned you, newest first.</p>
      </div>

      {isPending ? (
        <LoadingState message="Loading your mentions…" />
      ) : isError ? (
        <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)]">
          {error instanceof Error ? error.message : "Could not load your mentions."}
        </div>
      ) : rows.length === 0 ? (
        <div className="flex items-center gap-2 rounded-lg border border-dashed border-[var(--border-strong)] bg-card px-6 py-8 text-[13px] text-muted-foreground">
          <AtSign size={15} /> Nobody has mentioned you yet.
        </div>
      ) : (
        <>
          <ul className="flex flex-col gap-3">
            {rows.map((m) => (
              <li key={m.mentionId} className="rounded-lg border border-border bg-card px-5 py-4 shadow-[var(--ledger-shadow)]">
                <p className="text-[12.5px] text-muted-foreground">
                  <span className="font-semibold text-foreground">{m.comment.author.displayName ?? "Someone"}</span> mentioned you on{" "}
                  {m.canOpenDocument ? (
                    <Link href={`/documents/${m.documentId}`} className="font-semibold text-primary hover:underline">
                      {m.documentTitle ?? "a document"}
                    </Link>
                  ) : (
                    <span className="inline-flex items-center gap-1 font-semibold text-foreground" title="You can't open this document">
                      <Lock size={11} /> {m.documentTitle ?? "a document"}
                    </span>
                  )}
                  <span className="ml-1.5 font-mono text-[11.5px]">{formatDateTime(m.createdAt)}</span>
                </p>
                <p className="mt-1.5 text-[13.5px] text-foreground">
                  <MentionText body={m.comment.body} />
                </p>
                {!m.canOpenDocument && (
                  <p className="mt-1.5 text-[12px] text-muted-foreground">
                    You don&apos;t have access to this document - ask its owner if you need to see it.
                  </p>
                )}
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
