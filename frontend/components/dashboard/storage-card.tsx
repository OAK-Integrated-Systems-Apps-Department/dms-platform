"use client"

import { HardDrive } from "lucide-react"

import { NUMBER } from "@/components/dashboard/charts"
import {
  DashCard,
  DashSkeleton,
  EmptyState,
  Pending,
} from "@/components/dashboard/dash-card"
import { DonutChart } from "@/components/dashboard/donut-chart"
import { FORMATS } from "@/components/dashboard/formats"
import type { DashboardDto } from "@/lib/dashboard/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"

/**
 * What the storage is made of, by kind of file, as a donut with its legend.
 * Only what's used - the system has no storage quota, so there's no
 * "available" or "total" to show.
 */
export function StorageCard({
  data,
  order,
}: {
  data?: DashboardDto
  order: number
}) {
  const formats = data?.formats.filter((f) => f.bytes > 0) ?? []
  const total = formats.reduce((sum, f) => sum + f.bytes, 0)

  return (
    <DashCard
      order={order}
      title="Storage"
      subtitle="Current versions, by file type"
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
      ) : total === 0 ? (
        <EmptyState
          icon={HardDrive}
          title="Nothing stored yet"
          text="Storage fills up as documents are added."
        />
      ) : (
        <DonutChart
          ariaLabel="Storage by file type"
          centerValue={formatBytes(total)}
          centerLabel="used"
          slices={formats.map((f) => ({
            key: f.format,
            label: FORMATS[f.format].label,
            value: f.bytes,
            valueLabel: formatBytes(f.bytes),
            detail: `${NUMBER.format(f.count)} ${f.count === 1 ? "file" : "files"}`,
            color: FORMATS[f.format].color,
          }))}
        />
      )}
    </DashCard>
  )
}
