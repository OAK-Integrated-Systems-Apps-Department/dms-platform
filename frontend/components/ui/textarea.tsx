import { forwardRef, type TextareaHTMLAttributes } from "react"

import { cn } from "@/lib/utils"

export type TextareaProps = TextareaHTMLAttributes<HTMLTextAreaElement>

/** Multi-line text field — same visual language as `Input`. */
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
  function Textarea({ className, rows = 4, ...props }, ref) {
    return (
      <textarea
        ref={ref}
        data-slot="textarea"
        rows={rows}
        className={cn(
          "w-full min-h-[110px] resize-y rounded-btn border border-[var(--border-strong)] bg-muted px-3 py-2.5 text-sm text-foreground transition-colors outline-none placeholder:text-[var(--ink-faint)]",
          "focus-visible:border-primary focus-visible:bg-card focus-visible:ring-3 focus-visible:ring-primary/15",
          "disabled:cursor-not-allowed disabled:opacity-50",
          "aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20",
          className
        )}
        {...props}
      />
    )
  }
)