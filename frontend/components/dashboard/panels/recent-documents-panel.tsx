"use client"

import type { CSSProperties } from "react"
import { Download } from "lucide-react"

import { DashCard, DashSkeleton } from "@/components/dashboard/dash-card"
import { fileKind, FriendlyDate } from "@/components/documents/browser-items"
import type { DashboardRecentDocument } from "@/lib/dashboard/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

/**
 * The newest documents the user can see - click one to preview it, or
 * download it straight away.
 */
export function RecentDocumentsPanel({
  documents,
  order,
  onOpen,
  onDownload,
}: {
  /** Absent while loading. */
  documents?: DashboardRecentDocument[]
  order: number
  onOpen: (document: DashboardRecentDocument) => void
  onDownload: (document: DashboardRecentDocument) => void
}) {
  return (
    <DashCard
      order={order}
      title="Recent documents"
      subtitle="The newest you can open"
      link={{ href: "/documents", label: "View all" }}
    >
      {!documents ? (
        <div className="flex flex-col gap-3">
          {[1, 2, 3, 4].map((row) => (
            <div key={row} className="flex items-center gap-3">
              <DashSkeleton className="size-10 shrink-0 rounded-xl" />
              <div className="flex-1">
                <DashSkeleton className="mb-1.5 h-3.5 w-3/5" />
                <DashSkeleton className="h-3 w-2/5" />
              </div>
            </div>
          ))}
        </div>
      ) : documents.length === 0 ? (
        <p className="py-10 text-center text-[13px] text-muted-foreground">
          No documents yet - upload one to get started.
        </p>
      ) : (
        <ul className="-mx-2 flex flex-col">
          {documents.map((document, i) => {
            const kind = fileKind(document.fileName)
            return (
              <li
                key={document.documentId}
                className="dash-row group relative"
                style={{ "--dash-order": i } as CSSProperties}
              >
                <button
                  type="button"
                  onClick={() => onOpen(document)}
                  className="flex w-full items-center gap-3 rounded-xl px-2 py-2.5 pr-12 text-left transition-[background-color,transform,box-shadow] duration-200 hover:-translate-y-0.5 hover:bg-muted/70 hover:shadow-[0_8px_18px_-12px_rgba(0,0,0,0.35)]"
                >
                  <span
                    className={cn(
                      "flex size-10 shrink-0 items-center justify-center rounded-xl shadow-[inset_0_-2px_0_rgba(0,0,0,0.08)] transition-transform duration-300 group-hover:[transform:perspective(300px)_rotateY(-14deg)_rotateX(8deg)]",
                      kind.tone
                    )}
                  >
                    <kind.icon size={18} />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13.5px] font-medium">
                      {document.title}
                    </span>
                    <span className="mt-0.5 block truncate text-[11.5px] text-muted-foreground">
                      {[
                        document.documentTypeName,
                        document.ownerName,
                        document.sizeBytes !== null
                          ? formatBytes(document.sizeBytes)
                          : null,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                      {" · "}
                      <FriendlyDate iso={document.createdAt} />
                    </span>
                  </span>
                </button>
                <button
                  type="button"
                  aria-label={`Download ${document.title}`}
                  title="Download"
                  onClick={() => onDownload(document)}
                  className="absolute top-1/2 right-3 flex size-8 -translate-y-1/2 items-center justify-center rounded-lg text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 hover:bg-card hover:text-primary focus-visible:opacity-100"
                >
                  <Download size={16} />
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </DashCard>
  )
}
