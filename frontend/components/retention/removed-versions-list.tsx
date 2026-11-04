"use client"

import { useState } from "react"
import Link from "next/link"

import { LoadingState } from "@/components/ui/loading-state"
import { TablePagination } from "@/components/ui/table-pagination"
import { friendlyMessage } from "@/lib/api-error"
import { useRemovedVersions, useRestoreVersion } from "@/lib/retention/use-retention"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { formatDateTime } from "@/lib/utils"

const PAGE_SIZE = 25

/**
 * Old versions a retention rule has removed (6.1): hidden from the version history but not yet destroyed. Each says when
 * its files will be destroyed for good, and can be restored until then - restoring also keeps it from now on, so the next
 * retention run does not remove it again.
 */
export function RemovedVersionsList() {
  const [page, setPage] = useState(1)
  const versions = useRemovedVersions(page - 1, PAGE_SIZE)
  const restore = useRestoreVersion()

  return (
    <div className="flex flex-col gap-3">
      <p className="max-w-[640px] text-[12.5px] leading-[1.6] text-muted-foreground">
        These old versions were removed by a retention rule. They are hidden from the version history, and their files are
        destroyed for good on the date shown. Restore one to bring it back and keep it.
      </p>
      {versions.isPending ? (
        <LoadingState message="Loading…" />
      ) : versions.isError ? (
        <p className="text-[13px] text-[var(--rust)]">{friendlyMessage(versions.error, "Could not load the removed versions.")}</p>
      ) : versions.data.content.length === 0 ? (
        <p className="rounded-lg border border-dashed border-[var(--border-strong)] px-6 py-10 text-center text-[13px] text-muted-foreground">
          No versions are waiting out their grace period.
        </p>
      ) : (
        <>
          <ul className="divide-y divide-border rounded-lg border border-border bg-card">
            {versions.data.content.map((v) => (
              <li key={v.versionId} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <Link href={`/documents/${v.documentId}`} className="text-[13.5px] font-semibold hover:underline">
                    {v.documentTitle}
                  </Link>
                  <p className="mt-0.5 text-[12.5px] text-muted-foreground">
                    Version {v.versionNumber} · {v.fileName} · {formatBytes(v.sizeBytes)} · removed {formatDateTime(v.removedAt)} ·
                    destroyed {formatDateTime(v.destroysAt)}
                  </p>
                </div>
                <button
                  type="button"
                  disabled={restore.isPending}
                  onClick={() => restore.mutate(v.versionId)}
                  className="rounded-md border border-[var(--border-strong)] bg-card px-3 py-1.5 text-[12.5px] font-semibold text-foreground disabled:cursor-not-allowed disabled:opacity-60"
                >
                  Restore version {v.versionNumber}
                </button>
              </li>
            ))}
          </ul>
          {versions.data.totalPages > 1 && (
            <TablePagination currentPage={page} totalPages={versions.data.totalPages} onPageChange={setPage} />
          )}
        </>
      )}
    </div>
  )
}
