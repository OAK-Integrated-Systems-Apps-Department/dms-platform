"use client"

import { Grid2x2, Grid3x3, LayoutGrid, Square } from "lucide-react"

import {
  TILE_SIZES,
  setTileSize,
  useTileSize,
  type TileSize,
} from "@/lib/documents/use-browser-view"
import { cn } from "@/lib/utils"

const ICONS: Record<TileSize, typeof Square> = {
  xsmall: Grid3x3,
  small: LayoutGrid,
  medium: Grid2x2,
  large: Square,
}

/**
 * Extra small / small / medium / large tiles for the grid view - how many fit in a row. Sits beside the grid/list switch and is only
 * shown while the grid is showing. The choice is remembered per browser for every listing that has a grid.
 */
export function TileSizeControl() {
  const size = useTileSize()
  return (
    <div
      role="radiogroup"
      aria-label="Tile size"
      className="inline-flex rounded-btn border border-border p-0.5"
    >
      {TILE_SIZES.map((option) => {
        const Icon = ICONS[option.value]
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={size === option.value}
            aria-label={option.label}
            title={option.label}
            onClick={() => setTileSize(option.value)}
            className={cn(
              "flex size-7 items-center justify-center rounded",
              size === option.value
                ? "bg-primary/15 text-foreground"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            <Icon size={15} />
          </button>
        )
      })}
    </div>
  )
}
