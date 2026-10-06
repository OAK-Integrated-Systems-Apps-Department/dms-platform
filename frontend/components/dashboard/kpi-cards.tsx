"use client"

import type { CSSProperties } from "react"
import type { LucideIcon } from "lucide-react"
import {
  ArrowDownRight,
  ArrowUpRight,
  Download,
  Eye,
  FileStack,
  HardDrive,
  Minus,
} from "lucide-react"

import { NUMBER } from "@/components/dashboard/charts"
import { DashSkeleton } from "@/components/dashboard/dash-card"
import { useCountUp, useTilt } from "@/components/dashboard/motion"
import type { DashboardDto } from "@/lib/dashboard/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

const count = (n: number) => NUMBER.format(Math.round(n))
const bytes = (n: number) => formatBytes(Math.round(n))

/**
 * The four headline figures. Each card reads top to bottom: what it is, the
 * number, what the number covers - then this period against the one before
 * as two bars on one scale, so the change is plain even when it's from zero.
 */
export function KpiCards({ data }: { data?: DashboardDto }) {
  if (!data) {
    return (
      <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {[1, 2, 3, 4].map((order) => (
          <div
            key={order}
            className="rounded-2xl border border-border bg-card p-5 shadow-[var(--ledger-shadow)]"
          >
            <DashSkeleton className="mb-5 h-4 w-24" />
            <DashSkeleton className="mb-2 h-9 w-20" />
            <DashSkeleton className="mb-5 h-3 w-32" />
            <DashSkeleton className="mb-2 h-2.5 w-full rounded-full" />
            <DashSkeleton className="h-2.5 w-2/3 rounded-full" />
          </div>
        ))}
      </div>
    )
  }

  const { documents, storage, usage, days } = data
  const by = data.usageScope === "organisation" ? "by everyone" : "by you"

  return (
    <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <KpiCard
        order={1}
        icon={FileStack}
        color="var(--primary)"
        label="Documents"
        value={documents.total}
        caption="you can open"
        compareLabel="Added"
        now={documents.added}
        before={documents.addedBefore}
        days={days}
      />
      <KpiCard
        order={2}
        icon={Eye}
        color="#2b579a"
        label="Views"
        value={usage.views}
        caption={`${by} in the last ${days} days`}
        now={usage.views}
        before={usage.viewsBefore}
        days={days}
      />
      <KpiCard
        order={3}
        icon={Download}
        color="var(--gold)"
        label="Downloads"
        value={usage.downloads}
        caption={`${by} in the last ${days} days`}
        now={usage.downloads}
        before={usage.downloadsBefore}
        days={days}
      />
      <KpiCard
        order={4}
        icon={HardDrive}
        color="#8764b8"
        label="Storage"
        value={storage.usedBytes}
        format={bytes}
        caption={`used by ${NUMBER.format(storage.versions)} file versions`}
        compareLabel="Added"
        now={storage.addedBytes}
        before={storage.addedBytesBefore}
        days={days}
      />
    </div>
  )
}

