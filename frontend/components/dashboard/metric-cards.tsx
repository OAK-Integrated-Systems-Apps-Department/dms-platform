"use client"

import type { CSSProperties, ReactNode } from "react"
import Link from "next/link"
import type { LucideIcon } from "lucide-react"
import {
  ArrowDownRight,
  ArrowUpRight,
  Database,
  FileUser,
  Files,
  Minus,
  UsersRound,
} from "lucide-react"

import { NUMBER } from "@/components/dashboard/charts"
import { IconTile } from "@/components/ui/icon-tile"
import { DashSkeleton, Pending } from "@/components/dashboard/dash-card"
import { useCountUp, useTilt } from "@/components/dashboard/motion"
import type { DashboardDto } from "@/lib/dashboard/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

const count = (n: number) => NUMBER.format(Math.round(n))
const bytes = (n: number) => formatBytes(Math.round(n))

/**
 * The four headline figures - what the user has, what's theirs, what's been
 * shared with them, and the storage - each read top to bottom: what it is,
 * the number, what it covers, then a comparison or a share.
 */
export function MetricCards({
  data,
  sharedWithMe,
}: {
  data?: DashboardDto
  /** How many documents others have shared with the user; undefined while it loads, null if it couldn't. */
  sharedWithMe?: number | null
}) {
  const days = data?.days ?? 30
  const documents = data?.documents
  const storage = data?.storage

  return (
    <div className="mb-5 grid grid-cols-1 gap-4 min-[480px]:grid-cols-2 xl:grid-cols-4">
      <MetricCard
        order={1}
        icon={Files}
        color="var(--primary)"
        label="Documents"
        href="/documents"
        value={documents?.total}
        caption="you can open"
        change={
          documents && { now: documents.added, before: documents.addedBefore }
        }
        footer={
          documents && (
            <Comparison
              heading="Added"
              now={documents.added}
              before={documents.addedBefore}
              days={days}
              color="var(--primary)"
              format={count}
            />
          )
        }
      />
      <MetricCard
        order={2}
        icon={FileUser}
        color="var(--gold)"
        label="My documents"
        href="/documents"
        value={documents?.mine}
        caption="owned by you"
        footer={
          documents && (
            <Meter
              part={documents.mine}
              whole={documents.total}
              color="var(--gold)"
              label="of the documents you can open"
            />
          )
        }
      />
      <MetricCard
        order={3}
        icon={UsersRound}
        color="#2b579a"
        label="Shared with me"
        href="/shared"
        value={sharedWithMe ?? undefined}
        unavailable={sharedWithMe === null}
        caption="by name or role, by others"
        footer={
          <span className="inline-flex items-center gap-1 text-[12px] font-semibold text-[#2b579a] dark:text-[#7aa0dc]">
            Open Shared With Me
            <ArrowUpRight size={13} aria-hidden="true" />
          </span>
        }
      />
      <MetricCard
        order={4}
        icon={Database}
        color="#8764b8"
        label="Storage"
        value={storage?.usedBytes}
        format={bytes}
        caption={
          storage
            ? `used by ${NUMBER.format(storage.versions)} file versions`
            : "used"
        }
        change={
          storage && {
            now: storage.addedBytes,
            before: storage.addedBytesBefore,
          }
        }
        footer={
          storage && (
            <Comparison
              heading="Added"
              now={storage.addedBytes}
              before={storage.addedBytesBefore}
              days={days}
              color="#8764b8"
              format={bytes}
            />
          )
        }
      />
    </div>
  )
}

