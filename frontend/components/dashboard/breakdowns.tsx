"use client"

import type { CSSProperties } from "react"
import { useRouter } from "next/navigation"
import { Eye, Download, ShieldCheck } from "lucide-react"

import { BarList, NUMBER, share } from "@/components/dashboard/charts"
import { DashCard, DashSkeleton } from "@/components/dashboard/dash-card"
import { fileKind } from "@/components/documents/browser-items"
import type { DashboardDto, DashboardTopDocument } from "@/lib/dashboard/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

/** Up to this many rows in a ranked list; the rest are summed into one. */
const ROWS = 6
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
  const shown = types.slice(0, ROWS)
  const rest = types.slice(ROWS).reduce((sum, t) => sum + t.count, 0)
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
        <BarSkeleton />
      ) : (
        <BarList
          total={data.documents.total}
          emptyText="No documents yet."
          items={[
            ...shown.map((t, i) => ({
              key: t.name,
              label: t.name,
              value: t.count,
              color: PALETTE[i % PALETTE.length],
            })),
            ...(rest > 0
              ? [
                  {
                    key: "rest",
                    label: `${types.length - ROWS} more types`,
                    value: rest,
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
        <BarSkeleton />
      ) : (
        <BarList
          total={data.documents.total}
          emptyText="No documents yet."
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
        <BarSkeleton />
      ) : top.length === 0 ? (
        <p className="py-8 text-center text-[13px] text-muted-foreground">
          Nothing opened in this period.
        </p>
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

const OUTCOMES = [
  {
    key: "clean",
    label: "Clean, stored",
    color: "var(--primary)",
  },
  {
    key: "threats",
    label: "Malware quarantined",
    color: "var(--rust)",
  },
  {
    key: "unscannable",
    label: "Couldn't be scanned",
    color: "var(--gold)",
  },
  {
    key: "refused",
    label: "Refused (duplicate, damaged…)",
    color: "#8b968f",
  },
] as const

/** What the virus scanner did with this period's uploads, and what's held in quarantine now. */
export function SecurityCard({
  data,
  order,
}: {
  data: DashboardDto
  order: number
}) {
  const security = data.security
  if (!security) return null
  const total = OUTCOMES.reduce((sum, o) => sum + security[o.key], 0)
  return (
    <DashCard
      order={order}
      title="Upload security"
      subtitle={`Every upload is scanned for malware · last ${data.days} days`}
      link={{ href: "/admin/quarantine", label: "Quarantine" }}
    >
      <div className="mb-4 flex items-center gap-4">
        <span
          className="dash-tile flex size-12 shrink-0 items-center justify-center rounded-2xl text-white"
          style={
            {
              background:
                security.threats > 0
                  ? "linear-gradient(145deg, color-mix(in oklab, var(--rust) 75%, white), var(--rust))"
                  : "linear-gradient(145deg, color-mix(in oklab, var(--primary) 75%, white), var(--primary))",
              "--tile-depth":
                security.threats > 0
                  ? "color-mix(in oklab, var(--rust) 55%, black)"
                  : "var(--dash-depth)",
            } as CSSProperties
          }
        >
          <ShieldCheck size={22} />
        </span>
        <div>
          <div className="font-heading text-[22px] leading-tight font-semibold tabular-nums">
            {share(security.clean, total)} clean
          </div>
          <div className="text-[12px] text-muted-foreground">
            {NUMBER.format(total)} uploads checked ·{" "}
            <span
              className={cn(
                security.quarantined > 0 && "font-semibold text-[var(--rust)]"
              )}
            >
              {NUMBER.format(security.quarantined)} held in quarantine now
            </span>
          </div>
        </div>
      </div>

      {total > 0 && (
        <div className="mb-3 flex h-3 overflow-hidden rounded-full bg-muted shadow-[inset_0_1px_2px_rgba(0,0,0,0.08)]">
          {OUTCOMES.map((o, i) =>
            security[o.key] > 0 ? (
              <span
                key={o.key}
                title={`${o.label}: ${NUMBER.format(security[o.key])}`}
                className="dash-bar-fill h-full border-r-2 border-card last:border-r-0"
                style={
                  {
                    "--dash-order": i,
                    width: `${(security[o.key] / total) * 100}%`,
                    background: o.color,
                  } as CSSProperties
                }
              />
            ) : null
          )}
        </div>
      )}

      <ul className="grid grid-cols-2 gap-x-4 gap-y-1.5">
        {OUTCOMES.map((o) => (
          <li key={o.key} className="flex items-center gap-2 text-[12px]">
            <span
              className="size-2.5 shrink-0 rounded-[3px]"
              style={{ background: o.color }}
            />
            <span className="min-w-0 flex-1 truncate text-muted-foreground">
              {o.label}
            </span>
            <span className="font-semibold tabular-nums">
              {NUMBER.format(security[o.key])}
            </span>
          </li>
        ))}
      </ul>
    </DashCard>
  )
}
