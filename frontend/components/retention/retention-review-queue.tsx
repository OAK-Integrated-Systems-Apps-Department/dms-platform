"use client"

import { useState } from "react"
import Link from "next/link"

import { LifecycleBadge } from "@/components/retention/lifecycle-badge"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { LoadFailed } from "@/components/ui/load-failed"
import { useReviewQueue, useWithoutPolicy } from "@/lib/retention/use-retention"
import { formatDateTime } from "@/lib/utils"

const PAGE_SIZE = 25

/**
 * The two lists an administrator works through (CRM-FR-012 and the "no rule configured" alternate flow):
 * documents past their total retention (open one to keep it longer or send it to the recycle bin), and documents no
 * rule covers yet (add a rule - the next run clears the flag).
 */
export function RetentionReviewQueue({ kind }: { kind: "review" | "without-policy" }) {
  const [page, setPage] = useState(1)
  const review = useReviewQueue(page - 1, PAGE_SIZE, kind === "review")
  const without = useWithoutPolicy(page - 1, PAGE_SIZE, kind === "without-policy")
  const query = kind === "review" ? review : without

  return (
    <div className="flex flex-col gap-3">
      <p className="text-[12.5px] leading-[1.6] text-muted-foreground">
        {kind === "review"
          ? "These documents have been kept for their whole retention period. Nothing has been deleted. Open one to keep it longer or send it to the recycle bin."
          : "No retention rule covers these documents. Add a rule on the Rules tab (an organisation-wide one covers everything), then apply the rules - the flag clears on the next run."}
      </p>
      {query.isPending ? (
        <LoadingState message="Loading…" />
      ) : query.isError ? (
        <LoadFailed error={query.error} fallback="Could not load the list." onRetry={() => void query.refetch()} />
      ) : query.data.content.length === 0 ? (
        <p className="flex min-h-[160px] flex-1 items-center justify-center rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-10 text-center text-[13px] text-muted-foreground">
          {kind === "review" ? "No documents are waiting for a retention review." : "Every document is covered by a rule."}
        </p>
      ) : (
        <>
          <ul className="divide-y divide-border rounded-lg border border-border bg-card">
            {query.data.content.map((item) => (
              <li key={item.documentId} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <Link href={`/documents/${item.documentId}`} className="text-[13.5px] font-semibold hover:underline">
                    {item.title}
                  </Link>
                  <p className="mt-0.5 text-[12.5px] text-muted-foreground">
                    {item.documentTypeName ?? "-"}
                    {item.policyName ? ` · ${item.policyName}` : ""}
                    {item.framework ? ` (${item.framework})` : ""}
                    {kind === "review" && item.flaggedAt ? ` · flagged ${formatDateTime(item.flaggedAt)}` : ""}
                  </p>
                </div>
                <LifecycleBadge state={item.lifecycleState} />
              </li>
            ))}
          </ul>
          {query.data.totalPages > 1 && (
            <TablePagination currentPage={page} totalPages={query.data.totalPages} onPageChange={setPage} />
          )}
        </>
      )}
    </div>
  )
}