export function MetricCard({
  order,
  icon: Icon,
  color,
  label,
  href,
  value,
  unavailable,
  format = count,
  caption,
  change,
  footer,
}: {
  order: number
  icon: LucideIcon
  color: string
  label: string
  /** Where the card leads - the whole card is the link. */
  href?: string
  /** Undefined while it loads. */
  value?: number
  /** It couldn't be loaded - say so in place of the number. */
  unavailable?: boolean
  format?: (value: number) => string
  /** What the number covers, in words. */
  caption: string
  change?: { now: number; before: number }
  footer?: ReactNode
}) {
  const card = useTilt<HTMLDivElement>(4)
  const shown = useCountUp(value ?? 0, 1300, order * 90)
  const loading = value === undefined && !unavailable

  const body = (
    <div
      ref={card}
      className={cn(
        "dash-tilt relative flex h-full flex-col rounded-lg border border-border bg-card p-5 shadow-[var(--ledger-shadow)]",
        href && "transition-colors group-hover:border-[var(--border-strong)]"
      )}
    >
      <span aria-hidden="true" className="dash-glare" />
      <div className="mb-3.5 flex items-center justify-between gap-2">
        <span className="flex items-center gap-2 text-[11.5px] font-semibold tracking-[0.08em] text-muted-foreground uppercase">
          <IconTile icon={Icon} color={color} size={30} />
          {label}
        </span>
        {change && <Change {...change} format={format} />}
      </div>

      {loading ? (
        <Pending
          skeleton={
            <>
              <DashSkeleton className="mb-2 h-8 w-20" />
              <DashSkeleton className="mb-4 h-3 w-32" />
              <DashSkeleton className="h-2 w-full rounded-full" />
            </>
          }
        />
      ) : unavailable ? (
        <p className="py-2 text-[12.5px] text-muted-foreground">
          Couldn&apos;t be loaded just now.
        </p>
      ) : (
        <>
          <div
            className="font-heading text-[30px] leading-none font-semibold tabular-nums"
            aria-label={format(value ?? 0)}
          >
            {format(shown)}
          </div>
          <div className="mt-1.5 text-[12px] text-muted-foreground">
            {caption}
          </div>
          {footer && <div className="mt-auto pt-4">{footer}</div>}
        </>
      )}
    </div>
  )

  return (
    <div
      className="dash-rise"
      style={{ "--dash-order": order } as CSSProperties}
    >
      {/* A link only once it's loaded - a retry button can't sit inside a link. */}
      {href && value !== undefined ? (
        <Link
          href={href}
          aria-label={`${label}: ${format(value)} ${caption}`}
          className="group block h-full rounded-lg focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          {body}
        </Link>
      ) : (
        body
      )}
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
  // Nothing in either period: a "Same" badge next to a 0 is just noise.
  if (now === 0 && before === 0) return null
  const diff = now - before
  const Icon = diff > 0 ? ArrowUpRight : diff < 0 ? ArrowDownRight : Minus
  const percent =
    before > 0
      ? ` (${diff > 0 ? "+" : ""}${Math.round((diff / before) * 100)}%)`
      : ""
  const text =
    diff === 0 ? "Same" : `${diff > 0 ? "+" : "−"}${format(Math.abs(diff))}`
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
      <Icon size={13} aria-hidden="true" />
      <span className="sr-only">
        {diff > 0 ? "Up" : diff < 0 ? "Down" : "No change"}:{" "}
      </span>
      {text}
    </span>
  )
}

/** This period against the one before, as two bars on one scale. */
function Comparison({
  heading,
  now,
  before,
  days,
  color,
  format,
}: {
  heading: string
  now: number
  before: number
  days: number
  color: string
  format: (value: number) => string
}) {
  const max = Math.max(now, before, 1)
  return (
    <div>
      <div className="mb-1.5 text-[11px] font-medium text-muted-foreground">
        {heading}
      </div>
      {[
        { label: `Last ${days} days`, value: now, muted: false },
        { label: `${days} days before`, value: before, muted: true },
      ].map((row) => (
        <div
          key={row.label}
          className="mb-1.5 grid grid-cols-[88px_1fr_auto] items-center gap-2 text-[11.5px] last:mb-0"
        >
          <span
            className={cn(
              "truncate",
              row.muted ? "text-[var(--ink-faint)]" : "text-muted-foreground"
            )}
          >
            {row.label}
          </span>
          <span className="h-2 overflow-hidden rounded-full bg-muted">
            <span
              className="dash-bar-fill block h-full rounded-full"
              style={{
                width:
                  row.value > 0
                    ? `${Math.max(3, (row.value / max) * 100)}%`
                    : 0,
                background: row.muted
                  ? "var(--ink-faint)"
                  : `linear-gradient(90deg, color-mix(in oklab, ${color} 70%, white), ${color})`,
                opacity: row.muted ? 0.55 : 1,
              }}
            />
          </span>
          <span
            className={cn(
              "min-w-8 text-right font-semibold tabular-nums",
              row.muted && "font-medium text-[var(--ink-faint)]"
            )}
          >
            {format(row.value)}
          </span>
        </div>
      ))}
    </div>
  )
}

/** A part of a whole, as a meter with its percentage in words. */
function Meter({
  part,
  whole,
  color,
  label,
}: {
  part: number
  whole: number
  color: string
  label: string
}) {
  const pct = whole > 0 ? Math.round((part / whole) * 100) : 0
  return (
    <div>
      <div className="mb-1.5 flex items-baseline justify-between text-[11.5px]">
        <span className="text-muted-foreground">
          <span className="font-semibold text-foreground tabular-nums">
            {pct}%
          </span>{" "}
          {label}
        </span>
      </div>
      <div
        role="meter"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={pct}
        aria-label={`${pct}% ${label}`}
        className="h-2 overflow-hidden rounded-full bg-muted"
      >
        <span
          className="dash-bar-fill block h-full rounded-full"
          style={{
            width: `${pct}%`,
            background: `linear-gradient(90deg, color-mix(in oklab, ${color} 70%, white), ${color})`,
          }}
        />
      </div>
    </div>
  )
}
