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
import { useReviewQueue } from "@/lib/documents/use-review-queue"
import { usePageTitle } from "@/lib/use-page-title"

const DEFAULT_PAGE_SIZE = 25

/**
 * Review Queue (Phase 4b) -- every document in this tenant whose OCR
 * failed or whose result tripped a rule, newest first, each with the
 * reason on the row.
 *
 * Top-level under /documents rather than /admin: reviewing is daily work for
 * the people who file documents, not an administrative task.
 *
 * Gated twice since the merge - os.review-ocr-exceptions.view-queue to reach
 * the screen, and document-level access so the list contains only documents
 * the caller can see. The access filter is applied in the query, not to the
 * page afterwards, so the count is honest too.
 */
export default function ReviewQueuePage() {
  usePageTitle("Review Queue")

  const router = useRouter()
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)

  const { data, isPending, isError, error, refetch } = useReviewQueue(page, pageSize)

  function handlePageSizeChange(size: number) {
    setPageSize(size)
    setPage(1)
  }

  function handleOpen(row: ReviewQueueItem) {
    router.push(`/documents/${row.documentId}/review`)
  }

  const total = data?.totalElements ?? 0

  return (
    <DashboardShell initialActiveLabel="Review Queue">
      <AdminBreadcrumb
        className="mb-3"
        segments={[
          { label: "Dashboard", href: "/" },
          { label: "My Documents", href: "/documents" },
          { label: "Review Queue" },
        ]}
      />
      <div className="mb-[22px] max-w-[680px]">
        <h1 className="mb-2 font-heading text-[26px] font-semibold tracking-[-0.01em]">Review Queue</h1>
        <p className="text-[13.5px] leading-[1.6] text-muted-foreground">
          Documents whose text extraction failed, or whose results need a human to confirm them —
          a required field that wasn&apos;t found, a validation check that didn&apos;t add up, or a
          page too poor to read confidently. Open one to check and correct it.
        </p>
      </div>

      {isPending ? (
        <LoadingState message="Loading the review queue…" />
      ) : isError ? (
        isNetworkError(error) ? (
          <BackendUnavailable onRetry={refetch} />
        ) : (
          <div className="rounded-lg border border-[var(--rust)] bg-[var(--rust-tint)] p-[26px] text-[13.5px] text-[var(--rust)] shadow-[var(--ledger-shadow)]">
            {error instanceof Error ? error.message : "Could not load the review queue."}
          </div>
        )
      ) : (
        <>
          <p className="mb-2.5 text-[12.5px] text-muted-foreground">
            {total === 0
              ? "Nothing waiting."
              : total === 1
                ? "1 document waiting."
                : `${total} documents waiting.`}
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
