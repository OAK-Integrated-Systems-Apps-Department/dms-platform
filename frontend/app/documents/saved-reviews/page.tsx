"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"

import { AdminBreadcrumb } from "@/components/admin/admin-breadcrumb"
import { DashboardShell } from "@/components/layout/dashboard-shell"
import { ReviewQueueTable } from "@/components/documents/review-queue-table"
import { BackendUnavailable } from "@/components/ui/backend-unavailable"
import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { isNetworkError } from "@/lib/api-error"
import type { ReviewQueueItem } from "@/lib/documents/api"
import { useSavedReviews } from "@/lib/documents/use-review-queue"
import { usePageTitle } from "@/lib/use-page-title"

const DEFAULT_PAGE_SIZE = 25

/**
 * Saved reviews -- documents a human has already settled, newest first.
 *
 * The counterpart to the Review Queue, and the reason the queue can be
 * trusted to empty: without somewhere to see what was decided, "it left the
 * queue" is the only evidence a review ever happened. A document appears here
 * once it has at least one recorded correction or acceptance, whether or not
 * it is still in the queue for other fields.
 *
 * Scoped twice, like the queue: os.review-ocr-exceptions.view-queue to reach
 * the screen at all, and document-level access so it lists only documents the
 * caller can see.
 */
export default function SavedReviewsPage() {
  usePageTitle("Saved reviews")

  const router = useRouter()
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  const { data, isPending, isError, error, refetch } = useSavedReviews(page, pageSize)

  function handlePageSizeChange(size: number) {
    setPageSize(size)
    setPage(1)
  }

  function handleOpen(row: ReviewQueueItem) {
    router.push(`/documents/${row.documentId}/review`)
  }

  const total = data?.totalElements ?? 0

  return (
    <DashboardShell initialActiveLabel="Saved reviews">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "My Documents", href: "/documents" },
          { label: "Saved reviews" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Saved reviews</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Documents somebody has already reviewed, newest first. Open one to see what was
          accepted and what was corrected, or to revisit a decision.
        </p>
      </div>

      {isPending ? (
        <LoadingState message="Loading saved reviews…" />
      ) : isError ? (
        isNetworkError(error) ? (
          <BackendUnavailable onRetry={refetch} />
        ) : (
          <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
            {error instanceof Error ? error.message : "Could not load saved reviews."}
          </div>
        )
      ) : (
        <>
          <p className="mb-2.5 text-[12.5px] text-muted-foreground">
            {total === 0
              ? "Nothing reviewed yet."
              : total === 1
                ? "1 document reviewed."
                : `${total} documents reviewed.`}
          </p>
          <ReviewQueueTable rows={data?.content ?? []} onOpen={handleOpen} />
          <TablePagination
            currentPage={page}
            totalPages={data?.totalPages ?? 1}
            onPageChange={setPage}
            pageSize={pageSize}
            onPageSizeChange={handlePageSizeChange}
          />
        </>
      )}
    </DashboardShell>
  )
}
