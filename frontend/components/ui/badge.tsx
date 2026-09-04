import type { ReactNode } from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const badgeVariants = cva(
  "inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-[5px] border px-2.5 py-0.5 text-[11.5px] font-semibold whitespace-nowrap [&_svg]:pointer-events-none [&_svg]:size-3",
  {
    variants: {
      variant: {
        default: "border-transparent bg-primary/10 text-primary",
        muted: "border-border bg-muted text-muted-foreground",
        outline: "border-[var(--border-strong)] bg-card text-foreground",
        gold: "border-transparent bg-[var(--gold-tint)] text-[#7a5b00]",
        rust: "border-transparent bg-[var(--rust-tint)] text-[var(--rust)]",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

interface BadgeProps extends VariantProps<typeof badgeVariants> {
  children: ReactNode
  className?: string
}

/**
 * Small pill label — role name tags, selected-count and read-only indicators.
 *
 * Corner radius is intentionally NOT overridable per call site: any
 * `rounded-*` class passed in via `className` is stripped before merging,
 * so every badge in the app shares exactly one radius, controlled only by
 * `rounded-[5px]` above. Plenty of call sites still pass a leftover
 * `rounded-[10px]` from before this existed — that's harmless now, it's
 * dropped here rather than needing to be cleaned up at 15+ call sites.
 * Change the radius by editing the line above, not by passing one in.
 */
function Badge({ className, variant, children }: BadgeProps) {
  const withoutRadius = className
    ?.split(" ")
    .filter((token) => token && !/^rounded(-|$)/.test(token))
    .join(" ")

  return (
    <span
      data-slot="badge"
      className={cn(badgeVariants({ variant }), withoutRadius)}
    >
      {children}
    </span>
  )
}

export { Badge, badgeVariants }