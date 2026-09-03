import type { ReactNode } from "react"
import { Info } from "lucide-react"

import { Tooltip, TooltipContent, TooltipTrigger } from "./tooltip"

interface LabelWithHintProps {
  htmlFor?: string
  children: ReactNode
  hint: string
}

/**
 * Same visual treatment as `Label`, plus a small info icon that shows
 * `hint` on hover/focus. Deliberately built as its own `<label>` + `<div>`
 * pair rather than nesting the icon button inside `Label` itself — a
 * clickable element inside a <label> can trigger the label's own
 * "focus the referenced control" behavior unexpectedly.
 */
export function LabelWithHint({ htmlFor, children, hint }: LabelWithHintProps) {
  return (
    <div className="mb-[7px] flex items-center gap-1.5">
      <label
        htmlFor={htmlFor}
        className="text-[11px] font-semibold tracking-[0.07em] text-muted-foreground uppercase"
      >
        {children}
      </label>
      <Tooltip>
        <TooltipTrigger
          render={(props) => (
            <button
              {...props}
              type="button"
              aria-label={`About the ${typeof children === "string" ? children.toLowerCase() : "field"} field`}
              className="flex text-[var(--ink-faint)] transition-colors hover:text-muted-foreground"
            >
              <Info size={12} />
            </button>
          )}
        />
        <TooltipContent className="normal-case">{hint}</TooltipContent>
      </Tooltip>
    </div>
  )
}