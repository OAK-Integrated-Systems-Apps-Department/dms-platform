import type { LucideIcon } from "lucide-react"

import { cn } from "@/lib/utils"

/**
 * A line icon on a soft tint of its colour with a fine ring - the one icon
 * style used across the dashboard and Help. Flat on purpose: no gradients or
 * drop edges, so it stays crisp and calm in both themes.
 */
export function IconTile({
  icon: Icon,
  color,
  size = 36,
  className,
}: {
  icon: LucideIcon
  /** Any CSS colour, including theme variables (e.g. "var(--primary)"). */
  color: string
  /** The tile's width and height in pixels; the icon scales with it. */
  size?: number
  className?: string
}) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        "flex shrink-0 items-center justify-center rounded-[10px] transition-colors",
        className
      )}
      style={{
        width: size,
        height: size,
        color,
        background: `color-mix(in oklab, ${color} 11%, var(--card))`,
        boxShadow: `inset 0 0 0 1px color-mix(in oklab, ${color} 20%, transparent)`,
      }}
    >
      <Icon size={Math.round(size * 0.5)} strokeWidth={1.75} />
    </span>
  )
}
