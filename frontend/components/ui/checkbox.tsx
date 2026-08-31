"use client"

import { Check } from "lucide-react"

import { cn } from "@/lib/utils"

interface CheckboxProps {
  id?: string
  label: string
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
  checked,
  onCheckedChange,
  disabled,
}: CheckboxProps) {
  return (
    <label
      htmlFor={id}
      className={cn(
        "flex items-center justify-between gap-3 py-[9px] text-[13px] text-foreground",
        disabled ? "cursor-not-allowed opacity-60" : "cursor-pointer"
      )}
    >
      <span>{label}</span>
      <button
        type="button"
        id={id}
        role="checkbox"
        aria-checked={checked}
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
