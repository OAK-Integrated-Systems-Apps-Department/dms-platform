"use client"

import type { CSSProperties } from "react"
import { useRouter } from "next/navigation"
import {
  Download,
  Eye,
  FileText,
  FolderOpen,
  MousePointerClick,
} from "lucide-react"

import { BarList, NUMBER } from "@/components/dashboard/charts"
import {
  DashCard,
  DashSkeleton,
  EmptyState,
  Pending,
} from "@/components/dashboard/dash-card"
import { DonutChart } from "@/components/dashboard/donut-chart"
import { fileKind } from "@/components/documents/browser-items"
import type { DashboardDto, DashboardTopDocument } from "@/lib/dashboard/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

/** Slices in the document-type donut, the last one being "more types" when there are more. */
const SLICES = 5
const PALETTE = [
  "var(--primary)",
  "#2b579a",
  "var(--gold)",
  "#8764b8",
  "#c43e1c",
  "#4f7a8a",
]

function BarSkeleton() {
  return (
    <div className="flex flex-col gap-4 px-2 py-1">
      {[1, 2, 3, 4].map((row) => (
        <div key={row}>
          <DashSkeleton className="mb-1.5 h-3 w-2/5" />
          <DashSkeleton className="h-2 w-full rounded-full" />
        </div>
      ))}
    </div>
  )
}

/** Documents per document type, largest first. */
export function TypesCard({
  data,
  order,
}: {
  data?: DashboardDto
  order: number
}) {
  const types = data?.types ?? []
  // A donut reads well up to about five slices; the rest share one.
  const shown = types.length > SLICES ? types.slice(0, SLICES - 1) : types
  const rest = types.slice(shown.length).reduce((sum, t) => sum + t.count, 0)
  return (
    <DashCard
      order={order}
      title="Document types"
      subtitle={
        data
          ? `${types.length} ${types.length === 1 ? "type" : "types"} in use`
          : " "
      }
    >
      {!data ? (
        <Pending
          skeleton={
            <div className="flex items-center gap-5">
              <DashSkeleton className="size-[140px] shrink-0 rounded-full" />
              <div className="flex flex-1 flex-col gap-2">
                <DashSkeleton className="h-3 w-full" />
                <DashSkeleton className="h-3 w-4/5" />
                <DashSkeleton className="h-3 w-3/5" />
              </div>
            </div>
          }
        />
      ) : types.length === 0 ? (
        <EmptyState icon={FileText} title="No documents yet" />
      ) : (
        <DonutChart
          ariaLabel="Documents by type"
          total={data.documents.total}
          centerValue={NUMBER.format(data.documents.total)}
          centerLabel="documents"
          slices={[
            ...shown.map((t, i) => ({
              key: t.name,
              label: t.name,
              value: t.count,
              valueLabel: NUMBER.format(t.count),
              color: PALETTE[i % PALETTE.length],
            })),
            ...(rest > 0
              ? [
                  {
                    key: "rest",
                    label: `${types.length - shown.length} more types`,
                    value: rest,
                    valueLabel: NUMBER.format(rest),
                    color: "var(--ink-faint)",
                  },
                ]
              : []),
          ]}
        />
      )}
    </DashCard>
  )
}

/** Where the documents are - the fullest folders first; click one to open it. */
export function FoldersCard({
  data,
  order,
}: {
  data?: DashboardDto
  order: number
}) {
  const router = useRouter()
  return (
    <DashCard
      order={order}
      title="Top folders"
      subtitle="Where your documents are kept"
      link={{ href: "/documents", label: "Browse" }}
    >
      {!data ? (
        <Pending skeleton={<BarSkeleton />} />
      ) : data.folders.length === 0 ? (
        <EmptyState
          icon={FolderOpen}
          title="No documents yet"
          action={{ href: "/documents", label: "Go to My Documents" }}
        />
      ) : (
        <BarList
          total={data.documents.total}
          items={data.folders.map((f) => ({
            key: f.folderId ?? "top",
            label: f.name ?? (
              <span className="text-muted-foreground italic">
                Not in a folder
              </span>
            ),
            value: f.count,
            hint: f.bytes > 0 ? formatBytes(f.bytes) : undefined,
            color: f.folderId ? "var(--primary)" : "var(--ink-faint)",
            title: f.name ? `Open ${f.name}` : "Open My Documents",
            onSelect: () =>
              router.push(
                f.folderId
                  ? `/documents?folder=${encodeURIComponent(f.folderId)}`
                  : "/documents"
              ),
          }))}
        />
      )}
    </DashCard>
  )
}

