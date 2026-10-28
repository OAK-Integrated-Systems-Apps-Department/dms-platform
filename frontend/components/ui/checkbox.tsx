"use client"

import { useId } from "react"
import { Check } from "lucide-react"

import { cn } from "@/lib/utils"

interface CheckboxProps {
  id?: string
  label: string
  /** A second, quieter line under the label — a caveat or an explanation the label is too short to carry. */
  hint?: string
  checked: boolean
  onCheckedChange: (checked: boolean) => void
  disabled?: boolean
}

/**
 * Label + square checkbox row, used for the permission lists on the
 * Add/Edit Role screen (mirrors `checkbox-ph` in the wireframe and the
 * `.perm-row` pattern from the permission-config screen).
 */
export function Checkbox({
  id,
  label,
  hint,
  checked,
  onCheckedChange,
  disabled,
}: CheckboxProps) {
  const hintId = useId()
  return (
    <label
      htmlFor={id}
      className={cn(
        "flex items-center justify-between gap-3 py-[9px] text-[13px] text-foreground",
        disabled ? "cursor-not-allowed opacity-60" : "cursor-pointer"
      )}
    >
      <span>
        {label}
        {hint && (
          <span id={hintId} className="mt-0.5 block text-[12px] text-muted-foreground">
            {hint}
          </span>
        )}
      </span>
      <button
        type="button"
        id={id}
        role="checkbox"
        aria-checked={checked}
        aria-describedby={hint ? hintId : undefined}
        disabled={disabled}
        onClick={() => onCheckedChange(!checked)}
        className={cn(
          "flex size-[18px] shrink-0 items-center justify-center rounded-[5px] border transition-colors",
          checked
            ? "border-primary bg-primary text-primary-foreground"
            : "border-[var(--border-strong)] bg-card",
          disabled && "cursor-not-allowed"
        )}
      >
        {checked && <Check size={12} strokeWidth={3} />}
      </button>
    </label>
  )
}
