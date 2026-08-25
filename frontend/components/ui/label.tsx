import type { LabelHTMLAttributes } from "react"

import { cn } from "@/lib/utils"

/** Small uppercase field label — the same treatment the wireframes use for every form field. */
export function Label({
  className,
  ...props
}: LabelHTMLAttributes<HTMLLabelElement>) {
  return (
    <label
      data-slot="label"
      className={cn(
        "mb-[7px] block text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase",
        className
      )}
      {...props}
    />
  )
}
