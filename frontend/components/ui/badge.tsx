import type { ReactNode } from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const badgeVariants = cva(
  "inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-full border px-2.5 py-0.5 text-[11.5px] font-semibold whitespace-nowrap [&_svg]:pointer-events-none [&_svg]:size-3",
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

/** Small pill label — role name tags, selected-count and read-only indicators. */
function Badge({ className, variant, children }: BadgeProps) {
  return (
    <span
      data-slot="badge"
      className={cn(badgeVariants({ variant }), className)}
    >
      {children}
    </span>
  )
}

export { Badge, badgeVariants }
