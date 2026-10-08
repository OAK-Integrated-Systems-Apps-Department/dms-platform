"use client"

import { useQuery } from "@tanstack/react-query"
import {
  AlertTriangle,
  Ban,
  CheckCircle2,
  ChevronRight,
  Clock,
  Loader2,
  Upload,
  XCircle,
  type LucideIcon,
} from "lucide-react"

import { FriendlyDate } from "@/components/documents/browser-items"
import {
  fetchMyBulkImports,
  isFinished,
  type BulkImportStatus,
} from "@/lib/bulk-imports/api"
import { BULK_IMPORTS_KEY } from "@/lib/bulk-imports/use-bulk-import"
import { cn } from "@/lib/utils"

import { ProgressBar } from "./step-card"

const STATUS: Record<
  BulkImportStatus,
  { label: string; icon: LucideIcon; tone: string; spin?: boolean }
> = {
  uploading: {
    label: "Uploading",
    icon: Upload,
    tone: "bg-primary/10 text-primary",
  },
  queued: {
    label: "Queued",
    icon: Clock,
    tone: "bg-muted text-muted-foreground",
  },
  running: {
    label: "Processing",
    icon: Loader2,
    tone: "bg-primary/10 text-primary",
    spin: true,
  },
  completed: {
    label: "Completed",
    icon: CheckCircle2,
    tone: "bg-primary/10 text-primary",
  },
  completed_with_errors: {
    label: "Completed with errors",
    icon: AlertTriangle,
    tone: "bg-[var(--gold-tint)] text-[var(--gold)]",
  },
  failed: {
    label: "Failed",
    icon: XCircle,
    tone: "bg-[var(--rust-tint)] text-[var(--rust)]",
  },
  cancelled: {
    label: "Cancelled",
    icon: Ban,
    tone: "bg-muted text-muted-foreground",
  },
}

/**
 * The caller's latest imports, finished ones included, so a result (and
 * its failed-rows file) can be reopened after leaving the page. Lives under
 * the ["bulk-imports"] key, so it refreshes whenever an import changes.
 */
export function RecentImports({
  documentTypeName,
  onOpen,
}: {
  documentTypeName: (documentTypeId: string) => string | null
  onOpen: (batchId: string) => void
}) {
  const { data, isPending, isError } = useQuery({
    queryKey: [...BULK_IMPORTS_KEY, "mine"],
    queryFn: () => fetchMyBulkImports(0, 10),
  })
  const rows = data?.content ?? []

  if (isPending) {
    return (
      <p className="inline-flex items-center gap-2 text-[13px] text-muted-foreground">
        <Loader2 size={14} className="animate-spin" aria-hidden="true" />
        Loading your recent imports…
      </p>
    )
  }
  if (isError || rows.length === 0) {
    return null
  }

  return (
    <section
      aria-labelledby="recent-imports"
      className="overflow-hidden rounded-xl border border-border bg-card shadow-[var(--ledger-shadow)]"
    >
      <div className="flex items-baseline justify-between gap-3 border-b border-border px-5 py-3.5">
        <h2 id="recent-imports" className="text-[15px] font-semibold">
          Recent imports
        </h2>
        <span className="text-[12.5px] text-muted-foreground">
          Your last {rows.length}
        </span>
      </div>
      <ul className="divide-y divide-border">
        {rows.map((row) => {
          const status = STATUS[row.status]
          const finished = isFinished(row.status)
          const openable = row.status !== "cancelled"
          const content = (
            <>
              <span
                aria-hidden="true"
                className={cn(
                  "flex size-9 shrink-0 items-center justify-center rounded-lg",
                  status.tone
                )}
              >
                <status.icon
                  size={17}
                  className={status.spin ? "animate-spin" : undefined}
                />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[13.5px] font-semibold">
                  {documentTypeName(row.documentTypeId) ?? "Import"}
                </span>
                <span className="block truncate text-[12px] text-muted-foreground">
                  {status.label} · <FriendlyDate iso={row.createdAt} />
                </span>
              </span>
              <span className="hidden w-[220px] shrink-0 sm:block">
                {row.status === "cancelled" ? (
                  <span className="block text-right text-[12.5px] text-muted-foreground">
                    Nothing imported
                  </span>
                ) : finished ? (
                  <span className="block text-right text-[12.5px] text-muted-foreground">
                    <span className="font-semibold text-foreground">
                      {row.successCount}
                    </span>{" "}
                    created
                    {row.failureCount > 0 && (
                      <>
                        {" · "}
                        <span className="font-semibold text-[var(--rust)]">
                          {row.failureCount}
                        </span>{" "}
                        failed
                      </>
                    )}{" "}
                    of {row.totalRecords}
                  </span>
                ) : (
                  <span className="flex flex-col gap-1">
                    <span className="text-right text-[12px] text-muted-foreground">
                      {row.filesReceived} of {row.totalRecords} files
                    </span>
                    <ProgressBar
                      value={row.filesReceived}
                      max={row.totalRecords}
                      label="Files received"
                    />
                  </span>
                )}
              </span>
              {openable ? (
                <span className="inline-flex w-[110px] shrink-0 items-center justify-end gap-1 text-[12.5px] font-medium text-primary">
                  {finished ? "View results" : "Open"}
                  <ChevronRight size={15} aria-hidden="true" />
                </span>
              ) : (
                <span className="w-[110px] shrink-0" />
              )}
            </>
          )
          return (
            <li key={row.batchId}>
              {openable ? (
                <button
                  type="button"
                  onClick={() => onOpen(row.batchId)}
                  className="flex w-full items-center gap-3.5 px-5 py-3 text-left transition-colors hover:bg-muted/40"
                >
                  {content}
                </button>
              ) : (
                <div className="flex items-center gap-3.5 px-5 py-3 opacity-75">
                  {content}
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
