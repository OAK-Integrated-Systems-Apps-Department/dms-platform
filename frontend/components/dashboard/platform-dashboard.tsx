"use client"

import { LoadFailed } from "@/components/ui/load-failed"
import { Fragment, useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import Link from "next/link"
import {
  Activity,
  Building2,
  ChevronDown,
  FileStack,
  HardDrive,
  Search,
  Users,
} from "lucide-react"

import { BarList, type BarItem } from "@/components/dashboard/charts"
import { ColumnChart } from "@/components/dashboard/column-chart"
import { DonutChart, type DonutSlice } from "@/components/dashboard/donut-chart"
import { TrendChart, type TrendPoint } from "@/components/dashboard/trend-chart"
import { FriendlyDate } from "@/components/documents/browser-items"
import { Badge } from "@/components/ui/badge"
import { buttonVariants } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { LoadingState } from "@/components/ui/loading-state"
import {
  fetchPlatformOrganisationBreakdown,
  fetchPlatformOverview,
  type PlatformFileFamilyDto,
  type PlatformOrganisationDto,
} from "@/lib/platform/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

type SortKey =
  | "tenantName"
  | "documents"
  | "storageBytes"
  | "users"
  | "createdAt"
  | "documentsAddedLast30Days"

type StatusFilter = "all" | "active" | "suspended" | "inactive"

const SORTS: { value: SortKey; label: string }[] = [
  { value: "storageBytes", label: "Storage" },
  { value: "documents", label: "Documents" },
  { value: "documentsAddedLast30Days", label: "Recent activity" },
  { value: "users", label: "Users" },
  { value: "tenantName", label: "Name" },
  { value: "createdAt", label: "Newest" },
]

const FILTERS: { value: StatusFilter; label: string }[] = [
  { value: "all", label: "All" },
  { value: "active", label: "Active" },
  { value: "suspended", label: "Suspended" },
  { value: "inactive", label: "Deactivated" },
]

/** What an organisation is now, in one word - same vocabulary as the Organisations screen. */
function stateOf(org: PlatformOrganisationDto): {
  label: string
  variant: "muted" | "gold" | "outline"
} {
  if (org.deactivated) return { label: "Deactivated", variant: "muted" }
  if (org.status === "suspended") return { label: "Suspended", variant: "gold" }
  if (org.status === "archived") return { label: "Archived", variant: "muted" }
  return { label: "Active", variant: "outline" }
}

function matchesFilter(org: PlatformOrganisationDto, filter: StatusFilter) {
  if (filter === "all") return true
  if (filter === "inactive") return org.deactivated
  if (filter === "suspended")
    return !org.deactivated && org.status === "suspended"
  return !org.deactivated && org.status === "active"
}

/**
 * The home dashboard for a platform administrator.
 *
 * The normal dashboard is tenant-scoped - documents, folders and activity for
 * the caller's own organisation - and a platform admin belongs to no tenant,
 * so `GET /api/v1/dashboard` answers 401 TENANT_CONTEXT_MISSING for them.
 * This answers the question they actually have: which clients are on this
 * installation, how much is each using, and which have gone quiet?
 *
 * Counts, sizes and dates only. Nothing here shows a document title, a folder
 * name or who did what, so running the platform never means being able to
 * read a client's documents.
 */
export function PlatformDashboard() {
  const overview = useQuery({
    queryKey: ["platform", "overview"],
    queryFn: fetchPlatformOverview,
  })
  const [sort, setSort] = useState<SortKey>("storageBytes")
  const [filter, setFilter] = useState<StatusFilter>("all")
  const [search, setSearch] = useState("")
  const [expanded, setExpanded] = useState<string | null>(null)

  const rows = useMemo(() => {
    const data = overview.data?.organisations ?? []
    const needle = search.trim().toLowerCase()
    return data
      .filter((org) => matchesFilter(org, filter))
      .filter(
        (org) =>
          needle === "" ||
          org.tenantName.toLowerCase().includes(needle) ||
          org.domain.toLowerCase().includes(needle)
      )
      .sort((a, b) => {
        // Deactivated organisations always sink, whatever the sort - they are
        // history, and mixing them into a usage ranking is misleading.
        if (a.deactivated !== b.deactivated) return a.deactivated ? 1 : -1
        if (sort === "tenantName")
          return a.tenantName.localeCompare(b.tenantName)
        if (sort === "createdAt") return b.createdAt.localeCompare(a.createdAt)
        return (b[sort] as number) - (a[sort] as number)
      })
  }, [overview.data, sort, filter, search])

  if (overview.isPending)
    return <LoadingState message="Loading the platform overview…" />
  if (overview.isError) {
    return (
      <LoadFailed error={overview.error} fallback="Could not load the platform overview." />
    )
  }

  const { totals, organisations, fileTypes, monthly } = overview.data
  // Bars are relative to the biggest organisation, so the distribution is
  // readable even when one client dwarfs the rest.
  const largest = Math.max(1, ...organisations.map((o) => o.storageBytes))
  const quiet = organisations.filter(
    (o) => !o.deactivated && o.documentsAddedLast30Days === 0
  ).length

  // Live organisations only: a deactivated one still holds storage, but
  // showing it as a share of "what we are running" would misread.
  const live = organisations.filter((o) => !o.deactivated)

  // Colour follows the organisation, not its position in the chart. Slots
  // are handed out in a stable order (oldest first, which never changes),
  // so an organisation keeps its colour as its usage moves it up and down
  // the ranking - otherwise the whole chart repaints whenever one client
  // overtakes another, and nothing can be tracked between visits.
  const colourOf = new Map(
    [...live]
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      .map((org, i) => [org.tenantId, `var(--chart-${(i % 5) + 1})`] as const)
  )

  // Top 5 by storage, with the rest folded into one "Other" slice. A seventh
  // hue is never generated - the palette is a fixed set of six, and slot 6 is
  // reserved for "Other" so it never collides with a real organisation.
  const bySize = [...live].sort((a, b) => b.storageBytes - a.storageBytes)
  const topBySize = bySize.slice(0, 5)
  const restBytes = bySize.slice(5).reduce((sum, o) => sum + o.storageBytes, 0)
  const storageSlices: DonutSlice[] = [
    ...topBySize.map((org) => ({
      key: org.tenantId,
      label: org.tenantName,
      value: org.storageBytes,
      color: colourOf.get(org.tenantId) ?? "var(--chart-1)",
      valueLabel: formatBytes(org.storageBytes),
      detail: `${org.documents.toLocaleString()} documents`,
    })),
    ...(restBytes > 0
      ? [
          {
            key: "other",
            label: `${bySize.length - 5} more`,
            value: restBytes,
            color: "var(--chart-6)",
            valueLabel: formatBytes(restBytes),
          },
        ]
      : []),
  ].filter((slice) => slice.value > 0)

  // Magnitude, not identity - one hue, sorted. Colouring these by
  // organisation would imply a category that the length already encodes.
  const documentBars: BarItem[] = [...live]
    .sort((a, b) => b.documents - a.documents)
    .slice(0, 8)
    .filter((org) => org.documents > 0)
    .map((org) => ({
      key: org.tenantId,
      label: org.tenantName,
      value: org.documents,
      hint: org.domain,
      valueLabel: org.documents.toLocaleString(),
    }))

  // What the disk is filling up with, across every client at once - a
  // question no single organisation's figures answer, and the one that
  // decides what storage to buy next.
  const platformTypeSlices = familySlices(fileTypes)

  // Plans, not sizes: how the client base is distributed commercially.
  // Counted over live organisations, since a deactivated one is no longer a
  // subscription. Colour follows the tier, never how popular it is.
  const tiersPresent = [
    ...TIER_ORDER.filter((tier) =>
      live.some((o) => o.subscriptionTier?.toLowerCase() === tier)
    ),
    // Anything the installation uses that TIER_ORDER has not heard of still
    // shows, rather than silently vanishing from a total that claims to be
    // every organisation.
    ...[
      ...new Set(live.map((o) => o.subscriptionTier?.toLowerCase() ?? "")),
    ].filter((tier) => tier !== "" && !TIER_ORDER.includes(tier)),
  ]
  const tierSlices: DonutSlice[] = tiersPresent
    .map((tier, i) => {
      const on = live.filter((o) => o.subscriptionTier?.toLowerCase() === tier)
      return {
        key: tier,
        label: tier.charAt(0).toUpperCase() + tier.slice(1),
        value: on.length,
        color: `var(--chart-${(i % 6) + 1})`,
        valueLabel: `${on.length}`,
        detail: `${formatBytes(on.reduce((sum, o) => sum + o.storageBytes, 0))} stored`,
      }
    })
    .filter((slice) => slice.value > 0)

  // A separate chart from documents-by-organisation rather than a second
  // series on it: seats and documents are different scales, and the client
  // with the most people is not always the one with the most files.
  const userBars: BarItem[] = [...live]
    .sort((a, b) => b.users - a.users)
    .slice(0, 8)
    .filter((org) => org.users > 0)
    .map((org) => ({
      key: org.tenantId,
      label: org.tenantName,
      value: org.users,
      hint: org.domain,
      valueLabel: org.users.toLocaleString(),
    }))

  const monthlyPoints: TrendPoint[] = monthly.map((m) => {
    const [year, month] = m.month.split("-")
    const date = new Date(Number(year), Number(month) - 1, 1)
    return {
      key: m.month,
      label: date.toLocaleDateString(undefined, { month: "short" }),
      value: m.documents,
      valueLabel: `${m.documents.toLocaleString()} documents · ${formatBytes(m.storageBytes)}`,
    }
  })

  return (
    <div className="flex flex-col gap-5">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Tile
          icon={Building2}
          label="Organisations"
          value={totals.organisations.toLocaleString()}
          detail={[
            `${totals.activeOrganisations} active`,
            totals.suspendedOrganisations > 0 &&
              `${totals.suspendedOrganisations} suspended`,
            totals.deactivatedOrganisations > 0 &&
              `${totals.deactivatedOrganisations} deactivated`,
          ]
            .filter(Boolean)
            .join(" · ")}
        />
        <Tile
          icon={FileStack}
          label="Documents"
          value={totals.documents.toLocaleString()}
          detail={
            quiet > 0
              ? `${quiet} ${quiet === 1 ? "organisation has" : "organisations have"} added nothing in 30 days`
              : "All active in the last 30 days"
          }
        />
        <Tile
          icon={HardDrive}
          label="Storage used"
          value={formatBytes(totals.storageBytes)}
          detail="Live versions only"
        />
        <Tile
          icon={Users}
          label="Users"
          value={totals.users.toLocaleString()}
          detail={
            totals.users > 0 && totals.documents > 0
              ? `${Math.round(totals.documents / totals.users).toLocaleString()} documents per user`
              : "Across every organisation"
          }
        />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <ChartCard
          title="Storage by organisation"
          subtitle="Share of everything stored. Point at a slice for its figures."
        >
          <DonutChart
            slices={storageSlices}
            total={totals.storageBytes}
            centerLabel="stored"
            centerValue={formatBytes(totals.storageBytes)}
            ariaLabel="Storage by organisation"
          />
        </ChartCard>

        <ChartCard
          title="Documents by organisation"
          subtitle="How the document count is distributed, largest first."
        >
          <BarList
            items={documentBars}
            total={totals.documents}
            emptyText="No documents yet."
          />
        </ChartCard>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <ChartCard
          title="Storage by file type"
          subtitle="What the disk is filling up with, across every organisation."
        >
          {platformTypeSlices.length > 0 ? (
            <DonutChart
              slices={platformTypeSlices}
              total={totals.storageBytes}
              centerLabel="stored"
              centerValue={formatBytes(totals.storageBytes)}
              ariaLabel="Storage by file type across every organisation"
            />
          ) : (
            <p className="py-8 text-center text-[13px] text-muted-foreground">
              Nothing stored yet.
            </p>
          )}
        </ChartCard>

        <ChartCard
          title="Organisations by plan"
          subtitle="How the client base is split across subscription tiers."
        >
          {tierSlices.length > 0 ? (
            <DonutChart
              slices={tierSlices}
              centerLabel={
                totals.activeOrganisations === 1
                  ? "organisation"
                  : "organisations"
              }
              centerValue={`${live.length}`}
              ariaLabel="Organisations by subscription plan"
            />
          ) : (
            <p className="py-8 text-center text-[13px] text-muted-foreground">
              No active organisations.
            </p>
          )}
        </ChartCard>

        <ChartCard
          title="Users by organisation"
          subtitle="Seats in use, largest first — a different ranking to documents."
        >
          <BarList
            items={userBars}
            total={totals.users}
            emptyText="No users yet."
          />
        </ChartCard>
      </div>

      <ChartCard
        title="Documents added each month"
        subtitle="Across every organisation, over the last 12 months."
      >
        <TrendChart
          points={monthlyPoints}
          ariaLabel="Documents added each month across every organisation"
          emptyText="Nothing has been uploaded yet."
        />
      </ChartCard>

      <div className="rounded-xl border border-border bg-card">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3">
          <div>
            <h2 className="text-[14px] font-semibold">Organisations</h2>
            <p className="text-[12px] text-muted-foreground">
              Showing {rows.length} of {organisations.length} · select a row for
              detail
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative">
              <Search
                size={13}
                className="absolute top-1/2 left-2.5 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search name or domain"
                aria-label="Search organisations"
                className="h-8 w-[200px] pl-7 text-[12.5px]"
              />
            </div>
            <div
              role="radiogroup"
              aria-label="Filter by state"
              className="flex items-center gap-1 rounded-md border border-border p-0.5"
            >
              {FILTERS.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  role="radio"
                  aria-checked={filter === option.value}
                  onClick={() => setFilter(option.value)}
                  className={cn(
                    "rounded px-2 py-1 text-[12px] font-medium",
                    filter === option.value
                      ? "bg-primary text-primary-foreground"
                      : "text-muted-foreground hover:bg-muted hover:text-foreground"
                  )}
                >
                  {option.label}
                </button>
              ))}
            </div>
            <select
              value={sort}
              onChange={(e) => setSort(e.target.value as SortKey)}
              aria-label="Sort organisations"
              className="h-8 rounded-md border border-border bg-card px-2 text-[12.5px]"
            >
              {SORTS.map((option) => (
                <option key={option.value} value={option.value}>
                  Sort: {option.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {rows.length === 0 ? (
          <div className="px-4 py-10 text-center">
            <p className="text-[13px] font-medium">
              {organisations.length === 0
                ? "No organisations yet"
                : "Nothing matches that"}
            </p>
            <p className="mt-1 text-[12.5px] text-muted-foreground">
              {organisations.length === 0
                ? "Add one from the Organisations screen to get started."
                : "Try a different search or filter."}
            </p>
            {organisations.length === 0 && (
              <Link
                href="/admin/organisations"
                className={cn(
                  buttonVariants({ variant: "outline", size: "sm" }),
                  "mt-3"
                )}
              >
                Go to Organisations
              </Link>
            )}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] text-[13px]">
              <thead className="border-b border-border bg-muted/30 text-left text-[11.5px] tracking-[0.04em] text-muted-foreground uppercase">
                <tr>
                  <th className="px-4 py-2.5 font-semibold">Organisation</th>
                  <th className="px-4 py-2.5 font-semibold">State</th>
                  <th className="px-4 py-2.5 text-right font-semibold">
                    Documents
                  </th>
                  <th className="px-4 py-2.5 font-semibold">Storage</th>
                  <th className="px-4 py-2.5 text-right font-semibold">
                    Users
                  </th>
                  <th className="px-4 py-2.5 font-semibold">Last activity</th>
                  <th className="px-4 py-2.5" />
                </tr>
              </thead>
              <tbody>
                {rows.map((org) => {
                  const state = stateOf(org)
                  const open = expanded === org.tenantId
                  const share = Math.round((org.storageBytes / largest) * 100)
                  return (
                    <Fragment key={org.tenantId}>
                      <tr
                        onClick={() => setExpanded(open ? null : org.tenantId)}
                        className={cn(
                          "cursor-pointer border-b border-border last:border-0 hover:bg-muted/40",
                          org.deactivated && "opacity-60",
                          open && "bg-muted/40"
                        )}
                      >
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-2.5">
                            <Building2
                              size={16}
                              className="shrink-0 text-primary"
                            />
                            <div className="min-w-0">
                              <p className="truncate font-medium">
                                {org.tenantName}
                              </p>
                              <p className="truncate text-[12px] text-muted-foreground">
                                {org.domain} · {org.subscriptionTier}
                              </p>
                            </div>
                          </div>
                        </td>
                        <td className="px-4 py-3">
                          <Badge variant={state.variant} className="rounded-md">
                            {state.label}
                          </Badge>
                        </td>
                        <td className="px-4 py-3 text-right tabular-nums">
                          {org.documents.toLocaleString()}
                          {org.documentsAddedLast30Days > 0 && (
                            <span className="ml-1 text-[11.5px] text-[#2f9e5f]">
                              +{org.documentsAddedLast30Days}
                            </span>
                          )}
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-2">
                            <span className="w-[72px] shrink-0 text-right tabular-nums">
                              {formatBytes(org.storageBytes)}
                            </span>
                            <span
                              className="h-1.5 w-[70px] shrink-0 overflow-hidden rounded-full bg-muted"
                              aria-hidden="true"
                            >
                              <span
                                className="block h-full rounded-full bg-primary"
                                style={{ width: `${share}%` }}
                              />
                            </span>
                          </div>
                        </td>
                        <td className="px-4 py-3 text-right tabular-nums">
                          {org.users.toLocaleString()}
                        </td>
                        <td className="px-4 py-3 text-muted-foreground">
                          {org.lastActivityAt ? (
                            <FriendlyDate iso={org.lastActivityAt} />
                          ) : (
                            <span className="text-[12px]">Never</span>
                          )}
                        </td>
                        <td className="px-4 py-3">
                          <ChevronDown
                            size={15}
                            className={cn(
                              "text-muted-foreground transition-transform",
                              open && "rotate-180"
                            )}
                            aria-hidden="true"
                          />
                        </td>
                      </tr>
                      {open && (
                        <tr className="border-b border-border bg-muted/20 last:border-0">
                          <td colSpan={7} className="px-4 py-4">
                            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                              <Detail
                                label="Share of all storage"
                                value={
                                  totals.storageBytes > 0
                                    ? `${((org.storageBytes / totals.storageBytes) * 100).toFixed(1)}%`
                                    : "—"
                                }
                              />
                              <Detail
                                label="Average document size"
                                value={
                                  org.documents > 0
                                    ? formatBytes(
                                        Math.round(
                                          org.storageBytes / org.documents
                                        )
                                      )
                                    : "—"
                                }
                              />
                              <Detail
                                label="Documents per user"
                                value={
                                  org.users > 0
                                    ? Math.round(
                                        org.documents / org.users
                                      ).toLocaleString()
                                    : "—"
                                }
                              />
                              <Detail
                                label="Added in last 30 days"
                                value={org.documentsAddedLast30Days.toLocaleString()}
                              />
                              <Detail
                                label="Plan"
                                value={org.subscriptionTier}
                              />
                              <Detail label="Domain" value={org.domain} mono />
                              <Detail
                                label="Created"
                                value={<FriendlyDate iso={org.createdAt} />}
                              />
                              <div>
                                <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
                                  Manage
                                </p>
                                <Link
                                  href="/admin/organisations"
                                  className="text-[13px] font-semibold text-primary hover:underline"
                                >
                                  Open in Organisations
                                </Link>
                              </div>
                            </div>
                            <OrganisationBreakdown org={org} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="flex flex-wrap gap-3">
        <Link
          href="/admin/organisations"
          className={cn(buttonVariants({ variant: "outline", size: "sm" }))}
        >
          <Building2 size={14} aria-hidden="true" /> Manage organisations
        </Link>
        <Link
          href="/admin/monitoring"
          className={cn(buttonVariants({ variant: "outline", size: "sm" }))}
        >
          <Activity size={14} aria-hidden="true" /> System monitoring
        </Link>
      </div>
    </div>
  )
}

/**
 * The canonical order of file families - the order they are assigned chart
 * colours in. Deliberately NOT the order this organisation happens to rank
 * them in: a family keeps its colour as its share moves, and two
 * organisations opened one after another use the same colour for PDF.
 */
const FAMILY_ORDER = [
  "PDF",
  "Word",
  "Excel",
  "PowerPoint",
  "Image",
  "Email",
  "Archive",
  "Video",
  "Audio",
  "Text",
  "Other",
]

/**
 * File families as donut slices, coloured by {@link FAMILY_ORDER} rather than
 * by size.
 *
 * Shared by the whole-platform chart and every organisation's own chart, so
 * PDF is the same colour in all of them - the two charts sit on one screen,
 * and a family that changed hue between them would read as two things.
 *
 * Present families take slots 1-5 in canonical order and the rest fold into
 * slot 6, which is reserved for the fold: a seventh hue is never generated,
 * and the fold can never collide with a real family.
 */
function familySlices(families: PlatformFileFamilyDto[]): DonutSlice[] {
  const present = FAMILY_ORDER.filter((label) =>
    families.some((f) => f.label === label && f.storageBytes > 0)
  )
  const slotOf = new Map(
    present.slice(0, 5).map((label, i) => [label, `var(--chart-${i + 1})`])
  )
  const folded = present.slice(5)
  const foldedBytes = families
    .filter((f) => folded.includes(f.label))
    .reduce((sum, f) => sum + f.storageBytes, 0)

  return [
    ...families
      .filter((f) => slotOf.has(f.label))
      .map((family) => ({
        key: family.label,
        label: family.label,
        value: family.storageBytes,
        color: slotOf.get(family.label) as string,
        valueLabel: formatBytes(family.storageBytes),
        detail: `${family.documents.toLocaleString()} ${family.documents === 1 ? "file" : "files"}`,
      })),
    ...(foldedBytes > 0
      ? [
          {
            key: "other-formats",
            label: `${folded.length} more formats`,
            value: foldedBytes,
            color: "var(--chart-6)",
            valueLabel: formatBytes(foldedBytes),
          },
        ]
      : []),
  ]
}

/**
 * Subscription tiers in plan order, smallest first - the order they are
 * assigned colours in, so a tier keeps its colour however many clients are
 * on it. A tier the installation uses but this list does not know about
 * still appears; it simply sorts last.
 */
const TIER_ORDER = ["free", "trial", "basic", "standard", "premium", "enterprise"]

/** The schema's lifecycle values, in the words the rest of the app uses. */
const STATUS_LABELS: Record<string, string> = {
  draft: "Draft",
  published: "Published",
  archived: "Archived",
  "pending-deletion": "Pending deletion",
}

/** "Mar" for "2026-03", in the reader's locale. */
function monthLabel(month: string): string {
  const [year, index] = month.split("-")
  return new Date(Number(year), Number(index) - 1, 1).toLocaleDateString(
    undefined,
    { month: "short" }
  )
}

/**
 * One organisation's own charts, under the figures in its opened row: what
 * kinds of file its storage is made of, and when it put them there.
 *
 * Fetched here rather than with the overview, so opening a row costs one
 * request and closed rows cost nothing. Held to figures only - file families
 * and monthly counts, never a filename or a document title.
 */
function OrganisationBreakdown({ org }: { org: PlatformOrganisationDto }) {
  const breakdown = useQuery({
    queryKey: ["platform", "breakdown", org.tenantId],
    queryFn: () => fetchPlatformOrganisationBreakdown(org.tenantId),
    // The row stays open while the reader reads it; nothing changes underneath
    // in that time, and reopening a row should not re-query.
    staleTime: 60_000,
  })

  if (breakdown.isPending) {
    return (
      <p className="mt-4 border-t border-border pt-4 text-[12.5px] text-muted-foreground">
        Loading this organisation&apos;s breakdown…
      </p>
    )
  }
  if (breakdown.isError) {
    return (
      <LoadFailed error={breakdown.error} fallback="Could not load this organisation's breakdown." quiet />
    )
  }

  const { fileTypes, statuses, monthly } = breakdown.data

  const typeSlices = familySlices(fileTypes)

  // Left in the order the server sent - the document lifecycle. Sorting these
  // by size would make the same chart mean something different for two
  // organisations, which is the whole point of a fixed lifecycle.
  const statusBars: BarItem[] = statuses.map((entry) => ({
    key: entry.status,
    label: STATUS_LABELS[entry.status] ?? entry.status,
    value: entry.documents,
    valueLabel: entry.documents.toLocaleString(),
  }))

  const fileColumns = monthly.map((m) => ({
    key: m.month,
    label: monthLabel(m.month),
    value: m.documents,
    valueLabel: `${m.documents.toLocaleString()} ${m.documents === 1 ? "file" : "files"}`,
  }))

  // Bytes get their own chart rather than a second axis on the one above.
  // A big month by count and a big month by size are different months, and a
  // dual axis would let whichever line was drawn higher decide which.
  const byteColumns = monthly.map((m) => ({
    key: m.month,
    label: monthLabel(m.month),
    value: m.storageBytes,
    valueLabel: formatBytes(m.storageBytes),
  }))

  return (
    <div className="mt-4 grid gap-5 border-t border-border pt-4 lg:grid-cols-2">
      <div>
        <h3 className="text-[13px] font-semibold">Storage by file type</h3>
        <p className="mb-3 text-[11.5px] text-muted-foreground">
          What {org.tenantName}&apos;s storage is made of. Point at a slice for its
          figures.
        </p>
        {typeSlices.length > 0 ? (
          <DonutChart
            slices={typeSlices}
            total={org.storageBytes}
            centerLabel="stored"
            centerValue={formatBytes(org.storageBytes)}
            ariaLabel={`Storage by file type for ${org.tenantName}`}
          />
        ) : (
          <p className="py-8 text-center text-[13px] text-muted-foreground">
            Nothing stored yet.
          </p>
        )}
      </div>
      <div>
        <h3 className="text-[13px] font-semibold">Documents by stage</h3>
        <p className="mb-3 text-[11.5px] text-muted-foreground">
          Where this organisation&apos;s documents are in their lifecycle. Every
          stage is shown, including the empty ones.
        </p>
        <BarList items={statusBars} total={org.documents} />
      </div>
      <div>
        <h3 className="text-[13px] font-semibold">Files added each month</h3>
        <p className="mb-3 text-[11.5px] text-muted-foreground">
          How many, over the last 12 months. Point at a column for its figures.
        </p>
        <ColumnChart
          columns={fileColumns}
          ariaLabel={`Files added each month by ${org.tenantName}`}
          emptyText="Nothing uploaded yet."
        />
      </div>
      <div>
        <h3 className="text-[13px] font-semibold">Storage added each month</h3>
        <p className="mb-3 text-[11.5px] text-muted-foreground">
          How much, over the same 12 months — a busy month and a heavy month
          are not always the same month.
        </p>
        <ColumnChart
          columns={byteColumns}
          ariaLabel={`Storage added each month by ${org.tenantName}`}
          emptyText="Nothing uploaded yet."
        />
      </div>
    </div>
  )
}

/** A titled surface for one chart - the title names the single series, so no legend box is needed. */
function ChartCard({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle: string
  children: React.ReactNode
}) {
  return (
    <div className="rounded-xl border border-border bg-card p-4">
      <h2 className="text-[14px] font-semibold">{title}</h2>
      <p className="mb-3 text-[12px] text-muted-foreground">{subtitle}</p>
      {children}
    </div>
  )
}

function Detail({
  label,
  value,
  mono,
}: {
  label: string
  value: React.ReactNode
  mono?: boolean
}) {
  return (
    <div>
      <p className="mb-1 text-[11px] font-semibold tracking-[0.06em] text-muted-foreground uppercase">
        {label}
      </p>
      <p className={cn("text-[13px]", mono && "font-mono text-[12px]")}>
        {value}
      </p>
    </div>
  )
}

function Tile({
  icon: Icon,
  label,
  value,
  detail,
}: {
  icon: typeof Building2
  label: string
  value: string
  detail: string
}) {
  return (
    <div className="rounded-xl border border-border bg-card p-4">
      <div className="mb-2 flex items-center gap-2 text-muted-foreground">
        <Icon size={15} aria-hidden="true" />
        <span className="text-[12px] font-medium">{label}</span>
      </div>
      <p className="text-[24px] leading-tight font-semibold tabular-nums">
        {value}
      </p>
      {detail && (
        <p className="mt-1 text-[11.5px] text-muted-foreground">{detail}</p>
      )}
    </div>
  )
}
