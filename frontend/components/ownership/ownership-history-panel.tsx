"use client"

import { useState } from "react"

import { Badge } from "@/components/ui/badge"
import { Panel } from "@/components/ui/panel"
import { PermissionCodes } from "@/lib/auth/permission-codes"
import { usePermissions } from "@/lib/auth/use-permissions"
import { useOwnershipHistory } from "@/lib/ownership/use-ownership"
import { formatDateTime } from "@/lib/utils"

import type { OwnershipHistoryItemDto, PersonRefDto, DepartmentRefDto } from "@/lib/ownership/types"

const PAGE_SIZE = 10

const REASON_LABELS: Record<string, string> = {
  manual: "Transferred",
  offboarding: "Offboarding",
  reorganization: "Reorganisation",
  holding: "Held for reassignment",
}

interface OwnershipHistoryPanelProps {
  documentId: string
  /** Show the panel even without the history permission (the admin screen looks documents up itself). */
  alwaysVisible?: boolean
}

/**
 * Who owned the document and when it changed hands, newest first. Needs the DOT history permission; the
 * previous owner is always kept, and a deactivated or deleted person still shows by name.
 */
export function OwnershipHistoryPanel({ documentId, alwaysVisible }: OwnershipHistoryPanelProps) {
  const { hasPermission } = usePermissions()
  const allowed = alwaysVisible || hasPermission(PermissionCodes.DOT_HISTORY_VIEW)
  const [page, setPage] = useState(0)
  const history = useOwnershipHistory(documentId, page, PAGE_SIZE, allowed)

  if (!allowed) return null
  const pages = history.data ? Math.max(1, Math.ceil(history.data.total / history.data.size)) : 1

  return (
    <Panel title="Ownership History">
      {history.isPending ? (
        <p className="pt-3 text-[13px] text-muted-foreground">Loading…</p>
      ) : history.isError ? (
        <p className="pt-3 text-[13px] text-[var(--rust)]">Could not load the ownership history.</p>
      ) : history.data.items.length === 0 ? (
        <p className="pt-3 text-[13px] text-muted-foreground">Nobody has taken over this document yet.</p>
      ) : (
        <>
          <ul className="divide-y divide-border pt-2">
            {history.data.items.map((item) => (
              <HistoryRow key={item.transferId} item={item} />
            ))}
          </ul>
          {pages > 1 && (
            <div className="mt-2 flex items-center justify-between text-[12.5px] text-muted-foreground">
              <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)} className="font-semibold text-primary disabled:opacity-40">
                Newer
              </button>
              <span>
                Page {page + 1} of {pages}
              </span>
              <button type="button" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)} className="font-semibold text-primary disabled:opacity-40">
                Older
              </button>
            </div>
          )}
        </>
      )}
    </Panel>
  )
}

function who(person: PersonRefDto | null, department: DepartmentRefDto | null) {
  const name = person?.name ?? "Unknown user"
  const gone = person !== null && (person.status === "deactivated" || person.status === null)
  return (
    <>
      <strong className="font-semibold text-foreground">{name}</strong>
      {gone && <span className="text-muted-foreground"> (no longer active)</span>}
      {department && <span className="text-muted-foreground"> · {department.name}</span>}
    </>
  )
}

function HistoryRow({ item }: { item: OwnershipHistoryItemDto }) {
  return (
    <li className="py-2.5 text-[12.5px]">
      <div className="flex flex-wrap items-center gap-2">
        <span>
          {who(item.previousOwner, item.previousDepartment)} → {who(item.newOwner, item.newDepartment)}
        </span>
        <Badge variant="outline">{REASON_LABELS[item.reason] ?? item.reason}</Badge>
        {item.accessGranted && <Badge variant="muted">Access granted</Badge>}
      </div>
      <p className="mt-0.5 text-muted-foreground">
        {formatDateTime(item.transferredAt)} · by {item.actor?.name ?? "Unknown user"}
      </p>
      {item.note && <p className="mt-0.5 text-muted-foreground">{item.note}</p>}
    </li>
  )
}
