"use client"

import type { CSSProperties, ReactNode } from "react"
import Link from "next/link"
import type { LucideIcon } from "lucide-react"
import {
  ArrowUpRight,
  ShieldAlert,
  ShieldCheck,
  ClipboardCheck,
} from "lucide-react"

import { NUMBER, share } from "@/components/dashboard/charts"
import { IconTile } from "@/components/ui/icon-tile"
import {
  DashCard,
  DashSkeleton,
  Pending,
} from "@/components/dashboard/dash-card"
import type { DashboardDto } from "@/lib/dashboard/api"
import { cn } from "@/lib/utils"

const OUTCOMES = [
  { key: "clean", label: "Clean", color: "var(--primary)" },
  { key: "threats", label: "Malware", color: "var(--rust)" },
  { key: "unscannable", label: "Unscannable", color: "var(--gold)" },
  { key: "refused", label: "Refused", color: "#8b968f" },
] as const

/**
 * What needs the user's attention, most pressing first: files held in
 * quarantine and uploads blocked this period (with the quarantine
 * permission), and approvals - which aren't built yet, so it says so.
 *
 * FOLLOW-UP: when approval workflows exist, list the documents waiting for
 * the user's sign-off here, from a real endpoint, with their due dates.
 */
export function AttentionPanel({
  data,
  order,
}: {
  data?: DashboardDto
  order: number
}) {
  const security = data?.security ?? null
  const urgent = (security?.quarantined ?? 0) > 0
  return (
    <DashCard
      order={order}
      title="Needs your attention"
      subtitle={
        !data
          ? " "
          : urgent
            ? "Something is waiting for review"
            : "You're all caught up"
      }
    >
      {!data ? (
        <Pending
          skeleton={
            <div className="flex flex-col gap-3">
              {[1, 2].map((row) => (
                <DashSkeleton key={row} className="h-16 w-full rounded-lg" />
              ))}
            </div>
          }
        />
      ) : (
        <ul className="flex flex-col gap-2.5">
          {security && (
            <Item
              icon={security.quarantined > 0 ? ShieldAlert : ShieldCheck}
              tone={security.quarantined > 0 ? "rust" : "primary"}
              title={
                security.quarantined > 0
                  ? `${NUMBER.format(security.quarantined)} ${security.quarantined === 1 ? "file" : "files"} held in quarantine`
                  : "Nothing held in quarantine"
              }
              text={
                security.quarantined > 0
                  ? "Release them if they're safe, or delete them."
                  : "Every infected upload has been dealt with."
              }
              link={
                security.quarantined > 0
                  ? { href: "/admin/quarantine", label: "Review" }
                  : undefined
              }
            />
          )}
          {security && <UploadSafety data={data} />}
          <Item
            icon={ClipboardCheck}
            tone="gold"
            title="Approvals"
            badge="Coming soon"
            text="Approval workflows aren't set up yet. Documents waiting for your sign-off will appear here."
          />
        </ul>
      )}
    </DashCard>
  )
}

const TONES = {
  primary: "var(--primary)",
  gold: "var(--gold)",
  rust: "var(--rust)",
}

function Item({
  icon: Icon,
  tone,
  title,
  text,
  badge,
  link,
  children,
}: {
  icon: LucideIcon
  tone: keyof typeof TONES
  title: string
  text?: string
  badge?: string
  link?: { href: string; label: string }
  children?: ReactNode
}) {
  const color = TONES[tone]
  return (
    <li
      className={cn(
        "rounded-lg border p-3",
        tone === "rust"
          ? "border-[color-mix(in_oklab,var(--rust)_35%,transparent)] bg-[var(--rust-tint)]"
          : "border-border bg-muted/30"
      )}
    >
      <div className="flex items-start gap-3">
        <IconTile icon={Icon} color={color} size={32} />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span
              className={cn(
                "text-[13px] font-semibold",
                tone === "rust" &&
                  "text-[var(--rust-foreground)] dark:text-[var(--rust)]"
              )}
            >
              {title}
            </span>
            {badge && (
              <span className="bg-[var(--gold-tint)] text-[var(--gold-foreground)] dark:text-[var(--gold)] inline-flex h-6 items-center justify-center gap-1 rounded-btn px-2.5 text-[12px] font-semibold whitespace-nowrap">
                {badge}
              </span>
            )}
          </div>
          {text && (
            <p className="mt-0.5 text-[12px] text-muted-foreground">{text}</p>
          )}
          {children}
        </div>
        {link && (
          <Link
            href={link.href}
            className="flex shrink-0 items-center gap-0.5 rounded-btn px-2 py-1 text-[12px] font-semibold text-[var(--rust)] hover:bg-card focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none"
          >
            {link.label}
            <ArrowUpRight size={13} aria-hidden="true" />
          </Link>
        )}
      </div>
    </li>
  )
}

/** This period's uploads by what the virus scanner did with them. */
function UploadSafety({ data }: { data: DashboardDto }) {
  const security = data.security
  if (!security) return null
  const total = OUTCOMES.reduce((sum, o) => sum + security[o.key], 0)
  const blocked = security.threats + security.unscannable
  return (
    <Item
      icon={blocked > 0 ? ShieldAlert : ShieldCheck}
      tone={security.threats > 0 ? "gold" : "primary"}
      title={
        total === 0
          ? `No uploads in the last ${data.days} days`
          : `${share(security.clean, total)} of uploads clean`
      }
      text={
        total === 0
          ? undefined
          : blocked > 0
            ? `${NUMBER.format(blocked)} blocked in the last ${data.days} days - ${NUMBER.format(security.threats)} malware, ${NUMBER.format(security.unscannable)} couldn't be scanned.`
            : `${NUMBER.format(total)} scanned in the last ${data.days} days, none blocked.`
      }
    >
      {total > 0 && (
        <>
          <div
            className="mt-2 flex h-2 overflow-hidden rounded-full bg-muted"
            role="img"
            aria-label={OUTCOMES.map(
              (o) => `${o.label}: ${security[o.key]}`
            ).join(", ")}
          >
            {OUTCOMES.map((o, i) =>
              security[o.key] > 0 ? (
                <span
                  key={o.key}
                  className="dash-bar-fill h-full border-r border-card last:border-r-0"
                  style={
                    {
                      "--dash-order": i,
                      width: `${(security[o.key] / total) * 100}%`,
                      minWidth: 3,
                      background: o.color,
                    } as CSSProperties
                  }
                />
              ) : null
            )}
          </div>
          <div className="mt-1.5 flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] text-muted-foreground">
            {OUTCOMES.map((o) => (
              <span key={o.key} className="inline-flex items-center gap-1">
                <span
                  aria-hidden="true"
                  className="size-2 rounded-[2px]"
                  style={{ background: o.color }}
                />
                {o.label}{" "}
                <span className="font-semibold text-foreground tabular-nums">
                  {NUMBER.format(security[o.key])}
                </span>
              </span>
            ))}
          </div>
        </>
      )}
    </Item>
  )
}
