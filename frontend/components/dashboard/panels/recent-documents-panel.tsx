"use client"

import { MoreVertical } from "lucide-react"

import { recentDocuments } from "@/components/dashboard/data"
import { fileIcon } from "@/components/dashboard/file-icon"
import { Panel } from "@/components/ui/panel"
import { Stamp } from "@/components/dashboard/status-stamp"
import type { RecentDocument } from "@/components/dashboard/types"

interface RecentDocumentsPanelProps {
  /** Defaults to the built-in mock data — pass this to show live/uploaded documents instead. */
  documents?: RecentDocument[]
}

/** Self-contained — delete this file's import from the page to remove the panel. */
export function RecentDocumentsPanel({ documents = recentDocuments }: RecentDocumentsPanelProps) {
  return (
    <Panel title="Recent documents" action="View all">
      <div>
        {documents.map((doc, index) => {
          const Icon = fileIcon(doc.type)
          return (
            <div
              key={doc.id}
              className="flex items-center gap-3 py-3"
              style={index === 0 ? undefined : { borderTop: "1px solid var(--border)" }}
            >
              <div
                className="flex size-[34px] shrink-0 items-center justify-center rounded-lg"
                style={{ background: "var(--primary-tint)" }}
              >
                <Icon size={16} color="var(--primary)" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="overflow-hidden text-[13.5px] font-medium text-ellipsis whitespace-nowrap">
                  {doc.name}
                </div>
                <div className="mt-0.5 text-[11.5px] text-muted-foreground">
                  {doc.owner} · {doc.modified} · {doc.size}
                </div>
              </div>
              <Stamp status={doc.status} />
              <button type="button" className="p-1 text-[var(--ink-faint)]">
                <MoreVertical size={16} />
              </button>
            </div>
          )
        })}
      </div>
    </Panel>
  )
}