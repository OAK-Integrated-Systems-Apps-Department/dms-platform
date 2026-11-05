import type { ReactNode } from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const badgeVariants = cva(
  "inline-flex h-6 w-fit shrink-0 items-center justify-center gap-1 rounded-btn border px-2.5 text-[12px] font-semibold whitespace-nowrap [&_svg]:pointer-events-none [&_svg]:size-3",
  {
    variants: {
      variant: {
        default: "border-transparent bg-primary/10 text-primary",
        muted: "border-border bg-muted text-muted-foreground",
        outline: "border-[var(--border-strong)] bg-card text-foreground",
        gold: "border-transparent bg-[var(--gold-tint)] text-[var(--gold-foreground)]",
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
  title?: string
}

/**
 * Small pill label — role name tags, selected-count and read-only indicators.
 *
 * Size and corner radius are intentionally NOT overridable per call site: any `rounded-*`, `text-[..px]`, `px-*`,
 * `py-*`, `h-*`, `tracking-*` or `uppercase` class passed via `className` is stripped before merging, so every label
 * in the app is the same height (24px), the same radius as the buttons (`rounded-btn`) and the same type size. Change
 * it by editing the base classes above, not by passing one in. (`font-mono` and `gap-*` are still allowed.)
 */
function Badge({ className, variant, children, title }: BadgeProps) {
  const withoutSizing = className
    ?.split(" ")
    .filter((token) => token && !/^(rounded(-|$)|text-\[\d|px-|py-|h-|tracking-|uppercase$|normal-case$)/.test(token))
    .join(" ")

  return (
    <span
      data-slot="badge"
      title={title}
      className={cn(badgeVariants({ variant }), withoutSizing)}
    >
      {children}
    </span>
  )
}

export { Badge, badgeVariants }