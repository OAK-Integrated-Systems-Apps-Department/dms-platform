"use client"

import { useState, type CSSProperties } from "react"

import { NUMBER, share } from "@/components/dashboard/charts"
import { DashCard, DashSkeleton } from "@/components/dashboard/dash-card"
import { FORMATS } from "@/components/dashboard/formats"
import { useBox } from "@/components/dashboard/motion"
import type { DashboardDto } from "@/lib/dashboard/api"
import { formatBytes } from "@/lib/quarantine/quarantine-badges"
import { cn } from "@/lib/utils"

const HEIGHT = 200
const GAP = 3
/** Formats under this share of the bytes are drawn together, so none is an unreadable sliver. */
const SMALL = 0.01

interface Tile {
  key: string
  label: string
  color: string
  bytes: number
  count: number
}

interface Placed extends Tile {
  x: number
  y: number
  w: number
  h: number
}

/** The worst aspect ratio in a row of areas laid along `side` - squarify keeps it near 1. */
function worst(row: number[], side: number): number {
  const sum = row.reduce((s, a) => s + a, 0)
  const max = Math.max(...row)
  const min = Math.min(...row)
  return Math.max(
    (side * side * max) / (sum * sum),
    (sum * sum) / (side * side * min)
  )
}

/**
 * Squarified treemap (Bruls, Huizing & van Wijk): tiles in order, largest
 * first, each row closed off when adding the next would make tiles thinner.
 */
function squarify(tiles: Tile[], width: number, height: number): Placed[] {
  const total = tiles.reduce((s, t) => s + t.bytes, 0)
  if (total <= 0 || width <= 0) return []
  const scale = (width * height) / total
  const areas = tiles.map((t) => t.bytes * scale)
  const placed: Placed[] = []
  let rect = { x: 0, y: 0, w: width, h: height }
  let row: number[] = []
  let start = 0

  function lay(count: number) {
    const sum = row.reduce((s, a) => s + a, 0)
    if (rect.w >= rect.h) {
      const w = sum / rect.h
      let y = rect.y
      row.forEach((area, i) => {
        const h = area / w
        placed.push({ ...tiles[start + i], x: rect.x, y, w, h })
        y += h
      })
      rect = { x: rect.x + w, y: rect.y, w: rect.w - w, h: rect.h }
    } else {
      const h = sum / rect.w
      let x = rect.x
      row.forEach((area, i) => {
        const w = area / h
        placed.push({ ...tiles[start + i], x, y: rect.y, w, h })
        x += w
      })
      rect = { x: rect.x, y: rect.y + h, w: rect.w, h: rect.h - h }
    }
    start += count
    row = []
  }

  for (let i = 0; i < areas.length; i++) {
    const side = Math.min(rect.w, rect.h)
    if (
      row.length === 0 ||
      worst([...row, areas[i]], side) <= worst(row, side)
    ) {
      row.push(areas[i])
    } else {
      lay(row.length)
      row.push(areas[i])
    }
  }
  if (row.length) lay(row.length)
  return placed
}

function tilesOf(formats: DashboardDto["formats"]): Tile[] {
  const total = formats.reduce((s, f) => s + f.bytes, 0)
  const tiles: Tile[] = []
  const small = { bytes: 0, count: 0 }
  for (const f of formats) {
    if (total > 0 && f.bytes / total < SMALL) {
      small.bytes += f.bytes
      small.count += f.count
    } else {
      tiles.push({
        key: f.format,
        label: FORMATS[f.format].label,
        color: FORMATS[f.format].color,
        bytes: f.bytes,
        count: f.count,
      })
    }
  }
  if (small.count > 0) {
    // Its own share, but never smaller than a tile you can point at.
    tiles.push({
      key: "small",
      label: "Small files",
      color: "#8b968f",
      bytes: Math.max(small.bytes, total * SMALL),
      count: small.count,
    })
  }
  return tiles.sort((a, b) => b.bytes - a.bytes)
}

