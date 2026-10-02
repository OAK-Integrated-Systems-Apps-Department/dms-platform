import { Check } from "lucide-react"

import { cn } from "@/lib/utils"

const STEPS = [
  { label: "Prepare", hint: "Template and sheet" },
  { label: "Upload files", hint: "Files and checks" },
  { label: "Processing", hint: "Documents created" },
  { label: "Results", hint: "Review and fix" },
] as const

/**
 * Where a template import stands: Prepare → Upload files → Processing →
 * Results. `current` is the 1-based step in progress; steps before it show
 * as done. Pass `complete` once the last step is reached and finished.
 */
export function ImportStepper({
  current,
  complete = false,
}: {
  current: 1 | 2 | 3 | 4
  complete?: boolean
}) {
  return (
    <ol
      aria-label="Import progress"
      className="mb-5 grid grid-cols-2 gap-2 rounded-xl border border-border bg-card p-2 shadow-[var(--ledger-shadow)] sm:grid-cols-4"
    >
      {STEPS.map((step, index) => {
        const number = index + 1
        const done = number < current || (complete && number === current)
        const active = number === current && !complete
        return (
          <li
            key={step.label}
            aria-current={active ? "step" : undefined}
            className={cn(
              "flex items-center gap-3 rounded-lg px-3 py-2.5",
              active && "bg-primary/10"
            )}
          >
            <span
              aria-hidden="true"
              className={cn(
                "flex size-7 shrink-0 items-center justify-center rounded-full text-[12.5px] font-semibold",
                done
                  ? "bg-primary text-primary-foreground"
                  : active
                    ? "border-2 border-primary text-primary"
                    : "border border-border text-muted-foreground"
              )}
            >
              {done ? <Check size={14} strokeWidth={3} /> : number}
            </span>
            <span className="min-w-0">
              <span
                className={cn(
                  "block truncate text-[13px] font-semibold",
                  !done && !active && "text-muted-foreground"
                )}
              >
                {step.label}
                <span className="sr-only">
                  {done ? " (done)" : active ? " (current)" : ""}
                </span>
              </span>
              <span className="block truncate text-[11.5px] text-muted-foreground">
                {step.hint}
              </span>
            </span>
          </li>
        )
      })}
    </ol>
  )
}