function KpiCard({
  order,
  icon: Icon,
  color,
  label,
  value,
  format = count,
  caption,
  compareLabel,
  now,
  before,
  days,
}: {
  order: number
  icon: LucideIcon
  color: string
  label: string
  value: number
  format?: (value: number) => string
  /** What the big number covers, in words. */
  caption: string
  /** What the bars compare, when it isn't the big number itself (e.g. "Added"). */
  compareLabel?: string
  now: number
  before: number
  days: number
}) {
  const card = useTilt<HTMLDivElement>(4)
  const shown = useCountUp(value, 1300, order * 90)
  const max = Math.max(now, before, 1)

  return (
    <div
      className="dash-rise"
      style={{ "--dash-order": order } as CSSProperties}
    >
      <div
        ref={card}
        className="dash-tilt relative flex h-full flex-col rounded-2xl border border-border bg-card p-5 shadow-[var(--ledger-shadow)]"
      >
        <span aria-hidden="true" className="dash-glare" />
        <div className="flex flex-1 flex-col">
          <div className="mb-4 flex items-center justify-between gap-2">
            <span className="flex items-center gap-2 text-[12px] font-semibold tracking-[0.08em] text-muted-foreground uppercase">
              <span
                className="dash-tile flex size-7 items-center justify-center rounded-lg text-white"
                style={
                  {
                    background: `linear-gradient(145deg, color-mix(in oklab, ${color} 78%, white), ${color})`,
                    "--tile-depth": `color-mix(in oklab, ${color} 55%, black)`,
                    "--tile-glow": `color-mix(in oklab, ${color} 35%, transparent)`,
                  } as CSSProperties
                }
              >
                <Icon size={14} />
              </span>
              {label}
            </span>
            <Change now={now} before={before} format={format} />
          </div>

          <div
            className="font-heading text-[32px] leading-none font-semibold tabular-nums"
            aria-label={format(value)}
          >
            {format(shown)}
          </div>
          <div className="mt-1.5 text-[12px] text-muted-foreground">
            {caption}
          </div>

          <div className="mt-auto pt-4">
            {compareLabel && (
              <div className="mb-1.5 text-[11px] font-medium text-muted-foreground">
                {compareLabel}
              </div>
            )}
            <CompareBar
              label={`Last ${days} days`}
              value={now}
              max={max}
              color={color}
              format={format}
              order={order}
            />
            <CompareBar
              label={`${days} days before`}
              value={before}
              max={max}
              color="var(--ink-faint)"
              format={format}
              order={order}
              muted
            />
          </div>
        </div>
      </div>
    </div>
  )
}

/** The change since the period before, in the figure's own units: ▲ +71, ▼ −5, or "Same". */
function Change({
  now,
  before,
  format,
}: {
  now: number
  before: number
  format: (value: number) => string
}) {
  const diff = now - before
  const Icon = diff > 0 ? ArrowUpRight : diff < 0 ? ArrowDownRight : Minus
  const percent =
    before > 0
      ? ` (${diff > 0 ? "+" : ""}${Math.round((diff / before) * 100)}%)`
      : ""
  return (
    <span
      title={`${format(now)} this period, ${format(before)} the period before${percent}`}
      className={cn(
        "inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 text-[11.5px] font-semibold whitespace-nowrap tabular-nums",
        diff > 0
          ? "bg-[var(--primary-tint)] text-primary"
          : diff < 0
            ? "bg-[var(--rust-tint)] text-[var(--rust)]"
            : "bg-muted text-muted-foreground"
      )}
    >
      <Icon size={13} />
      {diff === 0 ? "Same" : `${diff > 0 ? "+" : "−"}${format(Math.abs(diff))}`}
    </span>
  )
}

function CompareBar({
  label,
  value,
  max,
  color,
  format,
  order,
  muted,
}: {
  label: string
  value: number
  max: number
  color: string
  format: (value: number) => string
  order: number
  muted?: boolean
}) {
  return (
    <div className="mb-1.5 grid grid-cols-[88px_1fr_auto] items-center gap-2 text-[11.5px] last:mb-0">
      <span
        className={cn(
          "truncate",
          muted ? "text-[var(--ink-faint)]" : "text-muted-foreground"
        )}
      >
        {label}
      </span>
      <span className="h-2 overflow-hidden rounded-full bg-muted">
        <span
          className="dash-bar-fill block h-full rounded-full"
          style={
            {
              "--dash-order": order,
              width: value > 0 ? `${Math.max(3, (value / max) * 100)}%` : 0,
              background: muted
                ? color
                : `linear-gradient(90deg, color-mix(in oklab, ${color} 70%, white), ${color})`,
              opacity: muted ? 0.55 : 1,
            } as CSSProperties
          }
        />
      </span>
      <span
        className={cn(
          "min-w-8 text-right font-semibold tabular-nums",
          muted && "font-medium text-[var(--ink-faint)]"
        )}
      >
        {format(value)}
      </span>
    </div>
  )
}
