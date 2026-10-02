import {
  CheckCircle2,
  FileSpreadsheet,
  Upload,
  type LucideIcon,
} from "lucide-react"

import { cn } from "@/lib/utils"

export type ImportMethod = "quick" | "template"

const METHODS: {
  value: ImportMethod
  icon: LucideIcon
  title: string
  description: string
  points: string[]
}[] = [
  {
    value: "quick",
    icon: Upload,
    title: "Quick upload",
    description: "Drop files and add them as documents straight away.",
    points: ["No template or metadata", "One type and folder for all files"],
  },
  {
    value: "template",
    icon: FileSpreadsheet,
    title: "Import with a template",
    description:
      "Give every file its own field values from an Excel or CSV sheet.",
    points: ["Per-file metadata", "Rows checked before importing"],
  },
]

/** The two ways to bring documents in, as selectable cards. */
export function MethodPicker({
  value,
  onChange,
}: {
  value: ImportMethod
  onChange: (value: ImportMethod) => void
}) {
  return (
    <div
      role="radiogroup"
      aria-label="How do you want to import?"
      className="grid gap-3 sm:grid-cols-2"
    >
      {METHODS.map((method) => {
        const selected = method.value === value
        return (
          <button
            key={method.value}
            type="button"
            role="radio"
            aria-checked={selected}
            onClick={() => onChange(method.value)}
            className={cn(
              "relative flex items-start gap-3.5 rounded-xl border bg-card p-4 text-left shadow-[var(--ledger-shadow)] transition-colors",
              selected
                ? "border-primary ring-1 ring-primary"
                : "border-border hover:border-primary/50 hover:bg-muted/40"
            )}
          >
            <span
              aria-hidden="true"
              className={cn(
                "flex size-11 shrink-0 items-center justify-center rounded-lg",
                selected
                  ? "bg-primary text-primary-foreground"
                  : "bg-primary/10 text-primary"
              )}
            >
              <method.icon size={20} />
            </span>
            <span className="min-w-0 pr-6">
              <span className="block text-[14.5px] font-semibold">
                {method.title}
              </span>
              <span className="mt-0.5 block text-[12.5px] leading-[1.55] text-muted-foreground">
                {method.description}
              </span>
              <span className="mt-2 flex flex-wrap gap-1.5">
                {method.points.map((point) => (
                  <span
                    key={point}
                    className="rounded-full bg-muted px-2 py-0.5 text-[11.5px] text-muted-foreground"
                  >
                    {point}
                  </span>
                ))}
              </span>
            </span>
            {selected && (
              <CheckCircle2
                size={18}
                aria-hidden="true"
                className="absolute top-3.5 right-3.5 text-primary"
              />
            )}
          </button>
        )
      })}
    </div>
  )
}
