"use client"

import { cn } from "@/lib/utils"

interface ToggleProps {
  checked: boolean
  onChange: (checked: boolean) => void
  label?: string
}

/** Small pill switch used in the Settings panel. */
export function Toggle({ checked, onChange, label }: ToggleProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className={cn(
        "relative h-[22px] w-10 shrink-0 rounded-full transition-colors",
        checked ? "bg-primary" : "bg-[var(--border-strong)]"
      )}
    >
      <span
        className={cn(
          "absolute top-0.5 size-[18px] rounded-full bg-white shadow-[0_1px_2px_rgba(0,0,0,0.25)] transition-[left]",
          checked ? "left-[20px]" : "left-0.5"
        )}
      />
    </button>
  )
}
