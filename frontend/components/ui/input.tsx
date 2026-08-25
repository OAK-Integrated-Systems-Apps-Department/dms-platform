import { forwardRef, type InputHTMLAttributes } from "react"

import { cn } from "@/lib/utils"

export type InputProps = InputHTMLAttributes<HTMLInputElement>

/**
 * Text input used across forms (Add Role, Add User, ...). Matches the
 * focus/invalid treatment already established by `Button` so every form
 * control in the app feels like one family.
 */
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { className, ...props },
  ref
) {
  return (
    <input
      ref={ref}
      data-slot="input"
      className={cn(
        "h-10 w-full rounded-[9px] border border-[var(--border-strong)] bg-muted px-3 text-sm text-foreground transition-colors outline-none placeholder:text-[var(--ink-faint)]",
        "focus-visible:border-primary focus-visible:bg-card focus-visible:ring-3 focus-visible:ring-primary/15",
        "disabled:cursor-not-allowed disabled:opacity-50",
        "aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20",
        className
      )}
      {...props}
    />
  )
})