/** Where the storage goes, by kind of file - area is size. */
export function StorageTreemap({
  data,
  order,
}: {
  data?: DashboardDto
  order: number
}) {
  const [frame, box] = useBox<HTMLDivElement>()
  const [hover, setHover] = useState<string | null>(null)
  const formats = data?.formats ?? []
  const total = formats.reduce((s, f) => s + f.bytes, 0)
  const tiles = squarify(tilesOf(formats), box.width, HEIGHT)
  const exact = (key: string) =>
    key === "small"
      ? formats
          .filter((f) => total > 0 && f.bytes / total < SMALL)
          .reduce((s, f) => s + f.bytes, 0)
      : (formats.find((f) => f.format === key)?.bytes ?? 0)

  return (
    <DashCard
      order={order}
      title="Storage by file type"
      subtitle={
        data
          ? `${formatBytes(total)} in the current versions`
          : "Current versions"
      }
    >
      {!data ? (
        <DashSkeleton className="h-[300px] w-full" />
      ) : total === 0 ? (
        <p className="py-16 text-center text-[13px] text-muted-foreground">
          No documents yet.
        </p>
      ) : (
        <>
          <div
            ref={frame}
            className="relative"
            style={{ height: HEIGHT }}
            onPointerLeave={() => setHover(null)}
          >
            {tiles.map((tile, i) => {
              const roomy = tile.w > 84 && tile.h > 44
              return (
                <div
                  key={tile.key}
                  onPointerEnter={() => setHover(tile.key)}
                  title={`${tile.label}: ${formatBytes(exact(tile.key))} · ${NUMBER.format(tile.count)} files`}
                  className={cn(
                    "dash-tile-in absolute overflow-hidden rounded-lg p-2 text-white transition-[filter,transform] duration-200",
                    hover &&
                      hover !== tile.key &&
                      "brightness-[0.85] saturate-[0.7]",
                    hover === tile.key && "z-10 scale-[1.015]"
                  )}
                  style={
                    {
                      "--dash-order": i,
                      left: tile.x + GAP / 2,
                      top: tile.y + GAP / 2,
                      width: Math.max(0, tile.w - GAP),
                      height: Math.max(0, tile.h - GAP),
                      background: `linear-gradient(145deg, color-mix(in oklab, ${tile.color} 80%, white), ${tile.color} 55%, color-mix(in oklab, ${tile.color} 80%, black))`,
                      boxShadow: `inset 0 1px 0 rgba(255,255,255,0.25), 0 6px 16px -10px ${tile.color}`,
                    } as CSSProperties
                  }
                >
                  {roomy && (
                    <>
                      <div className="truncate text-[12px] font-semibold drop-shadow-sm">
                        {tile.label}
                      </div>
                      <div className="truncate text-[11px] tabular-nums opacity-90">
                        {formatBytes(exact(tile.key))} ·{" "}
                        {share(exact(tile.key), total)}
                      </div>
                    </>
                  )}
                </div>
              )
            })}
          </div>

          <ul className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1">
            {formats.map((f) => (
              <li
                key={f.format}
                className={cn(
                  "flex items-center gap-2 rounded-md px-1 py-0.5 text-[12px] transition-colors",
                  (hover === f.format ||
                    (hover === "small" && f.bytes / total < SMALL)) &&
                    "bg-muted"
                )}
              >
                <span
                  className="size-2.5 shrink-0 rounded-[3px]"
                  style={{ background: FORMATS[f.format].color }}
                />
                <span className="min-w-0 flex-1 truncate">
                  {FORMATS[f.format].label}
                  <span className="ml-1 text-muted-foreground">
                    ({NUMBER.format(f.count)})
                  </span>
                </span>
                <span className="text-muted-foreground tabular-nums">
                  {formatBytes(f.bytes)}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </DashCard>
  )
}
