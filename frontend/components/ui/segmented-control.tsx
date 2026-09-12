"use client"

import { cn } from "@/lib/utils"

interface SegmentedOption<T extends string> {
  value: T
  label: string
}

interface SegmentedControlProps<T extends string> {
  value: T
  onChange: (value: T) => void
  options: SegmentedOption<T>[]
  disabled?: boolean
}

/**
 * Two/three-way pill switch — used for the "● Active / ○ Inactive"
 * status field on the Add/Edit Role screen.
 */
export function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
  disabled,
}: SegmentedControlProps<T>) {
  return (
    <div className="inline-flex flex-wrap gap-2" role="radiogroup">
      {options.map((option) => {
        const active = option.value === value
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={active}
            disabled={disabled}
            onClick={() => onChange(option.value)}
            className={cn(
              "flex h-10 items-center gap-2 rounded-btn border px-3.5 text-[13px] font-medium transition-colors",
              active
                ? "border-primary bg-primary/10 text-primary"
                : "border-[var(--border-strong)] bg-muted text-muted-foreground",
              disabled && "cursor-not-allowed opacity-60"
            )}
          >
            <span
              className={cn(
                "size-[7px] rounded-full",
                active ? "bg-primary" : "bg-[var(--border-strong)]"
              )}
            />
            {option.label}
          </button>
        )
      })}
    </div>
  )
}