/** The documents opened most this period - views and downloads side by side; click to preview. */
export function MostOpenedCard({
  data,
  order,
  onOpen,
}: {
  data?: DashboardDto
  order: number
  onOpen: (document: DashboardTopDocument) => void
}) {
  const top = data?.mostOpened ?? []
  const max = Math.max(1, ...top.map((d) => d.views + d.downloads))
  return (
    <DashCard
      order={order}
      title="Most opened"
      subtitle={
        data?.usageScope === "organisation"
          ? `By everyone, last ${data.days} days`
          : `By you, last ${data?.days ?? 30} days`
      }
    >
      {!data ? (
        <Pending skeleton={<BarSkeleton />} />
      ) : top.length === 0 ? (
        <EmptyState
          icon={MousePointerClick}
          title="Nothing opened yet"
          text={`Documents viewed or downloaded in the last ${data.days} days are ranked here.`}
        />
      ) : (
        <>
          <ol className="flex flex-col gap-1">
            {top.map((doc, i) => {
              const kind = fileKind(doc.fileName)
              const total = doc.views + doc.downloads
              return (
                <li key={doc.documentId}>
                  <button
                    type="button"
                    onClick={() => onOpen(doc)}
                    className="flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left transition-colors hover:bg-muted/70"
                  >
                    <span className="w-4 shrink-0 text-center font-heading text-[13px] font-semibold text-muted-foreground tabular-nums">
                      {i + 1}
                    </span>
                    <span
                      className={cn(
                        "flex size-8 shrink-0 items-center justify-center rounded-lg",
                        kind.tone
                      )}
                    >
                      <kind.icon size={15} />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[12.5px] font-medium">
                        {doc.title}
                      </span>
                      <span
                        className="mt-1 flex h-1.5 overflow-hidden rounded-full bg-muted"
                        style={{ width: `${(total / max) * 100}%` }}
                      >
                        <span
                          className="dash-bar-fill h-full"
                          style={
                            {
                              "--dash-order": i,
                              width: `${(doc.views / total) * 100}%`,
                              background: "#2b579a",
                            } as CSSProperties
                          }
                        />
                        <span
                          className="dash-bar-fill h-full"
                          style={
                            {
                              "--dash-order": i,
                              width: `${(doc.downloads / total) * 100}%`,
                              background: "var(--gold)",
                            } as CSSProperties
                          }
                        />
                      </span>
                    </span>
                    <span className="flex shrink-0 flex-col items-end text-[11px] text-muted-foreground tabular-nums">
                      <span className="flex items-center gap-1">
                        {NUMBER.format(doc.views)} <Eye size={11} />
                      </span>
                      <span className="flex items-center gap-1">
                        {NUMBER.format(doc.downloads)} <Download size={11} />
                      </span>
                    </span>
                  </button>
                </li>
              )
            })}
          </ol>
          <div className="mt-2 flex gap-4 px-2 text-[11px] text-muted-foreground">
            <span className="flex items-center gap-1.5">
              <span className="size-2 rounded-[2px] bg-[#2b579a]" /> Views
            </span>
            <span className="flex items-center gap-1.5">
              <span className="size-2 rounded-[2px] bg-[var(--gold)]" />
              Downloads
            </span>
          </div>
        </>
      )}
    </DashCard>
  )
}
