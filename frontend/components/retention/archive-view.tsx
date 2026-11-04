"use client"

import { useState } from "react"
import Link from "next/link"
import { keepPreviousData, useQuery } from "@tanstack/react-query"
import { Gavel } from "lucide-react"

import { ReasonDialog } from "@/components/checkout/reason-dialog"
import { LifecycleBadge } from "@/components/retention/lifecycle-badge"
import { LoadingState } from "@/components/ui/loading-state"
import { SearchInput } from "@/components/ui/search-input"
import { SegmentedControl } from "@/components/ui/segmented-control"
import { TablePagination } from "@/components/ui/table-pagination"
import { LoadFailed } from "@/components/ui/load-failed"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { fetchDocuments, type DocumentListItemDto } from "@/lib/documents/api"
import { useChangeStatus } from "@/lib/retention/use-retention"
import { formatDateTime } from "@/lib/utils"

const PAGE_SIZE = 25

type Show = "all" | "inactive" | "archived"

/**
 * The Inactive / Archive view (CRM-FR-005, 009): every document switched off by hand or archived by its retention rule,
 * across all the folders the caller can open, still fully reachable. Only people with the view-archive permission get
 * the data (the backend refuses everyone else); from here they can bring a document back to Active - unless it is under
 * legal hold, which locks its status.
 */
export function ArchiveView() {
  const { hasPermission } = usePermissions()
  const canActivate = hasPermission(PermissionCodes.CRM_MARK_ACTIVE)
  const changeStatus = useChangeStatus()
  const [q, setQ] = useState("")
  const [show, setShow] = useState<Show>("all")
  const [page, setPage] = useState(1)
  const [reinstating, setReinstating] = useState<DocumentListItemDto | null>(null)

  const documents = useQuery({
    queryKey: ["documents", "archive", q, page],
    queryFn: () => fetchDocuments({ folderId: null, all: true, view: "archive", q, page: page - 1, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
    retry: false,
  })
  const rows = (documents.data?.content ?? []).filter((d) => show === "all" || d.lifecycleState === show)

  return (
    <div className="flex flex-1 flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <SearchInput
          value={q}
          onChange={(value) => {
            setQ(value)
            setPage(1)
          }}
          placeholder="Search inactive and archived documents…"
          aria-label="Search inactive and archived documents"
          className="min-w-[220px] flex-1"
        />
        <SegmentedControl
          value={show}
          onChange={setShow}
          options={[
            { value: "all", label: "All" },
            { value: "inactive", label: "Inactive" },
            { value: "archived", label: "Archived" },
          ]}
        />
      </div>

      {documents.isPending ? (
        <LoadingState message="Loading…" />
      ) : documents.isError ? (
        <LoadFailed error={documents.error} fallback="Could not load the inactive and archived documents." onRetry={() => void documents.refetch()} />
      ) : rows.length === 0 ? (
        <p className="flex min-h-[160px] flex-1 items-center justify-center rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-10 text-center text-[13px] text-muted-foreground">
          {q || show !== "all" ? "No inactive or archived document matches." : "Nothing here yet. Documents appear when they are marked inactive or archived by a retention rule."}
        </p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border bg-card">
          {rows.map((row) => (
            <li key={row.documentId} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <Link href={`/documents/${row.documentId}`} className="text-[13.5px] font-semibold hover:underline">
                  {row.title}
                </Link>
                <p className="mt-0.5 text-[12.5px] text-muted-foreground">
                  {row.documentTypeName ?? "-"} · created {formatDateTime(row.createdAt)}
                </p>
              </div>
              {row.lockState === "legal_hold" && (
                <span title="Under legal hold - its status is locked" className="inline-flex text-[var(--rust)]">
                  <Gavel size={14} aria-label="Under legal hold" />
                </span>
              )}
              <LifecycleBadge state={row.lifecycleState} />
              {canActivate && (
                <button
                  type="button"
                  disabled={row.lockState === "legal_hold" || changeStatus.isPending}
                  onClick={() => setReinstating(row)}
                  title={row.lockState === "legal_hold" ? "Under legal hold - lift the hold first" : undefined}
                  className="border border-[var(--border-strong)] bg-card font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-60 h-[38px] rounded-btn px-4 text-[13px] inline-flex items-center justify-center"
                >
                  {row.lifecycleState === "archived" ? "Bring back to Active" : "Mark active"}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {documents.data && documents.data.totalPages > 1 && (
        <TablePagination currentPage={page} totalPages={documents.data.totalPages} onPageChange={setPage} />
      )}

      {reinstating && (
        <ReasonDialog
          required={false}
          title={`Mark "${reinstating.title}" active?`}
          description="It returns to normal listings and search, and starts a fresh active period."
          confirmLabel="Mark active"
          onConfirm={(reason) =>
            changeStatus.mutateAsync({ documentId: reinstating.documentId, status: "active", reason })
          }
          onOpenChange={(open) => !open && setReinstating(null)}
        />
      )}
    </div>
  )
}
